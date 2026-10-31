import CGVirtualDisplayPrivate
import CoreGraphics
import Foundation

/// A HiDPI virtual display backed by the private CGVirtualDisplay API.
/// Main thread only. The display disappears when this object is released.
final class VirtualDisplay {
    private let display: CGVirtualDisplay
    private(set) var logicalSize: (width: Int, height: Int)

    var displayID: CGDirectDisplayID { display.displayID }

    /// - Parameters:
    ///   - serial: stable per tablet so macOS remembers the arrangement in System Settings.
    ///   - maxLogicalSide: largest logical side ever requested, so either orientation fits.
    init?(name: String, serial: UInt32, logical: (width: Int, height: Int), maxLogicalSide: Int, sizeInMillimeters: CGSize) {
        dispatchPrecondition(condition: .onQueue(.main))
        let descriptor = CGVirtualDisplayDescriptor()
        descriptor.setDispatchQueue(DispatchQueue.main)
        descriptor.name = name
        descriptor.maxPixelsWide = UInt32(maxLogicalSide * 2)
        descriptor.maxPixelsHigh = UInt32(maxLogicalSide * 2)
        descriptor.sizeInMillimeters = sizeInMillimeters
        descriptor.vendorID = 0x4D50
        descriptor.productID = 0x0001
        descriptor.serialNum = serial
        descriptor.terminationHandler = { _, _ in }
        guard let display = CGVirtualDisplay(descriptor: descriptor) else { return nil }
        self.display = display
        logicalSize = logical
        guard apply(logical) else { return nil }
    }

    /// Switches the display to a new logical size. Re-applying a single mode is the reliable way:
    /// listing several modes and switching with CGConfigureDisplayWithDisplayMode fails for
    /// orientation changes.
    @discardableResult
    func setLogicalSize(_ size: (width: Int, height: Int)) -> Bool {
        dispatchPrecondition(condition: .onQueue(.main))
        guard size != logicalSize else { return true }
        guard apply(size) else { return false }
        logicalSize = size
        return true
    }

    private func apply(_ size: (width: Int, height: Int)) -> Bool {
        let settings = CGVirtualDisplaySettings()
        settings.hiDPI = 1
        settings.modes = [CGVirtualDisplayMode(width: UInt32(size.width), height: UInt32(size.height), refreshRate: 60)]
        return display.apply(settings)
    }
}
