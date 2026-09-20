package uk.co.promptbuilt.notestodos.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.Flow
import uk.co.promptbuilt.notestodos.data.db.AppDatabase
import uk.co.promptbuilt.notestodos.data.db.IngredientDao
import uk.co.promptbuilt.notestodos.data.db.IngredientEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeAttachmentDao
import uk.co.promptbuilt.notestodos.data.db.RecipeAttachmentEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeDao
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity

class RecipesRepository(
    private val db: AppDatabase,
    private val recipeDao: RecipeDao,
    private val ingredientDao: IngredientDao,
    private val attachmentDao: RecipeAttachmentDao,
) {

    fun observeRecipes(): Flow<List<RecipeEntity>> = recipeDao.observeAll()

    suspend fun getById(id: Long): RecipeEntity? = recipeDao.getById(id)

    suspend fun create(name: String, notes: String): Long {
        val now = nowMillis()
        return recipeDao.insert(
            RecipeEntity(name = name, notes = notes, createdAt = now, updatedAt = now),
        )
    }

    suspend fun update(id: Long, name: String, notes: String) {
        val recipe = recipeDao.getById(id) ?: return
        recipeDao.update(recipe.copy(name = name, notes = notes, updatedAt = nowMillis()))
    }

    fun observeAttachments(): Flow<List<RecipeAttachmentEntity>> = attachmentDao.observeAll()

    suspend fun getAttachments(recipeId: Long): List<RecipeAttachmentEntity> =
        attachmentDao.getForRecipe(recipeId)

    /** Attachment FILE lifecycle (writing/deleting in the platform store) is the caller's. */
    suspend fun addAttachment(recipeId: Long, fileName: String, originalName: String?): Long {
        val position = attachmentDao.maxPosition(recipeId) + 1
        val id = attachmentDao.insert(
            RecipeAttachmentEntity(
                recipeId = recipeId,
                fileName = fileName,
                originalName = originalName,
                position = position,
                createdAt = nowMillis(),
            ),
        )
        recipeDao.getById(recipeId)?.let { recipeDao.update(it.copy(updatedAt = nowMillis())) }
        return id
    }

    suspend fun removeAttachment(attachmentId: Long) = attachmentDao.delete(attachmentId)

    suspend fun delete(id: Long) = recipeDao.delete(id) // ingredients + attachments cascade

    suspend fun getIngredients(recipeId: Long): List<IngredientEntity> =
        ingredientDao.getForRecipe(recipeId)

    /** Replaces the cached extraction result for a recipe. */
    suspend fun replaceIngredients(recipeId: Long, ingredients: List<IngredientEntity>) {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                ingredientDao.deleteForRecipe(recipeId)
                ingredientDao.insertAll(ingredients)
            }
        }
    }

    /** Stamps which todo category the ingredients were written to, and how many. */
    suspend fun setIngredientMetadata(recipeId: Long, todoCategory: String, count: Int) {
        val recipe = recipeDao.getById(recipeId) ?: return
        recipeDao.update(
            recipe.copy(
                ingredientTodoCategory = todoCategory,
                ingredientTodosCount = count,
                ingredientTodosCreatedAt = nowMillis(),
            ),
        )
    }
}
