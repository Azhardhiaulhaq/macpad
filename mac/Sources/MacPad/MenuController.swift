import AppKit

/// The menu bar item. Rebuilt every time it opens, so it always shows current state.
final class MenuController: NSObject, NSMenuDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let coordinator: Coordinator

    init(coordinator: Coordinator) {
        self.coordinator = coordinator
        super.init()
        if let button = statusItem.button {
            let image = NSImage(named: "MenuIcon")
            image?.isTemplate = true
            button.image = image
            button.setAccessibilityTitle("MacPad")
            button.toolTip = "MacPad"
        }
        let menu = NSMenu()
        menu.delegate = self
        menu.autoenablesItems = false
        statusItem.menu = menu
    }

    func refresh() {
        if let menu = statusItem.menu { menuNeedsUpdate(menu) }
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let status = coordinator.status

        switch status.connection {
        case .idle:
            menu.addItem(label("No tablet connected"))
            menu.addItem(label("Plug in the tablet, or open MacPad on it over Wi-Fi."))
        case .connected(let name, let transport):
            menu.addItem(label("Connected to \(name) over \(transport.rawValue)"))
        case .lingering(let name):
            menu.addItem(label("\(name) disconnected. Its display stays for \(Coordinator.graceSeconds) seconds."))
        }
        for problem in [status.serverProblem, status.usbProblem].compactMap({ $0 }) {
            menu.addItem(label(problem))
        }
        let addresses = Coordinator.lanAddresses
        if !addresses.isEmpty {
            let noun = addresses.count == 1 ? "address" : "addresses"
            menu.addItem(label("Wi-Fi \(noun): \(addresses.joined(separator: ", "))"))
        }

        menu.addItem(.separator())
        let sizeItem = NSMenuItem(title: "Desktop Size", action: nil, keyEquivalent: "")
        let sizeMenu = NSMenu()
        let current = Preferences.desktopSize
        for size in DesktopSize.allCases {
            var title = size.title
            if let panel = status.panel {
                let s = size.logicalSize(panelWidth: panel.width, panelHeight: panel.height)
                title += " (\(s.width) × \(s.height))"
            }
            let item = NSMenuItem(title: title, action: #selector(chooseSize(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = size.rawValue
            item.state = size == current ? .on : .off
            sizeMenu.addItem(item)
        }
        sizeItem.submenu = sizeMenu
        menu.addItem(sizeItem)

        let tabletsItem = NSMenuItem(title: "Paired Tablets", action: nil, keyEquivalent: "")
        let tabletsMenu = NSMenu()
        let tablets = PairingStore.shared.tablets()
        if tablets.isEmpty {
            tabletsMenu.addItem(label("No tablets paired over Wi-Fi"))
        }
        for tablet in tablets {
            let item = NSMenuItem(title: "Forget \(tablet.name)", action: #selector(forget(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = tablet.idHex
            tabletsMenu.addItem(item)
        }
        tabletsItem.submenu = tabletsMenu
        menu.addItem(tabletsItem)

        let needsCapture = !CGPreflightScreenCaptureAccess()
        let needsInput = !AXIsProcessTrusted()
        if needsCapture || needsInput {
            menu.addItem(.separator())
            if needsCapture {
                menu.addItem(action("Allow Screen Recording…", #selector(openScreenRecordingSettings)))
            }
            if needsInput {
                menu.addItem(action("Allow Touch and Pen Control…", #selector(openAccessibilitySettings)))
            }
        }

        menu.addItem(.separator())
        let login = action("Open at Login", #selector(toggleLogin))
        login.state = LoginItem.isEnabled ? .on : .off
        menu.addItem(login)
        let quit = NSMenuItem(title: "Quit MacPad", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.addItem(quit)
    }

    private func label(_ text: String) -> NSMenuItem {
        let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func action(_ title: String, _ selector: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: selector, keyEquivalent: "")
        item.target = self
        return item
    }

    @objc private func chooseSize(_ sender: NSMenuItem) {
        guard let raw = sender.representedObject as? String, let size = DesktopSize(rawValue: raw) else { return }
        coordinator.setDesktopSize(size)
    }

    @objc private func forget(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        PairingStore.shared.forget(idHex: id)
    }

    @objc private func toggleLogin() {
        LoginItem.setEnabled(!LoginItem.isEnabled)
    }

    @objc private func openScreenRecordingSettings() {
        _ = CGRequestScreenCaptureAccess()
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")!)
    }

    @objc private func openAccessibilitySettings() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }

    /// Wi-Fi pairing prompt. Runs modally; the user compares the code with the tablet.
    static func askApproval(deviceName: String, code: String, reply: @escaping (Bool) -> Void) {
        let grouped = "\(code.prefix(3)) \(code.suffix(3))"
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Allow “\(deviceName)” to use this Mac as a second screen?"
        alert.informativeText = "Only allow it if the tablet shows the code \(grouped)."
        alert.addButton(withTitle: "Allow")
        alert.addButton(withTitle: "Don’t Allow")
        reply(alert.runModal() == .alertFirstButtonReturn)
    }
}
