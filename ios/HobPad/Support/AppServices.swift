import Foundation
import Observation
import HobPadCore

/// Owns the shared Kotlin object graph. One instance for the app's lifetime.
@Observable @MainActor
final class AppServices {
    let core: CoreServices

    init() {
        core = CoreServices(secretStore: KeychainSecretStore())
    }
}
