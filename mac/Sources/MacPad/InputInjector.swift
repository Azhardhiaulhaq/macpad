import AppKit
import CoreGraphics

/// Turns tablet POINTER and SCROLL messages into macOS events. Pen events carry tablet
/// pressure and tilt so pressure-aware apps (Photoshop, Krita, Affinity) can use them.
/// Requires the Accessibility permission. Core queue only.
final class InputInjector {
    enum Action: UInt8 {
        case hoverEnter = 0, hoverMove, hoverExit, down, move, up
    }

    enum Tool: UInt8 {
        case finger = 0, pen = 1
    }

    private let source = CGEventSource(stateID: .hidSystemState)
    private let tabletDeviceID: Int64 = 0x4D50

    private var penInProximity = false
    private var buttonDown: CGMouseButton?
    private var downTool: Tool = .finger
    private var clickCount: Int64 = 1
    private var lastUpTime: TimeInterval = 0
    private var lastUpPoint = CGPoint.zero
    private var scrollRemainder = CGPoint.zero

    func pointer(action: Action, tool: Tool, secondary: Bool, at point: CGPoint, pressure: Double, tiltX: Double, tiltY: Double) {
        if tool == .pen {
            switch action {
            case .hoverEnter, .hoverMove, .down:
                if !penInProximity { postProximity(entering: true) }
            default: break
            }
        }

        switch action {
        case .hoverEnter, .hoverMove:
            post(.mouseMoved, at: point, button: .left, tool: tool, pressure: 0, tiltX: tiltX, tiltY: tiltY)
        case .hoverExit:
            if tool == .pen, penInProximity { postProximity(entering: false) }
        case .down:
            if buttonDown != nil { up(at: point, tool: tool, tiltX: tiltX, tiltY: tiltY) }
            let button: CGMouseButton = secondary ? .right : .left
            let now = ProcessInfo.processInfo.systemUptime
            let nearLast = hypot(point.x - lastUpPoint.x, point.y - lastUpPoint.y) <= 6
            clickCount = (now - lastUpTime <= NSEvent.doubleClickInterval && nearLast) ? min(clickCount + 1, 3) : 1
            buttonDown = button
            downTool = tool
            post(.mouseMoved, at: point, button: .left, tool: tool, pressure: 0, tiltX: tiltX, tiltY: tiltY)
            post(button == .left ? .leftMouseDown : .rightMouseDown, at: point, button: button, tool: tool,
                 pressure: pressure, tiltX: tiltX, tiltY: tiltY)
        case .move:
            guard let button = buttonDown else { return }
            post(button == .left ? .leftMouseDragged : .rightMouseDragged, at: point, button: button, tool: tool,
                 pressure: pressure, tiltX: tiltX, tiltY: tiltY)
        case .up:
            up(at: point, tool: tool, tiltX: tiltX, tiltY: tiltY)
        }
    }

    private func up(at point: CGPoint, tool: Tool, tiltX: Double, tiltY: Double) {
        guard let button = buttonDown else { return }
        post(button == .left ? .leftMouseUp : .rightMouseUp, at: point, button: button, tool: tool,
             pressure: 0, tiltX: tiltX, tiltY: tiltY)
        buttonDown = nil
        lastUpTime = ProcessInfo.processInfo.systemUptime
        lastUpPoint = point
    }

    /// `delta` is in points, positive y meaning the fingers moved down. Content follows the fingers.
    func scroll(phase: UInt8, at point: CGPoint, delta: CGPoint) {
        if phase == 1 {
            scrollRemainder = .zero
            post(.mouseMoved, at: point, button: .left, tool: .finger, pressure: 0, tiltX: 0, tiltY: 0)
        }
        let total = CGPoint(x: delta.x + scrollRemainder.x, y: delta.y + scrollRemainder.y)
        let wx = total.x.rounded(.towardZero), wy = total.y.rounded(.towardZero)
        scrollRemainder = CGPoint(x: total.x - wx, y: total.y - wy)
        guard let event = CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
                                  wheel1: Int32(wy), wheel2: Int32(wx), wheel3: 0) else { return }
        event.location = point
        event.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        // CGScrollPhase: began 1, changed 2, ended 4.
        event.setIntegerValueField(.scrollWheelEventScrollPhase, value: phase == 1 ? 1 : phase == 3 ? 4 : 2)
        event.post(tap: .cghidEventTap)
    }

    /// Lifts anything still pressed, e.g. when the tablet disconnects mid-drag.
    func reset() {
        if buttonDown != nil {
            let location = CGEvent(source: nil)?.location ?? .zero
            up(at: location, tool: downTool, tiltX: 0, tiltY: 0)
        }
        if penInProximity { postProximity(entering: false) }
    }

    private func post(_ type: CGEventType, at point: CGPoint, button: CGMouseButton, tool: Tool,
                      pressure: Double, tiltX: Double, tiltY: Double) {
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: point, mouseButton: button) else { return }
        if type != .mouseMoved {
            event.setIntegerValueField(.mouseEventClickState, value: clickCount)
        }
        if tool == .pen {
            event.setIntegerValueField(.mouseEventSubtype, value: Int64(CGEventMouseSubtype.tabletPoint.rawValue))
            event.setDoubleValueField(.mouseEventPressure, value: pressure)
            event.setDoubleValueField(.tabletEventPointPressure, value: pressure)
            event.setDoubleValueField(.tabletEventTiltX, value: tiltX)
            event.setDoubleValueField(.tabletEventTiltY, value: tiltY)
            event.setIntegerValueField(.tabletEventDeviceID, value: tabletDeviceID)
            event.setIntegerValueField(.tabletEventPointButtons, value: pressure > 0 ? 1 : 0)
        } else if type != .mouseMoved {
            event.setDoubleValueField(.mouseEventPressure, value: pressure)
        }
        event.post(tap: .cghidEventTap)
    }

    private func postProximity(entering: Bool) {
        guard let event = CGEvent(source: source) else { return }
        event.type = .tabletProximity
        event.setIntegerValueField(.tabletProximityEventEnterProximity, value: entering ? 1 : 0)
        event.setIntegerValueField(.tabletProximityEventDeviceID, value: tabletDeviceID)
        event.setIntegerValueField(.tabletProximityEventSystemTabletID, value: tabletDeviceID)
        event.setIntegerValueField(.tabletProximityEventPointerID, value: 1)
        event.setIntegerValueField(.tabletProximityEventPointerType, value: 1) // pen
        event.setIntegerValueField(.tabletProximityEventVendorPointerType, value: 0x0802) // generic stylus
        // NX_TABLET_CAPABILITY_*: device id, absolute X, absolute Y, buttons, tilt X, tilt Y, pressure.
        event.setIntegerValueField(.tabletProximityEventCapabilityMask, value: 0x0001 | 0x0002 | 0x0004 | 0x0040 | 0x0080 | 0x0100 | 0x0400)
        event.post(tap: .cghidEventTap)
        penInProximity = entering
    }
}
