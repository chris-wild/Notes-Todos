package uk.co.promptbuilt.notestodos.ui.recipes

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.promptbuilt.notestodos.ai.IngredientTodosUseCase
import uk.co.promptbuilt.notestodos.ai.AnthropicClient
import uk.co.promptbuilt.notestodos.backup.BackupManager
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.RecipesRepository
import uk.co.promptbuilt.notestodos.data.SecureKeys
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity

data class RecipesUiState(
    val recipes: List<RecipeEntity> = emptyList(),
    val query: String = "",
    /** True when an Anthropic key is stored (replaces GET /api/features). */
    val ingredientAutomation: Boolean = false,
    /** Non-null while a long operation runs; shown as a blocking overlay. */
    val working: String? = null,
    /** One-shot status/error text shown until dismissed or replaced. */
    val message: String? = null,
)

class RecipesViewModel(
    private val repository: RecipesRepository,
    private val recipeFiles: RecipeFiles,
    private val secureKeys: SecureKeys,
    private val ingredientTodos: IngredientTodosUseCase,
    private val backupManager: BackupManager,
    private val anthropicClient: AnthropicClient,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val working = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RecipesUiState> = combine(
        repository.observeRecipes(),
        query,
        secureKeys.hasAnthropicKey,
        working,
        message,
    ) { recipes, q, hasKey, workingText, messageText ->
        val filtered = if (q.isBlank()) {
            recipes
        } else {
            // Matches server-side search over name/notes (datastore.js:453).
            recipes.filter {
                it.name.contains(q, ignoreCase = true) || it.notes.contains(q, ignoreCase = true)
            }
        }
        RecipesUiState(
            recipes = filtered,
            query = q,
            ingredientAutomation = hasKey,
            working = workingText,
            message = messageText,
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

    fun saveRecipe(
        id: Long?,
        name: String,
        notes: String,
        newAttachment: RecipeFiles.Imported?,
        removeAttachment: Boolean,
        onDone: () -> Unit,
    ) {
        if (name.isBlank()) {
            message.value = "Recipe name is required"
            return
        }
        viewModelScope.launch {
            if (id == null) {
                val recipeId = repository.create(name.trim(), notes)
                if (newAttachment != null) {
                    repository.setAttachment(recipeId, newAttachment.fileName, newAttachment.originalName)
                }
            } else {
                val existing = repository.getById(id)
                repository.update(id, name.trim(), notes)
                when {
                    newAttachment != null -> {
                        recipeFiles.delete(existing?.pdfFileName)
                        repository.setAttachment(id, newAttachment.fileName, newAttachment.originalName)
                    }
                    removeAttachment -> {
                        recipeFiles.delete(existing?.pdfFileName)
                        repository.setAttachment(id, null, null)
                    }
                }
            }
            onDone()
        }
    }

    fun deleteRecipe(recipe: RecipeEntity) {
        viewModelScope.launch {
            recipeFiles.delete(recipe.pdfFileName)
            repository.delete(recipe.id)
        }
    }

    /** Port of the web "Create ingredient list" action; navigates to Todos on success. */
    fun createIngredientTodos(recipeId: Long, onSuccess: () -> Unit) {
        viewModelScope.launch {
            working.value = "Extracting ingredients…"
            try {
                val outcome = ingredientTodos.run(recipeId)
                message.value = "Added ${outcome.count} ingredients to \"${outcome.category}\""
                onSuccess()
            } catch (e: Exception) {
                message.value = e.message ?: "Ingredient extraction failed"
            } finally {
                working.value = null
            }
        }
    }

    /** Validates against the live API before storing, like PUT /api/anthropic-key. */
    fun saveAnthropicKey(key: String, onResult: (Boolean) -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            message.value = "Enter an API key"
            onResult(false)
            return
        }
        viewModelScope.launch {
            working.value = "Checking API key…"
            try {
                if (anthropicClient.validateKey(trimmed)) {
                    secureKeys.setAnthropicKey(trimmed)
                    message.value = "Anthropic API key saved"
                    onResult(true)
                } else {
                    message.value = "That API key was rejected by the Anthropic API"
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
