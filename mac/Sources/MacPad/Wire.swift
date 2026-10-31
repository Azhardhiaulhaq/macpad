import Foundation

/// Message types from docs/PROTOCOL.md.
enum MsgType: UInt8 {
    case hello = 0x01, resize = 0x02, pointer = 0x03, scroll = 0x04, keyframeRequest = 0x05, ping = 0x06
    case wifiHello = 0x10
    case serverInfo = 0x80, videoConfig = 0x81, videoFrame = 0x82, pong = 0x83, notice = 0x84
    case wifiHelloReply = 0x90, pairAccepted = 0x91, pairRejected = 0x92
}

enum Ports {
    static let usb: UInt16 = 47800
    static let wifi: UInt16 = 47801
}

struct ByteWriter {
    private(set) var data: Data

    init(capacity: Int = 64) {
        data = Data()
        data.reserveCapacity(capacity)
    }

    mutating func u8(_ v: UInt8) { data.append(v) }
    mutating func u16(_ v: UInt16) { withUnsafeBytes(of: v.bigEndian) { data.append(contentsOf: $0) } }
    mutating func u32(_ v: UInt32) { withUnsafeBytes(of: v.bigEndian) { data.append(contentsOf: $0) } }
    mutating func u64(_ v: UInt64) { withUnsafeBytes(of: v.bigEndian) { data.append(contentsOf: $0) } }
    mutating func bytes(_ d: Data) { data.append(d) }

    mutating func string(_ s: String) {
        var utf8 = Array(s.utf8)
        if utf8.count > 65535 { utf8 = Array(utf8.prefix(65535)) }
        u16(UInt16(utf8.count))
        data.append(contentsOf: utf8)
    }
}

struct ByteReader {
    struct Truncated: Error {}

    private let data: Data
    private var offset: Int

    init(_ data: Data) {
        self.data = data
        offset = data.startIndex
    }

    mutating func u8() throws -> UInt8 {
        guard offset < data.endIndex else { throw Truncated() }
        defer { offset += 1 }
        return data[offset]
    }

    mutating func u16() throws -> UInt16 { UInt16(try u8()) << 8 | UInt16(try u8()) }
    mutating func i16() throws -> Int16 { Int16(bitPattern: try u16()) }
    mutating func u64() throws -> UInt64 {
        var v: UInt64 = 0
        for _ in 0..<8 { v = v << 8 | UInt64(try u8()) }
        return v
    }

    mutating func bytes(_ n: Int) throws -> Data {
        guard n >= 0, offset + n <= data.endIndex else { throw Truncated() }
        defer { offset += n }
        return data.subdata(in: offset..<offset + n)
    }

    mutating func string() throws -> String {
        String(decoding: try bytes(Int(try u16())), as: UTF8.self)
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }

    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var out = Data(capacity: hex.count / 2)
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            out.append(b)
            i = j
        }
        self = out
    }
}
