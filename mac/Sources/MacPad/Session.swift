import CryptoKit
import Foundation
import Network
import os

struct Hello {
    let width: Int
    let height: Int
    let densityDpi: Int
    let clientId: Data
    let deviceName: String
}

protocol SessionDelegate: AnyObject {
    /// Called on the core queue once the tablet has introduced itself.
    func sessionDidReceiveHello(_ session: Session, hello: Hello)
    /// Called on the core queue for every message after HELLO.
    func session(_ session: Session, didReceive type: MsgType, payload: Data)
    /// Wi-Fi pairing: ask the user. `reply` may be called from any thread.
    func sessionNeedsApproval(_ session: Session, deviceName: String, code: String, reply: @escaping (Bool) -> Void)
    /// Called on the core queue exactly once.
    func sessionDidClose(_ session: Session)
}

/// One tablet connection: framing, the Wi-Fi handshake and encryption, and video flow control.
final class Session {
    enum Transport: String {
        case usb = "USB"
        case wifi = "Wi-Fi"
    }

    private enum Phase {
        case handshake, awaitingApproval, awaitingHello, ready
    }

    /// Frames still queued in the network stack before new video is skipped at the capture side.
    private static let maxPendingVideoFrames = 2

    let transport: Transport
    weak var delegate: SessionDelegate?
    private(set) var hello: Hello?

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let log = Logger(subsystem: "com.azhardhiaulhaq.macpad", category: "session")
    private var phase: Phase

    // Guarded by `sendLock`: everything the encoder thread and the core queue share.
    private let sendLock = NSLock()
    private var cipher: FrameCipher?
    private var pendingVideo = 0
    private var closed = false

    init(connection: NWConnection, transport: Transport, queue: DispatchQueue) {
        self.connection = connection
        self.transport = transport
        self.queue = queue
        phase = transport == .wifi ? .handshake : .awaitingHello
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        connection.start(queue: queue)
        readFrame()
    }

    var isClosed: Bool { sendLock.withLock { closed } }

    func close() {
        let first: Bool = sendLock.withLock {
            defer { closed = true }
            return !closed
        }
        guard first else { return }
        connection.cancel()
        queue.async { self.delegate?.sessionDidClose(self) }
    }

    // MARK: Sending

    func send(_ type: MsgType, _ payload: Data = Data(), completion: (() -> Void)? = nil) {
        sendLock.lock()
        defer { sendLock.unlock() }
        guard !closed else { return }
        let done = NWConnection.SendCompletion.contentProcessed { _ in completion?() }
        if let cipher {
            var plain = Data(capacity: payload.count + 1)
            plain.append(type.rawValue)
            plain.append(payload)
            guard let sealed = try? cipher.seal(plain) else { return }
            var header = ByteWriter(capacity: 4)
            header.u32(UInt32(sealed.count))
            connection.batch {
                connection.send(content: header.data, completion: .idempotent)
                connection.send(content: sealed, completion: done)
            }
        } else {
            // Plain path: header and payload go out as two sends to avoid copying video data.
            var header = ByteWriter(capacity: 5)
            header.u32(UInt32(payload.count + 1))
            header.u8(type.rawValue)
            connection.batch {
                connection.send(content: header.data, completion: payload.isEmpty ? done : .idempotent)
                if !payload.isEmpty { connection.send(content: payload, completion: done) }
            }
        }
    }

    var canSendVideo: Bool { sendLock.withLock { !closed && pendingVideo < Self.maxPendingVideoFrames } }

    func sendVideo(_ type: MsgType, _ payload: Data) {
        sendLock.withLock { pendingVideo += 1 }
        send(type, payload) { [weak self] in
            guard let self else { return }
            self.sendLock.withLock { self.pendingVideo -= 1 }
        }
    }

    func sendNoticeAndClose(code: UInt8, message: String) {
        var w = ByteWriter()
        w.u8(code)
        w.string(message)
        send(.notice, w.data) { [weak self] in self?.close() }
    }

    // MARK: Receiving

