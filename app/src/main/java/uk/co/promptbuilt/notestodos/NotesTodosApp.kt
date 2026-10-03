package uk.co.promptbuilt.notestodos

import android.app.Application
import uk.co.promptbuilt.notestodos.ai.AnthropicClient
import uk.co.promptbuilt.notestodos.ai.ByoOcrService
import uk.co.promptbuilt.notestodos.ai.IngredientTodosUseCase
import uk.co.promptbuilt.notestodos.ai.MeteredOcrClient
import uk.co.promptbuilt.notestodos.ai.OcrService
import uk.co.promptbuilt.notestodos.ai.SwitchingOcrService
import uk.co.promptbuilt.notestodos.backup.BackupManager
import uk.co.promptbuilt.notestodos.backup.DriveBackup
import uk.co.promptbuilt.notestodos.backup.SafBackup
import kotlinx.coroutines.flow.MutableStateFlow
import uk.co.promptbuilt.notestodos.data.AndroidSecretStore
import uk.co.promptbuilt.notestodos.data.AppPrefs
import uk.co.promptbuilt.notestodos.data.NotesRepository
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.RecipesRepository
import uk.co.promptbuilt.notestodos.data.SecureKeys
import uk.co.promptbuilt.notestodos.data.SettingsRepository
import uk.co.promptbuilt.notestodos.data.TodosRepository
import uk.co.promptbuilt.notestodos.data.buildAppDatabase
import uk.co.promptbuilt.notestodos.data.createSettingsDataStore
import uk.co.promptbuilt.notestodos.store.OpsAccount
import uk.co.promptbuilt.notestodos.store.OpsStore
import uk.co.promptbuilt.notestodos.store.OpsWorkerApi
import uk.co.promptbuilt.notestodos.store.StarterCheck

class NotesTodosApp : Application() {

    val database by lazy { buildAppDatabase(this) }

    val notesRepository by lazy { NotesRepository(database.noteDao()) }
    val todosRepository by lazy {
        TodosRepository(database, database.todoDao(), database.todoCategoryDao())
    }
    val recipesRepository by lazy {
        RecipesRepository(database, database.recipeDao(), database.ingredientDao(), database.recipeAttachmentDao())
    }
    val settingsRepository by lazy { SettingsRepository(createSettingsDataStore(this)) }

    val recipeFiles: RecipeFiles by lazy {
        RecipeFiles(this, onDeleted = { driveBackup.recordDeletion(it) }, onChanged = { driveBackup.requestSync() })
    }
    val driveBackup: DriveBackup by lazy { DriveBackup(this, { recipeFiles }, { recipesRepository }) }
    val secureKeys by lazy { SecureKeys(AndroidSecretStore(this)) }
    val anthropicClient by lazy { AnthropicClient() }
    val appPrefs by lazy { AppPrefs(this) }

    // Conversion is metered through the Worker, as on iOS. A personal Anthropic key is honoured
    // only in debug builds (a developer tool); release builds have no key UI at all.
    val opsAccount by lazy { OpsAccount(this) }
    val opsApi by lazy { OpsWorkerApi(BuildConfig.OPS_WORKER_URL) }
    val opsStore by lazy { OpsStore(this, opsAccount, opsApi, StarterCheck(this)) }
    private val meteredOcr by lazy {
        MeteredOcrClient(BuildConfig.OPS_WORKER_URL, { opsAccount.token() }, { appPrefs.resolvedUnits() })
    }
    private val byoOcr by lazy { ByoOcrService(anthropicClient, secureKeys) }
    val ocrService: OcrService by lazy {
        SwitchingOcrService { if (usesByoKey()) byoOcr else meteredOcr }
    }
    val ingredientTodosUseCase by lazy {
        IngredientTodosUseCase(recipesRepository, todosRepository, recipeFiles, ocrService)
    }
    val safBackup by lazy { SafBackup(this, BackupManager(database, recipeFiles)) }

    /** Creating an ingredient list opens the Todos tab on the new category (iOS: AppServices). */
    val pendingTodoCategory = MutableStateFlow<String?>(null)

    fun usesByoKey(): Boolean = BuildConfig.DEBUG && secureKeys.getAnthropicKey() != null

    override fun onCreate() {
        super.onCreate()
        driveBackup.onAppStart()
        opsStore.start()
        // As iOS's PdfCompactor at launch: shrink any recipe PDF too large to convert.
        Thread({ runCatching { recipeFiles.compactOversized() } }, "pdf-compactor").start()
    }
}
