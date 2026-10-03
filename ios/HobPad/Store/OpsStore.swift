import Foundation
import Observation
import OSLog
import StoreKit

extension Logger {
    static let store = Logger(subsystem: "uk.co.promptbuilt.hobpad", category: "store")
}

/// StoreKit 2 consumable credit packs. The crediting contract: a transaction is finished
/// ONLY after the Worker has answered 2xx (idempotent by transaction id, so a lost
/// response or a replay can never double-credit); anything unfinished is re-submitted by
/// the launch drain and the `Transaction.updates` listener, so no purchase is ever lost.
@Observable @MainActor
final class OpsStore {

    /// Sale copy for each pack — the WORKER's PRODUCT_CREDITS map in backend/ops/wrangler.toml
    /// is the authority for what a pack actually credits; keep the two identical.
    static let packCredits: [String: Int] = [
        "uk.co.promptbuilt.hobpad.ops50": 50,
        "uk.co.promptbuilt.hobpad.ops100": 100,
        "uk.co.promptbuilt.hobpad.ops500": 500,
    ]

    private(set) var products: [Product] = []
    private(set) var productsLoaded = false
    private(set) var balance: Int?
    /// The account has bought a pack at some point; with balance > 0 this lifts the
    /// daily auto-naming cap (RecipesModel mirrors the Worker's exemption rule).
    private(set) var purchased = false
    private(set) var purchasing = false
    var message: String?

    private var started = false
    private var lastStarterAttempt: Date?

    /// Idempotent; kick off from the root view's `.task`.
    func start() async {
        guard !started else { return }
        started = true
        Task { await self.loadProducts() }
        Task { await self.refreshBalance() }
        // The updates listener must start immediately — chaining it after the
        // unfinished drain left a window at launch where an Ask to Buy approval
        // or App Store purchase would be missed until the next launch.
        Task {
            for await result in Transaction.updates { await self.submit(result) }
        }
        Task {
            for await result in Transaction.unfinished { await self.submit(result) }
        }
    }

    func loadProducts() async {
        let requested = Self.packCredits.keys.sorted()
        do {
            let loaded = try await Product.products(for: requested)
            products = loaded.sorted { $0.price < $1.price }
            message = nil
            // An empty or partial SUCCESS means the App Store answered with no
            // matching metadata — an ASC/propagation issue, not a network one.
            // Keep the evidence in the log (requested vs returned).
            Logger.store.info("products: requested \(requested.count) [\(requested.joined(separator: ","))], got \(loaded.count) [\(loaded.map(\.id).joined(separator: ","))]")
        } catch {
            message = "Could not load the credit packs: \(error.localizedDescription)"
            Logger.store.error("products request failed: \(error.localizedDescription)")
        }
        productsLoaded = true
    }

    func refreshBalance() async {
        do {
            let reply = try await OpsWorkerAPI.balance(token: OpsAccount.token())
            balance = reply.balance
            purchased = reply.purchased ?? purchased
            if reply.starter == "pending" { await claimStarter() }
        } catch {
            // Offline is normal; keep the last known balance rather than alarming anyone.
        }
    }

    /// A new account's free credits wait for a device check (StarterCheck). Anything short
    /// of a clear answer leaves them pending on the Worker, so this simply tries again at
    /// the next refresh, at most once a minute.
    private func claimStarter() async {
        if let last = lastStarterAttempt, Date().timeIntervalSince(last) < 60 { return }
        lastStarterAttempt = Date()
        do {
            guard let check = try await StarterCheck.body() else { return }
            let reply = try await OpsWorkerAPI.claimStarter(token: OpsAccount.token(), check: check)
            balance = reply.balance
        } catch {
            Logger.store.info("starter check not settled: \(error.localizedDescription)")
        }
    }

    func purchase(_ product: Product) async {
        purchasing = true
        defer { purchasing = false }
        do {
            let result = try await product.purchase(options: [.appAccountToken(OpsAccount.token())])
            switch result {
            case .success(let verification):
                await submit(verification)
            case .pending:
                message = "Waiting for approval — the credits will arrive once the purchase is approved."
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            message = "Purchase failed: \(error.localizedDescription)"
        }
    }

    /// Send a signed transaction to the Worker; finish it only once the credit is banked.
    /// The Worker re-verifies everything, so even a locally unverified payload is submitted.
    private func submit(_ result: VerificationResult<StoreKit.Transaction>) async {
        let transaction = result.unsafePayloadValue
        guard transaction.productType == .consumable else {
            await transaction.finish()
            return
        }
        do {
            var newBalance: Int
            #if DEBUG
            if transaction.environment == .xcode {
                newBalance = try await OpsWorkerAPI.submitTestPurchase(
                    productId: transaction.productID,
                    transactionId: String(transaction.id),
                    token: OpsAccount.token(),
                )
            } else {
                newBalance = try await OpsWorkerAPI.submitPurchase(jws: result.jwsRepresentation)
            }
            #else
            newBalance = try await OpsWorkerAPI.submitPurchase(jws: result.jwsRepresentation)
            #endif
            await transaction.finish()
            balance = newBalance
            purchased = true
            message = nil
        } catch {
            // Deliberately NOT finished: Transaction.unfinished re-delivers on next launch,
            // and the idempotent Worker makes the retry safe.
            message = "Purchase made — crediting will retry: \(error.localizedDescription)"
        }
    }
}
