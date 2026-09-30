import Foundation
import Observation
import HobPadCore

enum AppTab: Hashable {
    case recipes, todos, notes
}

/// Owns the shared Kotlin object graph and the StoreKit credit store. One instance for the
/// app's lifetime. Also carries the little cross-tab routing state: creating an ingredient
/// list jumps to the Todos tab with the new category selected.
@Observable @MainActor
final class AppServices {
    let core: CoreServices
    let ops: OpsStore
    var selectedTab: AppTab = .recipes
    /// Set when an ingredient list was just created; TodosTab selects it and clears this.
    var pendingTodoCategory: String?

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
