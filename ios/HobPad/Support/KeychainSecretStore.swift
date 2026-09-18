import Foundation
import Security
import HobPadCore

/// Keychain-backed implementation of the shared core's SecretStore.
/// Holds the Anthropic API key as a generic password, this device only.
final class KeychainSecretStore: NSObject, SecretStore {

    private let service = "uk.co.promptbuilt.hobpad.secrets"

    private func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    func get(key: String) -> String? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func set(key: String, value: String) {
        let data = Data(value.utf8)
        var query = baseQuery(key)
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        if status == errSecSuccess {
            SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        } else {
            query[kSecValueData as String] = data
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(query as CFDictionary, nil)
        }
    }

    func delete(key: String) {
        SecItemDelete(baseQuery(key) as CFDictionary)
    }

    func contains(key: String) -> Bool {
        get(key: key) != nil
    }
}
