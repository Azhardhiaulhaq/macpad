import AppKit
import CoreGraphics
import Foundation
import Network
import os
import SystemConfiguration

/// Owns the single active tablet: its session, virtual display, stream, and input.
/// All state lives on `queue`; the virtual display object is only touched on the main thread.
final class Coordinator: SessionDelegate {
    struct Status {
        enum Connection {
            case idle
            case connected(name: String, transport: Session.Transport)
            case lingering(name: String)
        }

        var connection: Connection = .idle
        var logicalSize: (width: Int, height: Int)?
        var panel: (width: Int, height: Int)?
        var serverProblem: String?
        var usbProblem: String?
    }

    static let graceSeconds = 10

    let queue = DispatchQueue(label: "macpad.core", qos: .userInteractive)
    /// Called on the main thread whenever `status` changes.
    var onChange: (() -> Void)?
    /// Asks the user to approve a Wi-Fi pairing. Called on the main thread.
    var approve: ((_ deviceName: String, _ code: String, _ reply: @escaping (Bool) -> Void) -> Void)?

    private let log = Logger(subsystem: "com.azhardhiaulhaq.macpad", category: "core")
    private var server: Server?
    private let usb = UsbWatcher()
    private let injector = InputInjector()

    private var sessions: [ObjectIdentifier: Session] = [:]
    private var active: Session?
    private var display: VirtualDisplay?
    private var displayID: CGDirectDisplayID = 0
    private var displayOwner: Data?
    private var displayName = ""
    private var streamer: Streamer?
    private var panel = (width: 0, height: 0)
    private var grace: DispatchWorkItem?
    private var askedAccessibility = false
    /// Identifies the current stream so a late failure from a replaced one is ignored.
    private var streamGeneration = 0

    private let statusLock = NSLock()
    private var _status = Status()
    var status: Status { statusLock.withLock { _status } }

    func start() {
        queue.async { [self] in
            server = Server(
                queue: queue,
                onConnection: { [weak self] connection, transport in self?.accept(connection, transport) },
                onError: { [weak self] message in self?.updateStatus { $0.serverProblem = message } })
            server?.start()
        }
        usb.onProblem = { [weak self] message in self?.updateStatus { $0.usbProblem = message } }
        usb.start()
    }

    /// Menu action: switch between "More space" and "Larger text".
    func setDesktopSize(_ size: DesktopSize) {
        Preferences.desktopSize = size
        queue.async { [self] in
            guard display != nil else { return }
            applyPanelSize()
        }
    }

    // MARK: Sessions

    private func accept(_ connection: NWConnection, _ transport: Session.Transport) {
        let session = Session(connection: connection, transport: transport, queue: queue)
        session.delegate = self
        sessions[ObjectIdentifier(session)] = session
        session.start()
    }

