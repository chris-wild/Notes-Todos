package uk.co.promptbuilt.notestodos.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import uk.co.promptbuilt.notestodos.data.db.AppDatabase
import uk.co.promptbuilt.notestodos.data.db.IngredientDao
import uk.co.promptbuilt.notestodos.data.db.IngredientEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeDao
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity

class RecipesRepository(
    private val db: AppDatabase,
    private val recipeDao: RecipeDao,
    private val ingredientDao: IngredientDao,
) {

    fun observeRecipes(): Flow<List<RecipeEntity>> = recipeDao.observeAll()

    suspend fun getById(id: Long): RecipeEntity? = recipeDao.getById(id)

    suspend fun create(name: String, notes: String): Long {
        val now = System.currentTimeMillis()
        return recipeDao.insert(
            RecipeEntity(name = name, notes = notes, createdAt = now, updatedAt = now),
        )
    }

    suspend fun update(id: Long, name: String, notes: String) {
        val recipe = recipeDao.getById(id) ?: return
        recipeDao.update(recipe.copy(name = name, notes = notes, updatedAt = System.currentTimeMillis()))
    }

    /** Attachment file lifecycle (writing/deleting the PDF in filesDir/recipes/) is handled by the caller. */
    suspend fun setAttachment(id: Long, pdfFileName: String?, pdfOriginalName: String?) {
        val recipe = recipeDao.getById(id) ?: return
        recipeDao.update(
            recipe.copy(
                pdfFileName = pdfFileName,
                pdfOriginalName = pdfOriginalName,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: Long) = recipeDao.delete(id) // ingredients cascade

    suspend fun getIngredients(recipeId: Long): List<IngredientEntity> =
        ingredientDao.getForRecipe(recipeId)

    /** Replaces the cached extraction result for a recipe. */
    suspend fun replaceIngredients(recipeId: Long, ingredients: List<IngredientEntity>) {
        db.withTransaction {
            ingredientDao.deleteForRecipe(recipeId)
            ingredientDao.insertAll(ingredients)
        }
    }

    /** Stamps which todo category the ingredients were written to, and how many. */
    suspend fun setIngredientMetadata(recipeId: Long, todoCategory: String, count: Int) {
        val recipe = recipeDao.getById(recipeId) ?: return
        recipeDao.update(
            recipe.copy(
                ingredientTodoCategory = todoCategory,
                ingredientTodosCount = count,
                ingredientTodosCreatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