    private func readFrame() {
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, _, error in
            guard let self else { return }
            guard error == nil, let data, data.count == 4 else { return self.close() }
            let length = Int(data.reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
            // Tablet messages are tiny; before authentication even more so.
            let limit = self.phase == .ready ? 65_536 : 4_096
            guard length > 0, length <= limit else { return self.close() }
            self.connection.receive(minimumIncompleteLength: length, maximumLength: length) { body, _, _, error in
                guard error == nil, let body, body.count == length else { return self.close() }
                self.handle(body)
                if !self.isClosed { self.readFrame() }
            }
        }
    }

    private func handle(_ body: Data) {
        var plain = body
        if let cipher = sendLock.withLock({ cipher }) {
            guard let opened = try? cipher.open(body) else {
                log.error("decrypt failed; closing")
                return close()
            }
            plain = opened
        }
        guard let first = plain.first, let type = MsgType(rawValue: first) else { return }
        let payload = Data(plain.dropFirst())
        do {
            switch phase {
            case .handshake:
                guard type == .wifiHello else { return close() }
                try handleWifiHello(payload)
            case .awaitingApproval:
                close()
            case .awaitingHello:
                guard type == .hello else { return close() }
                var r = ByteReader(payload)
                guard try r.u16() == 1 else { return close() }
                let h = Hello(
                    width: Int(try r.u16()), height: Int(try r.u16()), densityDpi: Int(try r.u16()),
                    clientId: try r.bytes(16), deviceName: try r.string())
                guard h.width > 0, h.height > 0 else { return close() }
                hello = h
                phase = .ready
                delegate?.sessionDidReceiveHello(self, hello: h)
            case .ready:
                delegate?.session(self, didReceive: type, payload: payload)
            }
        } catch {
            log.error("malformed message \(first): \(String(describing: error))")
            close()
        }
    }

    private func handleWifiHello(_ payload: Data) throws {
        var r = ByteReader(payload)
        guard try r.u16() == 1 else { return close() }
        let clientId = try r.bytes(16)
        let clientPub = try r.bytes(65)
        let clientHasKey = try r.u8() == 1
        let name = try r.string()

        let privateKey = P256.KeyAgreement.PrivateKey()
        let serverPub = privateKey.publicKey.x963Representation
        let shared = try privateKey.sharedSecretFromKeyAgreement(
            with: P256.KeyAgreement.PublicKey(x963Representation: clientPub)
        ).withUnsafeBytes { Data($0) }
        let store = PairingStore.shared
        let transcript = clientId + clientPub + store.serverId + serverPub
        let storedKey = store.key(for: clientId)
        let paired = storedKey != nil && clientHasKey

        var reply = ByteWriter(capacity: 82)
        reply.bytes(store.serverId)
        reply.bytes(serverPub)
        reply.u8(paired ? 0 : 1)
        send(.wifiHelloReply, reply.data)

        if let storedKey, paired {
            activate(shared: shared, pairingKey: storedKey, transcript: transcript)
            phase = .awaitingHello
            return
        }

        phase = .awaitingApproval
        let code = Handshake.sas(shared: shared, transcript: transcript)
        delegate?.sessionNeedsApproval(self, deviceName: name, code: code) { [weak self] allowed in
            guard let self else { return }
            self.queue.async {
                guard !self.isClosed, self.phase == .awaitingApproval else { return }
                guard allowed else {
                    self.send(.pairRejected) { [weak self] in self?.close() }
                    return
                }
                // PAIR_ACCEPTED goes out in the clear; everything after it is encrypted.
                self.send(.pairAccepted)
                let key = Handshake.pairingKey(shared: shared, transcript: transcript)
                store.save(key: key, clientId: clientId, name: name)
                self.activate(shared: shared, pairingKey: key, transcript: transcript)
                self.phase = .awaitingHello
            }
        }
    }

    private func activate(shared: Data, pairingKey: Data, transcript: Data) {
        let keys = Handshake.sessionKeys(shared: shared, pairingKey: pairingKey, transcript: transcript)
        sendLock.withLock { cipher = FrameCipher(sendKey: keys.s2c, recvKey: keys.c2s) }
    }
}
