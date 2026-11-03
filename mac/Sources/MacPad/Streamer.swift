import CoreMedia
import Foundation
import os
import ScreenCaptureKit
import VideoToolbox

/// Captures one display with ScreenCaptureKit and encodes it with the hardware H.264 encoder
/// in low-latency mode. Emits Annex B parameter sets and access units.
final class Streamer: NSObject, SCStreamOutput, SCStreamDelegate {
    struct Output {
        /// SPS + PPS in Annex B form, sent before the first frame and whenever they change.
        var config: (_ width: Int, _ height: Int, _ parameterSets: Data) -> Void
        /// Payload for VIDEO_FRAME: u64 pts, u8 flags, Annex B access unit.
        var frame: (_ payload: Data) -> Void
        /// False while the network is behind; the frame is skipped before encoding.
        var canSend: () -> Bool
        var failed: (_ message: String) -> Void
    }

    private let displayID: CGDirectDisplayID
    let width: Int
    let height: Int
    private let bitRate: Int
    private let output: Output
    private let queue = DispatchQueue(label: "macpad.capture", qos: .userInteractive)
    private let log = Logger(subsystem: "com.azhardhiaulhaq.macpad", category: "stream")

    private var stream: SCStream?
    private var encoder: VTCompressionSession?
    private var refreshTimer: DispatchSourceTimer?
    private var stopped = false

    // Capture-queue state.
    private var lastBuffer: CVPixelBuffer?
    private var lastEncodeAt: CFTimeInterval = 0
    private var refreshesLeft = 0
    private var forceKeyframe = true
    private var lastParameterSets = Data()
    private var stats = Stats()

    private struct Stats {
        var frames = 0
        var bytes = 0
        var encodeMs = 0.0
        var skipped = 0
        var since = CACurrentMediaTime()
    }

    init(displayID: CGDirectDisplayID, width: Int, height: Int, bitRate: Int, output: Output) {
        self.displayID = displayID
        self.width = width
        self.height = height
        self.bitRate = bitRate
        self.output = output
    }

    func start() async {
        do {
            let scDisplay = try await findDisplay()
            let config = SCStreamConfiguration()
            config.width = width
            config.height = height
            config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            config.colorMatrix = CGDisplayStream.yCbCrMatrix_ITU_R_709_2
            config.colorSpaceName = CGColorSpace.sRGB
            config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
            config.queueDepth = 4
            config.showsCursor = true
            config.capturesAudio = false
            config.captureResolution = .best
            try queue.sync { try makeEncoder() }
            let filter = SCContentFilter(display: scDisplay, excludingWindows: [])
            let stream = SCStream(filter: filter, configuration: config, delegate: self)
            try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
            try await stream.startCapture()
            queue.sync {
                if stopped {
                    stream.stopCapture { _ in }
                } else {
                    self.stream = stream
                    startRefreshTimer()
                }
            }
            log.info("streaming display \(self.displayID) at \(self.width)x\(self.height)")
        } catch {
            log.error("start failed: \(String(describing: error), privacy: .public)")
            output.failed(error.localizedDescription)
        }
    }

    func stop() {
        queue.sync {
            stopped = true
            refreshTimer?.cancel()
            refreshTimer = nil
            stream?.stopCapture { _ in }
            stream = nil
            if let encoder {
                VTCompressionSessionCompleteFrames(encoder, untilPresentationTimeStamp: .invalid)
                VTCompressionSessionInvalidate(encoder)
            }
            encoder = nil
            lastBuffer = nil
        }
    }

    func requestKeyframe() {
        queue.async {
            self.forceKeyframe = true
            self.refreshesLeft = max(self.refreshesLeft, 1)
        }
    }

    /// A new virtual display takes a moment to show up in ScreenCaptureKit.
    private func findDisplay() async throws -> SCDisplay {
        for _ in 0..<30 {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            if let d = content.displays.first(where: { $0.displayID == displayID }) { return d }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw NSError(domain: "MacPad", code: 3, userInfo: [NSLocalizedDescriptionKey: "The tablet display did not appear."])
    }

    // MARK: Encoder

    private func makeEncoder() throws {
        var session: VTCompressionSession?
        let spec = [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary
        let status = VTCompressionSessionCreate(
            allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264,
            encoderSpecification: spec, imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &session)
        guard status == noErr, let session else {
            throw NSError(domain: "MacPad", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "H.264 encoder unavailable (\(status))."])
        }
        let props: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: true,
            kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_ConstrainedHigh_AutoLevel,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_AverageBitRate: bitRate,
            kVTCompressionPropertyKey_ExpectedFrameRate: 60,
            kVTCompressionPropertyKey_MaxKeyFrameInterval: 3600,
            kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration: 60,
            kVTCompressionPropertyKey_ColorPrimaries: kCVImageBufferColorPrimaries_ITU_R_709_2,
            kVTCompressionPropertyKey_TransferFunction: kCVImageBufferTransferFunction_ITU_R_709_2,
            kVTCompressionPropertyKey_YCbCrMatrix: kCVImageBufferYCbCrMatrix_ITU_R_709_2,
        ]
        for (key, value) in props {
            let s = VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
            if s != noErr { log.notice("encoder property \(key as String) rejected: \(s)") }
        }
        VTCompressionSessionPrepareToEncodeFrames(session)
        encoder = session
    }

