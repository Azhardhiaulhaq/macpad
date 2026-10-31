import CryptoKit
import Foundation

/// Key derivation for the Wi-Fi handshake (docs/PROTOCOL.md, "Wi-Fi handshake").
enum Handshake {
    static func hkdf(_ ikm: Data, salt: Data, info: Data, count: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: count)
        return key.withUnsafeBytes { Data($0) }
    }

    static func sas(shared: Data, transcript: Data) -> String {
        let b = hkdf(shared, salt: Data("macpad-sas-v1".utf8), info: transcript, count: 4)
        let v = b.reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return String(format: "%06u", v % 1_000_000)
    }

    static func pairingKey(shared: Data, transcript: Data) -> Data {
        hkdf(shared, salt: Data("macpad-pair-v1".utf8), info: transcript, count: 32)
    }

    static func sessionKeys(shared: Data, pairingKey: Data, transcript: Data) -> (c2s: SymmetricKey, s2c: SymmetricKey) {
        let c2s = hkdf(shared, salt: pairingKey, info: Data("c2s".utf8) + transcript, count: 32)
        let s2c = hkdf(shared, salt: pairingKey, info: Data("s2c".utf8) + transcript, count: 32)
        return (SymmetricKey(data: c2s), SymmetricKey(data: s2c))
    }
}

/// AES-256-GCM framing for the Mac side: sends with direction 2, receives with direction 1.
/// Callers serialize `seal` and `open` separately (send path and receive path).
final class FrameCipher {
    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0

    init(sendKey: SymmetricKey, recvKey: SymmetricKey) {
        self.sendKey = sendKey
        self.recvKey = recvKey
    }

    private static func nonce(direction: UInt32, counter: UInt64) -> AES.GCM.Nonce {
        var w = ByteWriter(capacity: 12)
        w.u32(direction)
        w.u64(counter)
        return try! AES.GCM.Nonce(data: w.data)
    }

    func seal(_ plain: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: sendKey, nonce: Self.nonce(direction: 2, counter: sendCounter))
        sendCounter += 1
        return box.ciphertext + box.tag
    }

    func open(_ sealed: Data) throws -> Data {
        guard sealed.count >= 16 else { throw CryptoKitError.incorrectParameterSize }
        let box = try AES.GCM.SealedBox(
            nonce: Self.nonce(direction: 1, counter: recvCounter),
            ciphertext: sealed.dropLast(16), tag: sealed.suffix(16))
        recvCounter += 1
        return try AES.GCM.open(box, using: recvKey)
    }
}