    func sessionNeedsApproval(_ session: Session, deviceName: String, code: String, reply: @escaping (Bool) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let approve = self?.approve else { return reply(false) }
            approve(deviceName, code, reply)
        }
    }

    func sessionDidReceiveHello(_ session: Session, hello: Hello) {
        if let current = active, current !== session {
            session.sendNoticeAndClose(code: 2, message: "\(current.hello?.deviceName ?? "Another tablet") is already using this Mac as a screen.")
            return
        }
        guard CGPreflightScreenCaptureAccess() else {
            DispatchQueue.main.async { _ = CGRequestScreenCaptureAccess() }
            session.sendNoticeAndClose(code: 1, message: "Allow Screen Recording for MacPad on your Mac (System Settings, Privacy & Security), then quit and reopen MacPad on the Mac.")
            return
        }
        active = session
        grace?.cancel()
        grace = nil

        var info = ByteWriter()
        info.string(Self.macName)
        info.bytes(PairingStore.shared.serverId)
        let addresses = Self.lanAddresses.prefix(8)
        info.u8(UInt8(addresses.count))
        for address in addresses { info.string(address) }
        session.send(.serverInfo, info.data)

        panel = (hello.width, hello.height)
        if display == nil || displayOwner != hello.clientId {
            destroyDisplay()
            createDisplay(for: hello)
        }
        guard display != nil else {
            session.sendNoticeAndClose(code: 3, message: "MacPad could not create the extra display on this Mac.")
            return
        }
        applyPanelSize()
        updateStatus { $0.connection = .connected(name: hello.deviceName, transport: session.transport) }
    }

    func session(_ session: Session, didReceive type: MsgType, payload: Data) {
        guard session === active else { return }
        var r = ByteReader(payload)
        do {
            switch type {
            case .resize:
                panel = (Int(try r.u16()), Int(try r.u16()))
                applyPanelSize()
            case .pointer:
                let action = InputInjector.Action(rawValue: try r.u8())
                let tool = InputInjector.Tool(rawValue: try r.u8()) ?? .finger
                let buttons = try r.u8()
                let point = displayPoint(x: try r.u16(), y: try r.u16())
                let pressure = Double(try r.u16()) / 65535
                let tiltX = Double(try r.i16()) / 32767
                let tiltY = Double(try r.i16()) / 32767
                guard let action, let point else { return }
                ensureAccessibility()
                injector.pointer(action: action, tool: tool, secondary: buttons & 1 != 0, at: point,
                                 pressure: pressure, tiltX: tiltX, tiltY: tiltY)
            case .scroll:
                let phase = try r.u8()
                guard let point = displayPoint(x: try r.u16(), y: try r.u16()), panel.width > 0 else { return }
                let dx = Double(try r.i16()), dy = Double(try r.i16())
                let scale = CGDisplayBounds(displayID).width / Double(panel.width)
                ensureAccessibility()
                injector.scroll(phase: phase, at: point, delta: CGPoint(x: dx * scale, y: dy * scale))
            case .keyframeRequest:
                streamer?.requestKeyframe()
            case .ping:
                session.send(.pong, payload)
            default:
                break
            }
        } catch {
            log.error("bad \(String(describing: type)) message")
        }
    }

    func sessionDidClose(_ session: Session) {
        sessions.removeValue(forKey: ObjectIdentifier(session))
        guard session === active else { return }
        active = nil
        injector.reset()
        streamer?.stop()
        streamer = nil
        let name = session.hello?.deviceName ?? "Tablet"
        updateStatus { $0.connection = .lingering(name: name) }
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.active == nil else { return }
            self.destroyDisplay()
            self.updateStatus { $0.connection = .idle }
        }
        grace = work
        queue.asyncAfter(deadline: .now() + .seconds(Self.graceSeconds), execute: work)
    }

    // MARK: Display and stream

    private func createDisplay(for hello: Hello) {
        let serial = hello.clientId.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        let dpi = Double(max(hello.densityDpi, 1))
        let mm = CGSize(width: Double(hello.width) / dpi * 25.4, height: Double(hello.height) / dpi * 25.4)
        // Largest logical side any preset can ask for, so both orientations fit the framebuffer.
        let maxSide = Int((Double(max(hello.width, hello.height)) / DesktopSize.moreSpace.divisor).rounded(.up)) + 2
        let logical = Preferences.desktopSize.logicalSize(panelWidth: hello.width, panelHeight: hello.height)
        let name = "MacPad (\(hello.deviceName))"
        let created: VirtualDisplay? = DispatchQueue.main.sync {
            VirtualDisplay(name: name, serial: serial, logical: logical, maxLogicalSide: maxSide, sizeInMillimeters: mm)
        }
        display = created
        displayID = created.map { d in DispatchQueue.main.sync { d.displayID } } ?? 0
        displayOwner = created == nil ? nil : hello.clientId
        displayName = hello.deviceName
        log.info("created display \(self.displayID) for \(hello.deviceName)")
    }

    private func destroyDisplay() {
        guard let old = display else { return }
        display = nil
        displayID = 0
        displayOwner = nil
        updateStatus { $0.logicalSize = nil }
        DispatchQueue.main.async { withExtendedLifetime(old) {} }
    }

    /// Matches the display mode and the stream to the current panel size and preset.
    private func applyPanelSize() {
        guard let display, panel.width > 0, panel.height > 0 else { return }
        let logical = Preferences.desktopSize.logicalSize(panelWidth: panel.width, panelHeight: panel.height)
        let ok = DispatchQueue.main.sync { display.setLogicalSize(logical) }
        if !ok { log.error("could not switch display to \(logical.width)x\(logical.height)") }
        updateStatus {
            $0.logicalSize = logical
            $0.panel = self.panel
        }
        startStreaming()
    }

    private func startStreaming() {
        streamer?.stop()
        streamer = nil
        guard let session = active, displayID != 0 else { return }
        let bitRate = session.transport == .usb ? 24_000_000 : 14_000_000
        streamGeneration += 1
        let generation = streamGeneration
        let output = Streamer.Output(
            config: { [weak session] width, height, sets in
                var w = ByteWriter(capacity: 4 + sets.count)
                w.u16(UInt16(width))
                w.u16(UInt16(height))
                w.bytes(sets)
                session?.send(.videoConfig, w.data)
            },
            frame: { [weak session] payload in session?.sendVideo(.videoFrame, payload) },
            canSend: { [weak session] in session?.canSendVideo ?? false },
            failed: { [weak self, weak session] message in
                self?.queue.async {
                    guard let self, self.streamGeneration == generation else { return }
                    session?.sendNoticeAndClose(code: 3, message: "Screen capture failed on the Mac: \(message)")
                }
            })
        let streamer = Streamer(displayID: displayID, width: panel.width, height: panel.height, bitRate: bitRate, output: output)
        self.streamer = streamer
        Task { await streamer.start() }
    }

    // MARK: Input helpers

    private func displayPoint(x: UInt16, y: UInt16) -> CGPoint? {
        guard displayID != 0 else { return nil }
        let b = CGDisplayBounds(displayID)
        guard b.width > 0 else { return nil }
        let px = b.minX + Double(x) / 65535 * b.width
        let py = b.minY + Double(y) / 65535 * b.height
        return CGPoint(x: min(px, b.maxX - 1), y: min(py, b.maxY - 1))
    }

    private func ensureAccessibility() {
        guard !askedAccessibility, !AXIsProcessTrusted() else { return }
        askedAccessibility = true
        DispatchQueue.main.async {
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            _ = AXIsProcessTrustedWithOptions(options)
        }
    }

    private func updateStatus(_ change: (inout Status) -> Void) {
        statusLock.withLock { change(&_status) }
        DispatchQueue.main.async { [weak self] in self?.onChange?() }
    }

    static var macName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? Host.current().localizedName ?? "Mac"
    }

    /// IPv4 addresses of active, non-loopback interfaces. The tablet remembers them so it can
    /// reach this Mac over Wi-Fi on networks that drop Bonjour multicast.
    static var lanAddresses: [String] {
        var result: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return result }
        defer { freeifaddrs(head) }
        var cursor = head
        while let entry = cursor?.pointee {
            defer { cursor = entry.ifa_next }
            let flags = Int32(entry.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let addr = entry.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let address = String(cString: host)
                // Skip link-local addresses: they never route between the tablet and the Mac.
                if !address.hasPrefix("169.254.") && !result.contains(address) { result.append(address) }
            }
        }
        return result
    }
}
