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
                // @Sendable is load-bearing: a plain closure formed in this MainActor init
                // inherits MainActor isolation, and the Kotlin OCR client invokes it on a
                // background coroutine — libdispatch then kills the app at the first
                // conversion ("Block was expected to execute on queue main-thread").
                unitsProvider: { @Sendable in UnitsPreference.resolved() },
            ),
        )
        ops = OpsStore()
    }
}

/// The unit system extraction converts to: the Settings choice, or the device region when
/// set to Automatic (the UK measures cooking in metric). Deliberately NOT on AppServices:
/// the Kotlin OCR client calls this from a background coroutine, and a MainActor-isolated
/// function invoked off the main thread traps under Swift 6 (crashed the first convert).
enum UnitsPreference {
    static func resolved() -> String {
        switch UserDefaults.standard.string(forKey: "preferredUnits") {
        case "metric": return "metric"
        case "us": return "us"
        default: return Locale.current.measurementSystem == .us ? "us" : "metric"
        }
    }
}
