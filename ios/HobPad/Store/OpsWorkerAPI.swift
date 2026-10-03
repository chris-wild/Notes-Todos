import Foundation

/// Commerce calls to the metering Worker (backend/ops). Only OCR itself rides the shared
/// Kotlin core; balance and purchase submission are plain Swift so StoreKit types never
/// cross the bridge. Debug builds talk to the staging Worker (which accepts the Xcode
/// StoreKit-configuration purchase shape); Release talks to production.
enum OpsWorkerAPI {
    static let baseURL: URL = {
        #if DEBUG
        URL(string: "https://hobpad-ops-staging.chris-f50.workers.dev")!
        #else
        URL(string: "https://hobpad-ops.chris-f50.workers.dev")!
        #endif
    }()

    struct WorkerError: LocalizedError {
        let status: Int
        let body: String
        var errorDescription: String? { "Credits service answered \(status): \(body)" }
    }

    struct BalanceReply: Decodable {
        let balance: Int
        /// Whether the account has ever bought a pack — with balance, this mirrors the
        /// Worker's naming-cap exemption (purchased accounts name freely while credits last).
        let purchased: Bool?
        /// "pending" until the device check (StarterCheck) settles the free starter
        /// credits, then "granted" or "denied". Absent from Workers older than the check.
        let starter: String?
    }
    private struct PurchaseReply: Decodable { let balance: Int }
    struct StarterReply: Decodable {
        let starter: String
        let balance: Int
    }

    /// Sent on every call: this client checks the device before the free starter credits,
    /// so accounts it creates wait for that check (backend/ops/src/starter.js). The shared
    /// Kotlin MeteredOcrClient sends the same header on conversion and naming calls.
    private static let starterHeader = "X-HobPad-Starter"

    static func balance(token: UUID) async throws -> BalanceReply {
        var request = URLRequest(url: baseURL.appending(path: "v1/balance"))
        request.setValue("Bearer \(token.uuidString.lowercased())", forHTTPHeaderField: "Authorization")
        return try await send(request, as: BalanceReply.self)
    }

    /// Ask for the free starter credits with a device check; the reply is authoritative.
    static func claimStarter(token: UUID, check: [String: Any]) async throws -> StarterReply {
        var request = URLRequest(url: baseURL.appending(path: "v1/starter"))
        request.httpMethod = "POST"
        request.setValue("Bearer \(token.uuidString.lowercased())", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: check)
        return try await send(request, as: StarterReply.self)
    }

    /// Submit a signed transaction; the Worker verifies Apple's signature and credits the
    /// pack idempotently. The returned balance is authoritative.
    static func submitPurchase(jws: String) async throws -> Int {
        try await post("v1/purchase", json: ["jws": jws]).balance
    }

    #if DEBUG
    /// Xcode's local StoreKit environment signs with a certificate no server can verify, so
    /// Debug builds may submit the raw fields to the STAGING Worker instead (TEST_MODE).
    static func submitTestPurchase(productId: String, transactionId: String, token: UUID) async throws -> Int {
        try await post(
            "v1/purchase",
            json: ["test": [
                "productId": productId,
                "transactionId": transactionId,
                "appAccountToken": token.uuidString.lowercased(),
            ]],
        ).balance
    }
    #endif

    private static func post(_ path: String, json: [String: Any]) async throws -> PurchaseReply {
        var request = URLRequest(url: baseURL.appending(path: path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: json)
        return try await send(request, as: PurchaseReply.self)
    }

    private static func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        var request = request
        request.setValue("1", forHTTPHeaderField: starterHeader)
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(status) else {
            throw WorkerError(status: status, body: String(data: data, encoding: .utf8) ?? "")
        }
        return try JSONDecoder().decode(type, from: data)
    }
}
