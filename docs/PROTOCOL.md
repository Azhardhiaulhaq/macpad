# MacPad wire protocol, version 1

One TCP connection carries everything: video from the Mac to the tablet, input and control from the tablet to the Mac. All integers are big-endian. `string` is a `u16` byte length followed by UTF-8 bytes.

## Transports

| Transport | Mac listens on | Tablet connects to | Encryption |
|---|---|---|---|
| USB | `127.0.0.1:47800` (loopback only) | `127.0.0.1:47800`, forwarded by `adb reverse tcp:47800 tcp:47800` | none |
| Wi-Fi | port `47801`, all interfaces, Bonjour `_macpad._tcp` | address from Android NSD, a remembered address, or one the user typed | AES-256-GCM after the handshake |

Bonjour TXT record: `id=<32 lowercase hex chars, server id>`, `v=1`. The service instance name is the Mac's computer name.

Some routers drop multicast between wireless clients, so Bonjour never reaches the tablet. The tablet therefore also remembers the addresses from `SERVER_INFO` (learned over any connection, including USB) and tries them directly on port 47801, and lets the user type an address. For an address whose server id the tablet does not know yet, it sends `hasKey=0`.

The Mac serves one tablet at a time. A second connection receives `NOTICE` code 2 and is closed.

## Framing

Plain frame (USB, and the Wi-Fi handshake):

```
u32 length          // bytes that follow: 1 + payload length, at most 16 MiB
u8  type
...  payload
```

Encrypted frame (Wi-Fi after the handshake):

```
u32 length          // bytes that follow: ciphertext length + 16
...  ciphertext     // AES-256-GCM of (u8 type || payload), no AAD
16B  tag
```

Nonce (12 bytes): `u32 direction || u64 counter`. Direction is `1` for tablet→Mac and `2` for Mac→tablet. Each side keeps one counter per direction, starting at 0 and incremented after every frame. A decryption failure closes the connection.

## Wi-Fi handshake

Keys are NIST P-256. Public keys travel as 65-byte uncompressed X9.63 points (`0x04 || X || Y`). `shared` is the 32-byte ECDH X coordinate. HKDF is HKDF-SHA256 (RFC 5869). `transcript = clientId || clientPub || serverId || serverPub` (162 bytes).

1. Tablet sends `0x10 WIFI_HELLO`: `u16 version=1`, `16B clientId`, `65B clientPub` (fresh per connection), `u8 hasKey` (1 if the tablet stores a pairing key for the server id from the TXT record), `string deviceName`.
2. Mac replies `0x90 WIFI_HELLO_REPLY`: `16B serverId`, `65B serverPub` (fresh per connection), `u8 mode`.
   - `mode=0` (paired): the Mac stores a pairing key for `clientId` and `hasKey=1`.
   - `mode=1` (pairing required): any other case. The tablet deletes any stored key for this server.
3. If `mode=1`:
   - Both compute `sas = u32(HKDF(ikm=shared, salt="macpad-sas-v1", info=transcript, L=4)) mod 1000000`, shown as 6 zero-padded digits.
   - The Mac asks the user to allow the tablet while showing the code. The tablet shows the same code.
   - Mac sends `0x91 PAIR_ACCEPTED` (empty) or `0x92 PAIR_REJECTED` (empty, then closes).
   - On accept both store `pairingKey = HKDF(ikm=shared, salt="macpad-pair-v1", info=transcript, L=32)`. The Mac stores it under `clientId` with the device name, the tablet under `serverId`.
4. Session keys, both modes:
   - `kC2S = HKDF(ikm=shared, salt=pairingKey, info="c2s" || transcript, L=32)`
   - `kS2C = HKDF(ikm=shared, salt=pairingKey, info="s2c" || transcript, L=32)`
5. Every following frame is encrypted. The tablet's first encrypted frame is `HELLO`. If the Mac cannot decrypt it, the keys disagree: the Mac closes, and the tablet deletes its key so the next attempt pairs again.

## Tablet → Mac

| Type | Name | Payload |
|---|---|---|
| `0x01` | HELLO | `u16 version=1`, `u16 widthPx`, `u16 heightPx` (panel pixels in the current orientation), `u16 densityDpi`, `16B clientId`, `string deviceName` |
| `0x02` | RESIZE | `u16 widthPx`, `u16 heightPx`. Sent after rotation. |
| `0x03` | POINTER | `u8 action`, `u8 tool`, `u8 buttons`, `u16 x`, `u16 y`, `u16 pressure`, `i16 tiltX`, `i16 tiltY` |
| `0x04` | SCROLL | `u8 phase`, `u16 x`, `u16 y`, `i16 dx`, `i16 dy` |
| `0x05` | KEYFRAME_REQUEST | empty |
| `0x06` | PING | `u64 clientTimeNanos` |

POINTER fields:
- `action`: 0 hover enter, 1 hover move, 2 hover exit, 3 down, 4 move (while down), 5 up.
- `tool`: 0 finger, 1 pen.
- `buttons`: bit 0 set means secondary (right) button. Used for the pen side button and the two-finger tap.
- `x`, `y`: position in the current video frame, scaled to 0..65535.
- `pressure`: 0..65535. Fingers send 65535 while down.
- `tiltX`, `tiltY`: −32767..32767 for −1..1, the pen's lean projected on each axis. 0 when unknown.

SCROLL fields:
- `phase`: 1 began, 2 changed, 3 ended.
- `x`, `y`: gesture centroid, scaled as in POINTER.
- `dx`, `dy`: finger movement since the previous SCROLL, in panel pixels. Positive `dy` means the fingers moved down. The Mac scrolls so the content follows the fingers.

## Mac → Tablet

| Type | Name | Payload |
|---|---|---|
| `0x80` | SERVER_INFO | `string macName`, `16B serverId`, `u8 count`, then `count` × `string address` (the Mac's IPv4 addresses on its active non-loopback interfaces, dotted form) |
| `0x81` | VIDEO_CONFIG | `u16 width`, `u16 height`, then H.264 SPS and PPS in Annex B form (`00 00 00 01` start codes) |
| `0x82` | VIDEO_FRAME | `u64 ptsMicros`, `u8 flags` (bit 0 keyframe), then H.264 access unit in Annex B form |
| `0x83` | PONG | `u64 clientTimeNanos`, echoed |
| `0x84` | NOTICE | `u8 code`, `string message` |

NOTICE codes: 1 screen recording permission missing on the Mac, 2 another tablet is connected, 3 the virtual display could not be created.

`VIDEO_CONFIG` always precedes the first frame and is sent again whenever the size changes. The first frame after it is a keyframe. The tablet reconfigures its decoder on every `VIDEO_CONFIG`.

## Sequence

1. Connect (Wi-Fi: handshake first).
2. Tablet sends `HELLO`.
3. Mac sends `SERVER_INFO`, creates or reuses the virtual display, sends `VIDEO_CONFIG`, then frames.
4. The Mac only sends frames when the screen changes. A still screen produces no traffic after a short quality refresh.
5. On disconnect the Mac keeps the display for 10 seconds. A `HELLO` with the same `clientId` within that window reuses it.
