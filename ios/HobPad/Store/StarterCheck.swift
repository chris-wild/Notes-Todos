import DeviceCheck
import Foundation

/// The device check behind the free starter credits (backend/ops/src/starter.js): an Apple
/// DeviceCheck token, with which the Worker reads and sets this device's "had its free
/// credits" bit. The bit is kept by Apple, survives reinstalls and resets, and says nothing
/// about the person.
enum StarterCheck {
    /// The request body for POST /v1/starter, or nil when this device cannot be checked (a
    /// Release build on hardware without DeviceCheck simply goes without the free credits).
    static func body() async throws -> [String: Any]? {
        guard DCDevice.current.isSupported else {
            #if DEBUG
            // The simulator has no DeviceCheck; the staging Worker accepts a test check.
            return ["platform": "test"]
            #else
            return nil
            #endif
        }
        let token = try await DCDevice.current.generateToken()
        // Development-signed builds get development tokens, checked by the staging Worker
        // against Apple's development server; TestFlight and App Store builds are production.
        #if DEBUG
        let environment = "development"
        #else
        let environment = "production"
        #endif
        return ["platform": "ios", "deviceToken": token.base64EncodedString(), "environment": environment]
    }
}
