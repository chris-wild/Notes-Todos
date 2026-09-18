package uk.co.promptbuilt.notestodos

import platform.Foundation.NSData
import uk.co.promptbuilt.notestodos.ai.AnthropicClient
import uk.co.promptbuilt.notestodos.ai.IngredientTodosUseCase
import uk.co.promptbuilt.notestodos.backup.BackupManager
import uk.co.promptbuilt.notestodos.backup.ImportSummary
import uk.co.promptbuilt.notestodos.data.IosRecipeStore
import uk.co.promptbuilt.notestodos.data.NotesRepository
import uk.co.promptbuilt.notestodos.data.RecipesRepository
import uk.co.promptbuilt.notestodos.data.SecretStore
import uk.co.promptbuilt.notestodos.data.SecureKeys
import uk.co.promptbuilt.notestodos.data.SettingsRepository
import uk.co.promptbuilt.notestodos.data.TodosRepository
import uk.co.promptbuilt.notestodos.data.buildAppDatabase
import uk.co.promptbuilt.notestodos.data.createSettingsDataStore

/**
 * The whole shared object graph, one call from Swift:
 * `CoreServices(secretStore: KeychainSecretStore())`.
 * The secret store comes from Swift (Keychain) — see SecretStore.
 */
class CoreServices(secretStore: SecretStore) {
    val database = buildAppDatabase()
    val recipeStore = IosRecipeStore()

    val notesRepository = NotesRepository(database.noteDao())
    val todosRepository = TodosRepository(database, database.todoDao(), database.todoCategoryDao())
    val recipesRepository = RecipesRepository(database, database.recipeDao(), database.ingredientDao())
    val settingsRepository = SettingsRepository(createSettingsDataStore())

    val secureKeys = SecureKeys(secretStore)
    val anthropicClient = AnthropicClient()
    val ingredientTodosUseCase = IngredientTodosUseCase(
        recipesRepository, todosRepository, recipeStore, anthropicClient, secureKeys,
    )
    val backupManager = BackupManager(database, recipeStore)

    // NSData bridges so 60 MB backups cross to Swift as Data (memcpy), never
    // element-by-element through KotlinByteArray.
    suspend fun exportBackup(): NSData = backupManager.exportBytes().toNSData()

    suspend fun importBackup(data: NSData): ImportSummary =
        backupManager.importBytes(data.toByteArray())
}
