package uk.co.promptbuilt.notestodos.ui.recipes

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uk.co.promptbuilt.notestodos.ai.AnthropicClient
import uk.co.promptbuilt.notestodos.ai.IngredientTodosUseCase
import uk.co.promptbuilt.notestodos.ai.InsufficientOpsException
import uk.co.promptbuilt.notestodos.ai.OcrService
import uk.co.promptbuilt.notestodos.data.AppPrefs
import uk.co.promptbuilt.notestodos.store.OpsStore
import uk.co.promptbuilt.notestodos.backup.SafBackup
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.RecipesRepository
import uk.co.promptbuilt.notestodos.data.SecureKeys
import uk.co.promptbuilt.notestodos.data.db.RecipeAttachmentEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity

data class RecipesUiState(
    val recipes: List<RecipeEntity> = emptyList(),
    val attachmentsByRecipe: Map<Long, List<RecipeAttachmentEntity>> = emptyMap(),
    val query: String = "",
    /** True when a personal Anthropic key is stored (only honoured in debug builds). */
    val hasKey: Boolean = false,
    /** Non-null while a long operation runs; shown as a blocking overlay. */
    val working: String? = null,
    /** One-shot status/error text shown until dismissed or replaced. */
    val message: String? = null,
    /** A captured recipe waiting for the user to type its name (limit reached or naming failed). */
    val pendingName: PendingName? = null,
)

/**
 * Automatic naming is budgeted (each call costs money server-side) and can fail; either way the
 * recipe is already saved under a date-stamped name, and this asks for the real one.
 */
data class PendingName(val recipeId: Long, val reason: Reason) {
    enum class Reason { DAILY_LIMIT, NAMING_FAILED }
}

/** How a Create ingredient list tap proceeds, decided before any credit copy is shown. */
sealed interface ConversionGate {
    /** No credits involved: a debug build's own key, or ingredients already extracted. */
    data object Free : ConversionGate
    data class Confirm(val cost: Int) : ConversionGate
    data object Paywall : ConversionGate
}

