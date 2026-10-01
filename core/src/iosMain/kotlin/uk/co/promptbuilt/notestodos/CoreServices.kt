package uk.co.promptbuilt.notestodos

import platform.Foundation.NSData
import uk.co.promptbuilt.notestodos.ai.AnthropicClient
import uk.co.promptbuilt.notestodos.ai.ByoOcrService
import uk.co.promptbuilt.notestodos.ai.IngredientTodosUseCase
import uk.co.promptbuilt.notestodos.ai.MeteredOcrClient
import uk.co.promptbuilt.notestodos.ai.OcrService
import uk.co.promptbuilt.notestodos.ai.SwitchingOcrService
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
 * How this build pays for OCR: [workerBaseUrl] + [opsToken] name the metering Worker and
 * the anonymous account (a Keychain UUID minted by Swift, doubling as the StoreKit
 * appAccountToken). [allowByoKey] is true only in Debug builds — when a personal Anthropic
 * key is stored, calls go direct; released users always meter.
 */
class MeteredConfig(
    val workerBaseUrl: String,
    val opsToken: String,
    val allowByoKey: Boolean,
    /** Preferred unit system for extracted quantities ("metric" / "us"), read per call so a
     *  Settings change applies immediately; the Worker injects the conversion prompt. */
    val unitsProvider: () -> String? = { null },
)

/**
 * The whole shared object graph, one call from Swift:
 * `CoreServices(secretStore: KeychainSecretStore(), metered: …)`.
 * The secret store comes from Swift (Keychain) — see SecretStore.
 */
class CoreServices(secretStore: SecretStore, metered: MeteredConfig) {
    val database = buildAppDatabase()
    val recipeStore = IosRecipeStore()

    val notesRepository = NotesRepository(database.noteDao())
    val todosRepository = TodosRepository(database, database.todoDao(), database.todoCategoryDao())
    val recipesRepository = RecipesRepository(database, database.recipeDao(), database.ingredientDao(), database.recipeAttachmentDao())
    val settingsRepository = SettingsRepository(createSettingsDataStore())

    val secureKeys = SecureKeys(secretStore)
    val anthropicClient = AnthropicClient()
    private val byoOcr = ByoOcrService(anthropicClient, secureKeys)
    private val meteredOcr = MeteredOcrClient(metered.workerBaseUrl, { metered.opsToken }, metered.unitsProvider)
    val ocrService: OcrService = SwitchingOcrService {
        if (metered.allowByoKey && secureKeys.getAnthropicKey() != null) byoOcr else meteredOcr
    }
    val ingredientTodosUseCase = IngredientTodosUseCase(
        recipesRepository, todosRepository, recipeStore, ocrService,
    )
    val backupManager = BackupManager(database, recipeStore)

    // NSData bridges so 60 MB backups cross to Swift as Data (memcpy), never
    // element-by-element through KotlinByteArray. @Throws on every suspend entry point
    // Swift awaits: without it, SKIE's async bridge treats a Kotlin exception as an
    // UNHANDLED coroutine failure and terminates the app instead of throwing to Swift.
    @Throws(Exception::class)
    suspend fun exportBackup(): NSData = backupManager.exportBytes().toNSData()

    @Throws(Exception::class)
    suspend fun importBackup(data: NSData): ImportSummary =
        backupManager.importBytes(data.toByteArray())

    /** Names a photographed recipe; metered title calls are free, so no key is needed. */
    @Throws(Exception::class)
    suspend fun extractRecipeTitle(pdfData: NSData): String? =
        ocrService.extractRecipeTitle(pdfData.toByteArray())
}
