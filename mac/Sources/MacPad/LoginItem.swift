import Foundation
import os
import ServiceManagement

enum LoginItem {
    private static let log = Logger(subsystem: "com.azhardhiaulhaq.macpad", category: "login")

    static var isEnabled: Bool { SMAppService.mainApp.status == .enabled }

    static func setEnabled(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            log.error("login item change failed: \(String(describing: error))")
        }
    }

    /// Plug-and-go over USB needs the app running, so it opens at login unless the user turns that off.
    static func enableOnFirstLaunch() {
        let key = "loginItemConfigured"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        setEnabled(true)
    }
}
