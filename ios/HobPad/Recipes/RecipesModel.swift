import CoreGraphics
import Foundation
import Observation
import HobPadCore

@Observable @MainActor
final class RecipesModel {
    private let core: CoreServices
    let ops: OpsStore

    private(set) var recipes: [RecipeEntity] = []
    private(set) var attachments: [Int64: [RecipeAttachmentEntity]] = [:]
    var query = ""
    private(set) var hasKey = false
    private(set) var working: String?
    var message: String?
    /// Set when an extraction needs more credits than remain; the viewer opens the paywall.
    var paywallNeeded = false

    /// A captured recipe waiting for the user to type its name: automatic naming is
    /// capped per day (each title call costs real money server-side), and can also
    /// simply fail. Either way the photo is already saved; the dialog just names it.
    struct PendingName: Identifiable {
        enum Reason { case dailyLimit, namingFailed }
        let recipeId: Int64
        let reason: Reason
        var id: Int64 { recipeId }
    }

    var pendingName: PendingName?

    /// Client-side auto-naming budget: 30 per local day, mirroring the Worker's
    /// TITLE_DAILY_LIMIT (backend/ops/wrangler.toml) — the server cap is the abuse
    /// backstop, this counter is the UX that avoids ever hitting it mid-flow.
    private static let autoNameDailyLimit = 30

