package uk.co.promptbuilt.notestodos.backup

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.first
import uk.co.promptbuilt.notestodos.data.RecipeStore
import uk.co.promptbuilt.notestodos.data.db.AppDatabase

data class ImportSummary(val notes: Int, val todos: Int, val categories: Int, val recipes: Int, val ingredients: Int, val pdfs: Int)

/**
 * Bytes-in/bytes-out backup. Platform shells feed it: Android's SAF shim
 * streams Uris, iOS's fileExporter/fileImporter hand Data across directly.
 */
class BackupManager(
    private val db: AppDatabase,
    private val recipeStore: RecipeStore,
) {

    suspend fun exportBytes(): ByteArray {
        val recipes = db.recipeDao().observeAll().first()
        val ingredients = recipes.flatMap { db.ingredientDao().getForRecipe(it.id) }
        val pdfs = recipes.mapNotNull { it.pdfFileName }
            .mapNotNull { name -> recipeStore.read(name)?.let { name to it } }
            .toMap()
        val data = BackupData(
            notes = db.noteDao().observeAll().first(),
            todos = db.todoDao().observeAll().first(),
            categories = db.todoCategoryDao().observeAll().first(),
            recipes = recipes,
            ingredients = ingredients,
            pdfs = pdfs,
        )
        return BackupCodec.write(data)
    }

    /** Replaces ALL app data with the backup's contents. */
    suspend fun importBytes(bytes: ByteArray): ImportSummary {
        val data = BackupCodec.read(bytes)

        // Wipe-and-load in one transaction, children first, via DAO deletes
        // (clearAllTables is Android-only and skips invalidation on some paths).
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                db.ingredientDao().deleteAll()
                db.recipeDao().deleteAll()
                db.todoDao().deleteAll()
                db.todoCategoryDao().deleteAll()
                db.noteDao().deleteAll()

                db.noteDao().insertAll(data.notes)
                db.todoCategoryDao().insertAll(data.categories)
                db.todoDao().insertAll(data.todos)
                db.recipeDao().insertAll(data.recipes)
                db.ingredientDao().insertAll(data.ingredients)
            }
        }
        recipeStore.clearAll()
        for ((name, pdfBytes) in data.pdfs) recipeStore.write(name, pdfBytes)

        return ImportSummary(
            notes = data.notes.size,
            todos = data.todos.size,
            categories = data.categories.size,
            recipes = data.recipes.size,
            ingredients = data.ingredients.size,
            pdfs = data.pdfs.size,
        )
    }
}
