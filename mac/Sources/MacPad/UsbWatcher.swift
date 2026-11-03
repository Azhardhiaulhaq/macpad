import Foundation
import os

/// Watches `adb track-devices`. When a tablet with USB debugging appears, forwards the tablet's
/// 127.0.0.1:47800 to this Mac (`adb reverse`) and opens MacPad on it.
final class UsbWatcher {
    static let androidPackage = "com.azhardhiaulhaq.macpad"

    /// Called with a user-facing problem, or nil when USB is usable.
    var onProblem: ((String?) -> Void)?

    private let queue = DispatchQueue(label: "macpad.usb")
    private let log = Logger(subsystem: "com.azhardhiaulhaq.macpad", category: "usb")
    private let adbPath = UsbWatcher.findAdb()
    private var process: Process?
    private var buffer = Data()
    private var attached = Set<String>()
    /// Serials with a setUp in flight or a retry scheduled; the token invalidates stale callbacks.
    private var pending = [String: UUID]()
    private let workQueue = DispatchQueue(label: "macpad.usb.adb", attributes: .concurrent)
    private static let maxAttempts = 5
    private static let adbTimeout: TimeInterval = 5

    func start() {
        queue.async { self.launch() }
    }

    private static func findAdb() -> String? {
        let env = ProcessInfo.processInfo.environment
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        var candidates = [env["ANDROID_HOME"], env["ANDROID_SDK_ROOT"]].compactMap { $0 }.map { "\($0)/platform-tools/adb" }
        candidates += ["\(home)/Library/Android/sdk/platform-tools/adb", "/opt/homebrew/bin/adb", "/usr/local/bin/adb"]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private func launch() {
        guard let adbPath else {
            onProblem?("USB needs adb. Install Android platform-tools (brew install android-platform-tools).")
            return
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: adbPath)
        process.arguments = ["track-devices"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty else {
                handle.readabilityHandler = nil
                return
            }
            self?.queue.async { self?.consume(data) }
        }
        process.terminationHandler = { [weak self] _ in
            // adb server restarted or was killed: start over, which re-applies `adb reverse`.
            self?.queue.asyncAfter(deadline: .now() + 3) {
                self?.attached.removeAll()
                self?.pending.removeAll()
                self?.buffer.removeAll()
                self?.launch()
            }
        }
        do {
            try process.run()
            self.process = process
            onProblem?(nil)
        } catch {
            onProblem?("Could not start adb: \(error.localizedDescription)")
        }
    }

    /// track-devices output: 4 hex digits of length, then "serial\tstate\n" lines.
    private func consume(_ data: Data) {
        buffer.append(data)
        while buffer.count >= 4 {
            guard let length = Int(String(decoding: buffer.prefix(4), as: UTF8.self), radix: 16) else {
                buffer.removeAll()
                return
            }
            guard buffer.count >= 4 + length else { return }
            let list = String(decoding: buffer.dropFirst(4).prefix(length), as: UTF8.self)
            buffer = Data(buffer.dropFirst(4 + length))
            update(list)
        }
    }

    private func update(_ list: String) {
        var online = Set<String>()
        for line in list.split(separator: "\n") {
            let fields = line.split(separator: "\t")
            if fields.count == 2, fields[1] == "device" { online.insert(String(fields[0])) }
        }
        attached.formIntersection(online)
        // Dropping the token cancels any queued retry for a device that went offline.
        for serial in pending.keys where !online.contains(serial) { pending[serial] = nil }
        for serial in online where !attached.contains(serial) && pending[serial] == nil {
            attemptSetUp(serial, attempt: 1)
        }
    }

    /// Runs setUp off the track-devices queue (adb calls can take seconds). A serial only joins
    /// `attached` once setUp succeeds; failures retry every 2 s, up to `maxAttempts` per attach.
    private func attemptSetUp(_ serial: String, attempt: Int) {
        let token = pending[serial] ?? UUID()
        pending[serial] = token
        workQueue.async { [weak self] in
            guard let self else { return }
            let ok = self.setUp(serial)
            self.queue.async {
                guard self.pending[serial] == token else { return }
                if ok {
                    self.pending[serial] = nil
                    self.attached.insert(serial)
                } else if attempt < Self.maxAttempts {
                    self.queue.asyncAfter(deadline: .now() + 2) {
                        guard self.pending[serial] == token else { return }
                        self.attemptSetUp(serial, attempt: attempt + 1)
                    }
                } else {
                    self.pending[serial] = nil
                    self.log.error("giving up on \(serial, privacy: .public) after \(attempt) attempts")
                }
            }
        }
    }

    /// Success means `adb reverse` worked; a failed app launch is logged but not fatal, since the
    /// reverse alone lets the tablet connect once the user opens MacPad.
    private func setUp(_ serial: String) -> Bool {
        let port = "tcp:\(Ports.usb)"
        let reverse = run(["-s", serial, "reverse", port, port])
        guard reverse == 0 else {
            log.error("adb reverse failed for \(serial, privacy: .public): status \(reverse)")
            return false
        }
        let status = run(["-s", serial, "shell", "am", "start",
                          "-a", "\(Self.androidPackage).CONNECT_USB",
                          "-n", "\(Self.androidPackage)/.MainActivity"])
        log.info("tablet \(serial, privacy: .public) attached; launch status \(status)")
        return true
    }

    /// Returns the exit status, or -1 on launch failure or timeout (the process is terminated).
    private func run(_ arguments: [String]) -> Int32 {
        guard let adbPath else { return -1 }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: adbPath)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        let exited = DispatchSemaphore(value: 0)
        process.terminationHandler = { _ in exited.signal() }
        do {
            try process.run()
        } catch {
            log.error("adb \(arguments.joined(separator: " "), privacy: .public) failed to start: \(error.localizedDescription, privacy: .public)")
            return -1
        }
        if exited.wait(timeout: .now() + Self.adbTimeout) == .timedOut {
            process.terminate()
            if exited.wait(timeout: .now() + 1) == .timedOut { kill(process.processIdentifier, SIGKILL) }
            log.error("adb \(arguments.joined(separator: " "), privacy: .public) timed out")
            return -1
        }
        return process.terminationStatus
    }
}
