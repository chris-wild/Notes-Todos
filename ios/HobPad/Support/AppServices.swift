import Foundation
import Observation
import HobPadCore

/// Owns the shared Kotlin object graph and the StoreKit credit store. One instance for the
/// app's lifetime.
@Observable @MainActor
final class AppServices {
    let core: CoreServices
    let ops: OpsStore

    init() {
        #if DEBUG
        let allowByoKey = true
        #else
        let allowByoKey = false
        #endif
        core = CoreServices(
            secretStore: KeychainSecretStore(),
            metered: MeteredConfig(
                workerBaseUrl: OpsWorkerAPI.baseURL.absoluteString,
                opsToken: OpsAccount.token().uuidString.lowercased(),
                allowByoKey: allowByoKey,
            ),
        )
        ops = OpsStore()
    }
}
