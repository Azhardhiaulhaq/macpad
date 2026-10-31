import Foundation
import Security

/// Trusted tablets. Pairing keys live in the login keychain; names in UserDefaults for the menu.
final class PairingStore {
    struct Tablet {
        let idHex: String
        let name: String
    }

    static let shared = PairingStore()

    private let service = "com.azhardhiaulhaq.macpad.pairing"
    private let namesKey = "pairedTabletNames"
    private let lock = NSLock()

    /// Stable id advertised over Bonjour and used in the handshake transcript.
    let serverId: Data = {
        let defaults = UserDefaults.standard
        if let d = defaults.data(forKey: "serverId"), d.count == 16 { return d }
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &bytes)
        let d = Data(bytes)
        defaults.set(d, forKey: "serverId")
        return d
    }()

    func key(for clientId: Data) -> Data? {
        lock.lock(); defer { lock.unlock() }
        var query = baseQuery(account: clientId.hex)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    func save(key: Data, clientId: Data, name: String) {
        lock.lock(); defer { lock.unlock() }
        let account = clientId.hex
        SecItemDelete(baseQuery(account: account) as CFDictionary)
        var item = baseQuery(account: account)
        item[kSecValueData as String] = key
        item[kSecAttrLabel as String] = "MacPad pairing: \(name)"
        SecItemAdd(item as CFDictionary, nil)
        var names = storedNames()
        names[account] = name
        UserDefaults.standard.set(names, forKey: namesKey)
    }

    func forget(idHex: String) {
        lock.lock(); defer { lock.unlock() }
        SecItemDelete(baseQuery(account: idHex) as CFDictionary)
        var names = storedNames()
        names.removeValue(forKey: idHex)
        UserDefaults.standard.set(names, forKey: namesKey)
    }

    func tablets() -> [Tablet] {
        lock.lock(); defer { lock.unlock() }
        return storedNames().map { Tablet(idHex: $0.key, name: $0.value) }.sorted { $0.name < $1.name }
    }

    private func storedNames() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: namesKey) as? [String: String] ?? [:]
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
