import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let coordinator = Coordinator()
    private var menu: MenuController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let menu = MenuController(coordinator: coordinator)
        self.menu = menu
        coordinator.onChange = { [weak menu] in menu?.refresh() }
        coordinator.approve = MenuController.askApproval
        coordinator.start()
        LoginItem.enableOnFirstLaunch()
        if !CGPreflightScreenCaptureAccess() {
            _ = CGRequestScreenCaptureAccess()
        }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