class RecipesViewModel(
    private val repository: RecipesRepository,
    private val recipeFiles: RecipeFiles,
    private val secureKeys: SecureKeys,
    private val ingredientTodos: IngredientTodosUseCase,
    private val backupManager: SafBackup,
    private val anthropicClient: AnthropicClient,
    private val ocr: OcrService,
    private val opsStore: OpsStore,
    private val appPrefs: AppPrefs,
    private val usesByoKey: () -> Boolean,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val working = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val pendingName = MutableStateFlow<PendingName?>(null)

    val uiState: StateFlow<RecipesUiState> = combine(
        repository.observeRecipes(),
        repository.observeAttachments(),
        query,
        secureKeys.hasAnthropicKey,
        combine(working, message, pendingName) { w, m, p -> Triple(w, m, p) },
    ) { recipes, attachments, q, hasKey, transient ->
        val filtered = if (q.isBlank()) {
            recipes
        } else {
            recipes.filter {
                it.name.contains(q, ignoreCase = true) || it.notes.contains(q, ignoreCase = true)
            }
        }
        RecipesUiState(
            recipes = filtered,
            attachmentsByRecipe = attachments.groupBy { it.recipeId },
            query = q,
            hasKey = hasKey,
            working = transient.first,
            message = transient.second,
            pendingName = transient.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun dismissMessage() {
        message.value = null
    }

    /** Copies/converts a picked attachment into app storage as soon as it is chosen. */
    fun importAttachment(uri: Uri, onImported: (RecipeFiles.Imported) -> Unit) {
        viewModelScope.launch {
            try {
                onImported(recipeFiles.importAttachment(uri))
            } catch (e: Exception) {
                message.value = "Could not read the selected file: ${e.message}"
            }
        }
    }

    /** Deletes an imported-but-unsaved attachment (picker cancelled / replaced). */
    fun discardImported(imported: RecipeFiles.Imported) {
        recipeFiles.delete(imported.fileName)
    }

    /**
     * A new recipe saved with a blank name and at least one attachment is named automatically
     * from its first attachment's first page, as a photographed one is. It is saved first under
     * a date-stamped name, so nothing is lost if naming is over its daily limit or fails.
     */
    fun saveRecipe(
        id: Long?,
        name: String,
        notes: String,
        newAttachments: List<RecipeFiles.Imported>,
        removals: List<RecipeAttachmentEntity>,
        onDone: () -> Unit,
    ) {
        val autoName = id == null && name.isBlank() && newAttachments.isNotEmpty()
        if (name.isBlank() && !autoName) {
            message.value = if (id == null) "Type a name, or attach a recipe to have it named" else "Recipe name is required"
            return
        }
        viewModelScope.launch {
            val recipeId = if (id == null) {
                repository.create(if (autoName) fallbackName("Added") else name.trim(), notes)
            } else {
                repository.update(id, name.trim(), notes)
                id
            }
            for (removal in removals) {
                recipeFiles.delete(removal.fileName)
                repository.removeAttachment(removal.id)
            }
            for (imported in newAttachments) {
                repository.addAttachment(recipeId, imported.fileName, imported.originalName)
            }
            onDone()
            if (autoName) {
                try {
                    nameAutomatically(recipeId, newAttachments.first().fileName, notes) { "Recipe \"$it\" added" }
                } finally {
                    working.value = null
                }
            }
        }
    }

    fun deleteRecipe(recipe: RecipeEntity) {
        viewModelScope.launch {
            repository.getAttachments(recipe.id).forEach { recipeFiles.delete(it.fileName) }
            repository.delete(recipe.id)
        }
    }

    /**
     * "Take photo of recipe": the captured image is already a PDF (importAttachment). The recipe
     * is created under a unique date-stamped name, then named by the metered title call, within
     * the daily budget. Past the budget, or if naming fails, the user types the name instead.
     */
    fun createFromCapture(imported: RecipeFiles.Imported) {
        viewModelScope.launch {
            working.value = "Creating recipe…"
            try {
                val recipeId = repository.create(fallbackName("Photographed"), "")
                repository.addAttachment(recipeId, imported.fileName, imported.originalName)
                nameAutomatically(recipeId, imported.fileName, notes = "") { "Recipe \"$it\" created from photo" }
            } catch (e: Exception) {
                message.value = "Could not create recipe: ${e.message}"
            } finally {
                working.value = null
            }
        }
    }

    /** The name dialog's Save; a blank name keeps the date-stamped fallback. */
    fun renamePending(name: String) {
        val pending = pendingName.value ?: return
        pendingName.value = null
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val notes = repository.getById(pending.recipeId)?.notes.orEmpty()
            repository.update(pending.recipeId, trimmed, notes)
        }
    }

    fun dismissPendingName() {
        pendingName.value = null
    }

    /**
     * Names a just-saved recipe from the first page of [fileName], within the daily budget.
     * Past the budget, or if naming fails, the user types the name instead. [notes] are kept.
     */
    private suspend fun nameAutomatically(recipeId: Long, fileName: String, notes: String, done: (String) -> String) {
        if (!usesByoKey() && !appPrefs.autoNameAllowed(opsStore.state.value.namingExempt)) {
            pendingName.value = PendingName(recipeId, PendingName.Reason.DAILY_LIMIT)
            return
        }
        appPrefs.countAutoName()
        working.value = "Naming recipe…"
        val title = recipeFiles.firstPageForNaming(fileName)?.let { pdf ->
            try {
                ocr.extractRecipeTitle(pdf)
            } catch (_: Exception) {
                null
            }
        }
        if (title != null) {
            repository.update(recipeId, title, notes)
            message.value = done(title)
        } else {
            pendingName.value = PendingName(recipeId, PendingName.Reason.NAMING_FAILED)
        }
    }

    /** "Photographed 1 Oct 2026" or "Added 1 Oct 2026", with " (2)", " (3)" and so on when taken. */
    private suspend fun fallbackName(prefix: String): String {
        val base = "$prefix " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
        val names = repository.observeRecipes().first().map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base ($n)" in names) n++
        return "$base ($n)"
    }

    /**
     * Decides the gate before showing any credit copy: a recipe whose ingredients were already
     * extracted re-runs from the cache (no API call, no charge), so it never asks about credits.
     */
    suspend fun conversionGate(recipe: RecipeEntity): ConversionGate {
        if (usesByoKey()) return ConversionGate.Free
        if (repository.getIngredients(recipe.id).isNotEmpty()) return ConversionGate.Free
        opsStore.refreshBalance()
        val cost = opsCost(recipe)
        return if ((opsStore.state.value.balance ?: 0) < cost) ConversionGate.Paywall else ConversionGate.Confirm(cost)
    }

    /**
     * What converting costs, mirroring the Worker's billing: one credit per PDF page, or one
     * for a notes-only recipe. A preview only; the Worker's own page count is what is charged.
     */
    private suspend fun opsCost(recipe: RecipeEntity): Int {
        val attachments = repository.getAttachments(recipe.id)
        if (attachments.isEmpty()) return 1
        return attachments.sumOf { attachment ->
            try {
                ParcelFileDescriptor.open(recipeFiles.fileFor(attachment.fileName), ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { fd -> PdfRenderer(fd).use { it.pageCount } }
            } catch (_: Exception) {
                1
            }
        }.coerceAtLeast(1)
    }

    /**
     * Port of the web "Create ingredient list" action. [onSuccess] receives the new todo
     * category; [onNeedsCredits] opens the paywall when the Worker reports a shortfall (its own
     * page count can exceed the preview).
     */
    fun createIngredientTodos(
        recipeId: Long,
        multiplier: Int = 1,
        onNeedsCredits: () -> Unit,
        onSuccess: (String) -> Unit,
    ) {
        viewModelScope.launch {
            working.value = "Extracting ingredients…"
            try {
                val outcome = ingredientTodos.run(recipeId, multiplier)
                message.value = "Added ${outcome.count} ingredients to \"${outcome.category}\""
                onSuccess(outcome.category)
            } catch (e: InsufficientOpsException) {
                onNeedsCredits()
            } catch (e: Exception) {
                message.value = e.message ?: "Ingredient extraction failed"
            } finally {
                working.value = null
                opsStore.refreshBalance()
            }
        }
    }

    /** Validates against the live API before storing, like PUT /api/anthropic-key. */
    fun saveAnthropicKey(key: String, onResult: (Boolean) -> Unit) {
        val cleaned = key.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) {
            message.value = "Enter an API key"
            onResult(false)
            return
        }
        viewModelScope.launch {
            working.value = "Checking API key…"
            try {
                val failure = anthropicClient.validateKey(cleaned)
                if (failure == null) {
                    secureKeys.setAnthropicKey(cleaned)
                    message.value = "Anthropic API key saved"
                    onResult(true)
                } else {
                    message.value = "Key rejected by the Anthropic API ($failure)"
                    onResult(false)
                }
            } catch (e: Exception) {
                message.value = "Could not validate key: ${e.message}"
                onResult(false)
            } finally {
                working.value = null
            }
        }
    }

    fun deleteAnthropicKey() {
        secureKeys.deleteAnthropicKey()
        message.value = "Anthropic API key removed"
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            working.value = "Exporting backup…"
            try {
                backupManager.exportTo(uri)
                message.value = "Backup exported"
            } catch (e: Exception) {
                message.value = "Export failed: ${e.message}"
            } finally {
                working.value = null
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            working.value = "Importing backup…"
            try {
                val s = backupManager.importFrom(uri)
                message.value =
                    "Imported ${s.notes} notes, ${s.todos} todos, ${s.categories} categories, " +
                        "${s.recipes} recipes (${s.pdfs} PDFs), ${s.ingredients} cached ingredients"
            } catch (e: Exception) {
                message.value = "Import failed: ${e.message}"
            } finally {
                working.value = null
            }
        }
    }
}
