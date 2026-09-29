import Foundation
import Security

/// The anonymous ops-account identity: one UUID, minted on first use, kept as an
/// iCloud-synchronizable Keychain item. It is both the StoreKit `appAccountToken` (baked
/// into every signed transaction) and the bearer the metering Worker keys balances by, so
/// it must survive reinstall and follow the user's iCloud Keychain to a new device.
///
/// Deliberately NOT `KeychainSecretStore`: that store is ThisDeviceOnly and unsynchronized,
/// which is right for an API key and would orphan a paid balance here.
enum OpsAccount {
    private static let service = "uk.co.promptbuilt.hobpad.ops"
    private static let accountName = "ops_account_token"

    static func token() -> UUID {
        if let existing = read() { return existing }
        let fresh = UUID()
        store(fresh)
        return fresh
    }

    private static func read() -> UUID? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountName,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let text = String(data: data, encoding: .utf8),
              let uuid = UUID(uuidString: text)
        else { return nil }
        return uuid
    }

    private static func store(_ uuid: UUID) {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountName,
            kSecAttrSynchronizable as String: kCFBooleanTrue!,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: Data(uuid.uuidString.utf8),
        ]
        SecItemAdd(attributes as CFDictionary, nil)
    }
}
