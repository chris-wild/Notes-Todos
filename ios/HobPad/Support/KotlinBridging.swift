import HobPadCore

// Immutable Kotlin data classes crossing Swift concurrency boundaries (for-await
// loops feeding @MainActor models). Each conformance is justified: every stored
// property on these core types is a val of a value-ish type, and the classes are
// final — they cannot be mutated after construction.
extension NoteEntity: @unchecked @retroactive Sendable {}
extension TodoEntity: @unchecked @retroactive Sendable {}
extension TodoCategoryEntity: @unchecked @retroactive Sendable {}
extension RecipeEntity: @unchecked @retroactive Sendable {}
extension IngredientEntity: @unchecked @retroactive Sendable {}
extension ImportSummary: @unchecked @retroactive Sendable {}

// Service facades: no Swift-visible mutable state; internally they sit on
// Room (thread-safe), StateFlow (thread-safe), URLSession, and the Keychain.
// Their suspend functions hop to Kotlin dispatchers internally.
extension CoreServices: @unchecked @retroactive Sendable {}
extension NotesRepository: @unchecked @retroactive Sendable {}
extension TodosRepository: @unchecked @retroactive Sendable {}
extension RecipesRepository: @unchecked @retroactive Sendable {}
extension SettingsRepository: @unchecked @retroactive Sendable {}
extension SecureKeys: @unchecked @retroactive Sendable {}
extension AnthropicClient: @unchecked @retroactive Sendable {}
extension IngredientTodosUseCase: @unchecked @retroactive Sendable {}
extension IngredientTodosUseCase.Outcome: @unchecked @retroactive Sendable {}
extension BackupManager: @unchecked @retroactive Sendable {}
extension IosRecipeStore: @unchecked @retroactive Sendable {}
