import Foundation
import Observation
import HobPadCore

@Observable @MainActor
final class RecipesModel {
    private let core: CoreServices

    private(set) var recipes: [RecipeEntity] = []
    var query = ""
    private(set) var hasKey = false
    private(set) var working: String?
    var message: String?

    init(core: CoreServices) {
        self.core = core
    }

    var filtered: [RecipeEntity] {
        query.isEmpty ? recipes : recipes.filter {
            $0.name.localizedCaseInsensitiveContains(query) ||
            $0.notes.localizedCaseInsensitiveContains(query)
        }
    }

    func pdfPath(_ recipe: RecipeEntity) -> String? {
        guard let name = recipe.pdfFileName else { return nil }
        return core.recipeStore.path(fileName: name)
    }

    func observeRecipes() async {
        for await list in core.recipesRepository.observeRecipes() {
            recipes = list
        }
    }

    func observeKey() async {
        for await value in core.secureKeys.hasAnthropicKey {
            hasKey = value.boolValue
        }
    }

    /// newAttachment: already-converted PDF bytes + display name, or nil.
    func save(
        id: Int64?,
        name: String,
        notes: String,
        newAttachment: (data: Data, originalName: String)?,
        removeAttachment: Bool,
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
                let existing = try await core.recipesRepository.getById(id: recipeId)
                if let attachment = newAttachment {
                    if let old = existing?.pdfFileName { core.recipeStore.delete(fileName: old) }
                    let fileName = UUID().uuidString + ".pdf"
                    let path = core.recipeStore.path(fileName: fileName)
                    try attachment.data.write(to: URL(fileURLWithPath: path))
                    try await core.recipesRepository.setAttachment(
                        id: recipeId, pdfFileName: fileName, pdfOriginalName: attachment.originalName,
                    )
                } else if removeAttachment, let old = existing?.pdfFileName {
                    core.recipeStore.delete(fileName: old)
                    try await core.recipesRepository.setAttachment(id: recipeId, pdfFileName: nil, pdfOriginalName: nil)
                }
            } catch {
                message = "Save failed: \(error.localizedDescription)"
            }
        }
    }

    func delete(_ recipe: RecipeEntity) {
        Task {
            if let name = recipe.pdfFileName { core.recipeStore.delete(fileName: name) }
            try? await core.recipesRepository.delete(id: recipe.id)
        }
    }

    func createIngredients(for recipe: RecipeEntity, onSuccess: @escaping () -> Void) {
        working = "Extracting ingredients…"
        Task {
            defer { working = nil }
            do {
                let outcome = try await core.ingredientTodosUseCase.run(recipeId: recipe.id)
                message = "Added \(outcome.count) ingredients to \"\(outcome.category)\""
                onSuccess()
            } catch {
                message = error.localizedDescription
            }
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
