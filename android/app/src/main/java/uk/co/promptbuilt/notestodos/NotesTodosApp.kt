package uk.co.promptbuilt.notestodos

import android.app.Application
import uk.co.promptbuilt.notestodos.ai.AnthropicClient
import uk.co.promptbuilt.notestodos.ai.IngredientTodosUseCase
import uk.co.promptbuilt.notestodos.backup.BackupManager
import uk.co.promptbuilt.notestodos.data.NotesRepository
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.RecipesRepository
import uk.co.promptbuilt.notestodos.data.SecureKeys
import uk.co.promptbuilt.notestodos.data.SettingsRepository
import uk.co.promptbuilt.notestodos.data.TodosRepository
import uk.co.promptbuilt.notestodos.data.db.AppDatabase

class NotesTodosApp : Application() {

    val database by lazy { AppDatabase.build(this) }

    val notesRepository by lazy { NotesRepository(database.noteDao()) }
    val todosRepository by lazy {
        TodosRepository(database, database.todoDao(), database.todoCategoryDao())
    }
    val recipesRepository by lazy {
        RecipesRepository(database, database.recipeDao(), database.ingredientDao())
    }
    val settingsRepository by lazy { SettingsRepository(this) }

    val recipeFiles by lazy { RecipeFiles(this) }
    val secureKeys by lazy { SecureKeys(this) }
    val anthropicClient by lazy { AnthropicClient() }
    val ingredientTodosUseCase by lazy {
        IngredientTodosUseCase(recipesRepository, todosRepository, recipeFiles, anthropicClient, secureKeys)
    }
    val backupManager by lazy { BackupManager(this, database, recipeFiles) }
}