    private func encode(_ buffer: CVPixelBuffer) {
        guard let encoder else { return }
        guard output.canSend() else {
            // Network is behind. Skip; the refresh timer re-sends the latest screen once it drains.
            stats.skipped += 1
            refreshesLeft = max(refreshesLeft, 1)
            return
        }
        let now = CACurrentMediaTime()
        lastEncodeAt = now
        let pts = CMTime(seconds: now, preferredTimescale: 1_000_000)
        var frameProps: CFDictionary?
        if forceKeyframe {
            frameProps = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
            forceKeyframe = false
        }
        VTCompressionSessionEncodeFrame(
            encoder, imageBuffer: buffer, presentationTimeStamp: pts, duration: .invalid,
            frameProperties: frameProps, infoFlagsOut: nil
        ) { [weak self] status, _, sample in
            guard let self, status == noErr, let sample else { return }
            self.handleEncoded(sample, submittedAt: now)
        }
    }

    private func handleEncoded(_ sample: CMSampleBuffer, submittedAt: CFTimeInterval) {
        guard let format = CMSampleBufferGetFormatDescription(sample),
              let block = CMSampleBufferGetDataBuffer(sample) else { return }

        var isKeyframe = true
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
           let first = attachments.first, first[kCMSampleAttachmentKey_NotSync] as? Bool == true {
            isKeyframe = false
        }

        var nalLengthSize: Int32 = 4
        if isKeyframe {
            var sets = Data()
            var count = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalLengthSize)
            for i in 0..<count {
                var ptr: UnsafePointer<UInt8>?
                var size = 0
                CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    format, parameterSetIndex: i, parameterSetPointerOut: &ptr, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                if let ptr {
                    sets.append(contentsOf: [0, 0, 0, 1])
                    sets.append(ptr, count: size)
                }
            }
            if sets != lastParameterSets {
                lastParameterSets = sets
                output.config(width, height, sets)
            }
        }

        // AVCC (length-prefixed NAL units) to Annex B (start codes), in place.
        let length = CMBlockBufferGetDataLength(block)
        let headerSize = 9
        var payload = Data(count: headerSize + length)
        let ptsMicros = UInt64(max(0, CMSampleBufferGetPresentationTimeStamp(sample).seconds * 1_000_000))
        payload.withUnsafeMutableBytes { raw in
            let base = raw.baseAddress!.assumingMemoryBound(to: UInt8.self)
            var pts = ptsMicros.bigEndian
            withUnsafeBytes(of: &pts) { p in for i in 0..<8 { base[i] = p[i] } }
            base[8] = isKeyframe ? 1 : 0
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: base + headerSize)
            let lengthSize = Int(nalLengthSize)
            var offset = headerSize
            while offset + lengthSize <= headerSize + length {
                var nalLength = 0
                for i in 0..<lengthSize { nalLength = nalLength << 8 | Int(base[offset + i]) }
                for i in 0..<lengthSize { base[offset + i] = i == lengthSize - 1 ? 1 : 0 }
                offset += lengthSize + nalLength
            }
        }
        output.frame(payload)

        queue.async { [self] in
            stats.frames += 1
            stats.bytes += payload.count
            stats.encodeMs += (CACurrentMediaTime() - submittedAt) * 1000
            let elapsed = CACurrentMediaTime() - stats.since
            if elapsed >= 5 {
                let fps = Double(stats.frames) / elapsed
                let mbps = Double(stats.bytes) * 8 / elapsed / 1_000_000
                let enc = stats.frames > 0 ? stats.encodeMs / Double(stats.frames) : 0
                log.info("stats fps=\(fps, format: .fixed(precision: 1)) mbps=\(mbps, format: .fixed(precision: 2)) encode=\(enc, format: .fixed(precision: 1))ms skipped=\(self.stats.skipped)")
                stats = Stats()
            }
        }
    }

    // MARK: Capture

    func stream(_ stream: SCStream, didOutputSampleBuffer sample: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, !stopped,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let rawStatus = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: rawStatus) == .complete,
              let buffer = CMSampleBufferGetImageBuffer(sample) else { return }
        lastBuffer = buffer
        // After motion stops, the low-latency encoder's last frame can be soft. Re-encode the
        // final image a few times so text sharpens, then go quiet.
        refreshesLeft = 3
        encode(buffer)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log.error("capture stopped: \(String(describing: error), privacy: .public)")
        output.failed(error.localizedDescription)
    }

    private func startRefreshTimer() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(50), repeating: .milliseconds(50))
        timer.setEventHandler { [weak self] in
            guard let self, !self.stopped, self.refreshesLeft > 0, let buffer = self.lastBuffer,
                  CACurrentMediaTime() - self.lastEncodeAt >= 0.1 else { return }
            self.refreshesLeft -= 1
            self.encode(buffer)
        }
        timer.resume()
        refreshTimer = timer
    }
}