    private static func dayStamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }

    private func autoNameAllowed() -> Bool {
        let d = UserDefaults.standard
        if d.string(forKey: "autoNameDay") != Self.dayStamp() { return true }
        return d.integer(forKey: "autoNameCount") < Self.autoNameDailyLimit
    }

    private func countAutoName() {
        let d = UserDefaults.standard
        if d.string(forKey: "autoNameDay") != Self.dayStamp() {
            d.set(Self.dayStamp(), forKey: "autoNameDay")
            d.set(0, forKey: "autoNameCount")
        }
        d.set(d.integer(forKey: "autoNameCount") + 1, forKey: "autoNameCount")
    }

    /// "Photographed 1 Oct 2026", with " (2)", " (3)"… when that name already exists —
    /// the no-API name a capture keeps until the model or the user supplies a real one.
    private func photographedFallbackName() -> String {
        let f = DateFormatter()
        f.dateFormat = "d MMM yyyy"
        let base = "Photographed \(f.string(from: Date()))"
        let names = Set(recipes.map(\.name))
        if !names.contains(base) { return base }
        var n = 2
        while names.contains("\(base) (\(n))") { n += 1 }
        return "\(base) (\(n))"
    }

    /// The manual-name dialog's Save: a blank name keeps the date-stamped fallback.
    func rename(recipeId: Int64, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task { try? await core.recipesRepository.update(id: recipeId, name: trimmed, notes: "") }
    }

    init(core: CoreServices, ops: OpsStore) {
        self.core = core
        self.ops = ops
    }

    /// Debug builds with a stored personal key bypass metering entirely (BYO path);
    /// released builds always meter, so the key never gates anything there.
    var usesByoKey: Bool {
        #if DEBUG
        return hasKey
        #else
        return false
        #endif
    }

    /// How to gate a "Create ingredient list" tap.
    enum ConversionGate {
        case run                 // no credits involved: BYO key, or a cached re-run
        case confirm(cost: Int)  // will spend credits; ask first
        case paywall             // not enough credits for the preview cost
    }

    /// Decides the gate BEFORE showing any credit copy: a recipe whose ingredients were
    /// already extracted re-runs from the cache (IngredientTodosUseCase short-circuits, no
    /// API call, no charge), so it must never ask about credits.
    func conversionGate(for recipe: RecipeEntity) async -> ConversionGate {
        if usesByoKey { return .run }
        if let cached = try? await core.recipesRepository.getIngredients(recipeId: recipe.id),
           !cached.isEmpty {
            return .run
        }
        let cost = opsCost(for: recipe)
        return (ops.balance ?? 0) < cost ? .paywall : .confirm(cost: cost)
    }

    /// What converting [recipe] will cost, mirroring the server's billing: one op per PDF
    /// page across its attachments, or one op for a notes-only recipe. A preview only —
    /// the Worker's own page count is what actually gets charged.
    func opsCost(for recipe: RecipeEntity) -> Int {
        let mine = attachments(for: recipe)
        guard !mine.isEmpty else { return 1 }
        let pages = mine.reduce(0) { total, attachment in
            let url = URL(fileURLWithPath: pdfPath(attachment)) as CFURL
            return total + (CGPDFDocument(url).map { $0.numberOfPages } ?? 1)
        }
        return max(1, pages)
    }

    var filtered: [RecipeEntity] {
        query.isEmpty ? recipes : recipes.filter {
            $0.name.localizedCaseInsensitiveContains(query) ||
            $0.notes.localizedCaseInsensitiveContains(query)
        }
    }

    func attachments(for recipe: RecipeEntity) -> [RecipeAttachmentEntity] {
        attachments[recipe.id] ?? []
    }

    func pdfPath(_ attachment: RecipeAttachmentEntity) -> String {
        core.recipeStore.path(fileName: attachment.fileName)
    }

    func observeRecipes() async {
        for await list in core.recipesRepository.observeRecipes() {
            recipes = list
        }
    }

    func observeAttachments() async {
        for await list in core.recipesRepository.observeAttachments() {
            attachments = Dictionary(grouping: list, by: { $0.recipeId })
        }
    }

    func observeKey() async {
        for await value in core.secureKeys.hasAnthropicKey {
            hasKey = value.boolValue
        }
    }

    /// Writes already-converted PDF bytes into the store; returns the stored file name.
    private func storePdf(_ data: Data) throws -> String {
        let fileName = UUID().uuidString + ".pdf"
        try data.write(to: URL(fileURLWithPath: core.recipeStore.path(fileName: fileName)))
        return fileName
    }

    func save(
        id: Int64?,
        name: String,
        notes: String,
        newAttachments: [(data: Data, originalName: String)],
        removals: [RecipeAttachmentEntity],
    ) {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            message = "Recipe name is required"
            return
        }
        Task {
            do {
                let recipeId: Int64
                if let id {
                    try await core.recipesRepository.update(id: id, name: name, notes: notes)
                    recipeId = id
                } else {
                    recipeId = try await core.recipesRepository.create(name: name, notes: notes).int64Value
                }
                for removal in removals {
                    core.recipeStore.delete(fileName: removal.fileName)
                    try await core.recipesRepository.removeAttachment(attachmentId: removal.id)
                }
                for attachment in newAttachments {
                    let fileName = try storePdf(attachment.data)
                    _ = try await core.recipesRepository.addAttachment(
                        recipeId: recipeId, fileName: fileName, originalName: attachment.originalName,
                    )
                }
            } catch {
                message = "Save failed: \(error.localizedDescription)"
            }
        }
    }

    func delete(_ recipe: RecipeEntity) {
        Task {
            do {
                let mine = try await core.recipesRepository.getAttachments(recipeId: recipe.id)
                for attachment in mine {
                    core.recipeStore.delete(fileName: attachment.fileName)
                }
                try await core.recipesRepository.delete(id: recipe.id)
            } catch {
                message = "Delete failed: \(error.localizedDescription)"
            }
        }
    }

    /// "Take photo of recipe": image data -> PDF -> new recipe, named by Claude. Naming is
    /// free (the metered /v1/title endpoint never charges), so it is always attempted; a
    /// failure just keeps the fallback name.
    func createFromCapture(imageData: Data) {
        guard let pdf = ImageToPDF.convert(imageData) else {
            message = "Could not convert the photo to a PDF"
            return
        }
        working = "Creating recipe…"
        Task {
            defer { working = nil }
            do {
                let fileName = try storePdf(pdf)
                let recipeId = try await core.recipesRepository
                    .create(name: photographedFallbackName(), notes: "").int64Value
                _ = try await core.recipesRepository.addAttachment(
                    recipeId: recipeId, fileName: fileName, originalName: "photo.jpg",
                )
                guard autoNameAllowed() else {
                    pendingName = PendingName(recipeId: recipeId, reason: .dailyLimit)
                    return
                }
                working = "Naming recipe…"
                countAutoName()
                if let title = try await core.extractRecipeTitle(pdfData: pdf) {
                    try await core.recipesRepository.update(id: recipeId, name: title, notes: "")
                    message = "Recipe \"\(title)\" created from photo"
                } else {
                    pendingName = PendingName(recipeId: recipeId, reason: .namingFailed)
                }
            } catch {
                message = "Could not create recipe: \(error.localizedDescription)"
            }
        }
    }

    func createIngredients(for recipe: RecipeEntity, multiplier: Int = 1, onSuccess: @escaping (String) -> Void) {
        working = "Extracting ingredients…"
        Task {
            defer { working = nil }
            do {
                let outcome = try await core.ingredientTodosUseCase.run(recipeId: recipe.id, multiplier: Int32(multiplier))
                message = "Added \(outcome.count) ingredients to \"\(outcome.category)\""
                onSuccess(outcome.category)
            } catch {
                message = error.localizedDescription
                // The Worker's 402 crosses the Kotlin bridge as this message prefix
                // (InsufficientOpsException in MeteredOcrClient.kt — keep the wording in step).
                if error.localizedDescription.contains("Not enough conversion credits") {
                    paywallNeeded = true
                }
            }
            if !usesByoKey { await ops.refreshBalance() }
        }
    }

    // Settings

    func saveKey(_ key: String) {
        let cleaned = key.filter { !$0.isWhitespace }
        guard !cleaned.isEmpty else {
            message = "Enter an API key"
            return
        }
        working = "Checking API key…"
        Task {
            defer { working = nil }
            do {
                if let failure = try await core.anthropicClient.validateKey(apiKey: cleaned) {
                    message = "Key rejected by the Anthropic API (\(failure))"
                } else {
                    core.secureKeys.setAnthropicKey(key: cleaned)
                    message = "Anthropic API key saved"
                }
            } catch {
                message = "Could not validate key: \(error.localizedDescription)"
            }
        }
    }

    func deleteKey() {
        core.secureKeys.deleteAnthropicKey()
        message = "Anthropic API key removed"
    }

    func exportBackup() async -> Data? {
        working = "Exporting backup…"
        defer { working = nil }
        do {
            return try await core.exportBackup() as Data
        } catch {
            message = "Export failed: \(error.localizedDescription)"
            return nil
        }
    }

    func importBackup(_ data: Data) {
        working = "Importing backup…"
        Task {
            defer { working = nil }
            do {
                let s = try await core.importBackup(data: data)
                message = "Imported \(s.notes) notes, \(s.todos) todos, \(s.categories) categories, "
                    + "\(s.recipes) recipes (\(s.pdfs) PDFs), \(s.ingredients) cached ingredients"
            } catch {
                message = "Import failed: \(error.localizedDescription)"
            }
        }
    }
}

