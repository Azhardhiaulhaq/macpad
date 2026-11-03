import AppKit

/// Wi-Fi pairing prompt; the user compares the code with the tablet.
/// Non-modal on purpose: a modal loop would stop the main queue, stalling the core queue's
/// synchronous display work and preventing the prompt from being withdrawn.
final class PairingPrompt: NSObject {
    private static var open: Set<PairingPrompt> = []

    private let alert = NSAlert()
    private var reply: ((Bool) -> Void)?

    /// Shows the prompt. Main thread only. The returned closure withdraws it without replying.
    static func show(deviceName: String, code: String, reply: @escaping (Bool) -> Void) -> () -> Void {
        let prompt = PairingPrompt(deviceName: deviceName, code: code, reply: reply)
        open.insert(prompt)
        NSApp.activate(ignoringOtherApps: true)
        prompt.alert.window.level = .floating
        prompt.alert.window.center()
        prompt.alert.window.makeKeyAndOrderFront(nil)
        return { [weak prompt] in prompt?.finish(nil) }
    }

    private init(deviceName: String, code: String, reply: @escaping (Bool) -> Void) {
        self.reply = reply
        super.init()
        alert.messageText = "Allow “\(deviceName)” to use this Mac as a second screen?"
        alert.informativeText = "Only allow it if the tablet shows the code \(code.prefix(3)) \(code.suffix(3))."
        let allow = alert.addButton(withTitle: "Allow")
        let deny = alert.addButton(withTitle: "Don’t Allow")
        allow.target = self
        allow.action = #selector(allowClicked)
        deny.target = self
        deny.action = #selector(denyClicked)
        alert.layout()
    }

    @objc private func allowClicked() { finish(true) }
    @objc private func denyClicked() { finish(false) }

    private func finish(_ allowed: Bool?) {
        alert.window.orderOut(nil)
        if let allowed, let reply { reply(allowed) }
        reply = nil
        Self.open.remove(self)
    }
}
