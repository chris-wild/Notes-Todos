package uk.co.promptbuilt.notestodos.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.db.AppDatabase

data class ImportSummary(val notes: Int, val todos: Int, val categories: Int, val recipes: Int, val ingredients: Int, val pdfs: Int)

class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val recipeFiles: RecipeFiles,
) {

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val recipes = db.recipeDao().observeAll().first()
        val ingredients = recipes.flatMap { db.ingredientDao().getForRecipe(it.id) }
        val pdfs = recipes.mapNotNull { it.pdfFileName }
            .mapNotNull { name ->
                val file = recipeFiles.fileFor(name)
                if (file.exists()) name to file.readBytes() else null
            }
            .toMap()
        val data = BackupData(
            notes = db.noteDao().observeAll().first(),
            todos = db.todoDao().observeAll().first(),
            categories = db.todoCategoryDao().observeAll().first(),
            recipes = recipes,
            ingredients = ingredients,
            pdfs = pdfs,
        )
        context.contentResolver.openOutputStream(uri)?.use { BackupCodec.write(data, it) }
            ?: throw IllegalStateException("Could not open destination for writing")
    }

    /** Replaces ALL app data with the backup's contents. */
    suspend fun importFrom(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val data = context.contentResolver.openInputStream(uri)?.use { BackupCodec.read(it) }
            ?: throw IllegalStateException("Could not open backup for reading")

        db.clearAllTables()
        recipeFiles.clearAll()

        db.noteDao().insertAll(data.notes)
        db.todoCategoryDao().insertAll(data.categories)
        db.todoDao().insertAll(data.todos)
        db.recipeDao().insertAll(data.recipes)
        db.ingredientDao().insertAll(data.ingredients)
        for ((name, bytes) in data.pdfs) recipeFiles.fileFor(name).writeBytes(bytes)

        ImportSummary(
            notes = data.notes.size,
            todos = data.todos.size,
            categories = data.categories.size,
            recipes = data.recipes.size,
            ingredients = data.ingredients.size,
            pdfs = data.pdfs.size,
        )
    }
}
