package uk.co.promptbuilt.notestodos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: Long): RecipeEntity?

    @Insert
    suspend fun insert(recipe: RecipeEntity): Long

    @Insert
    suspend fun insertAll(recipes: List<RecipeEntity>)

    @Update
    suspend fun update(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recipes")
    suspend fun deleteAll()
}

@Dao
interface RecipeAttachmentDao {
    @Query("SELECT * FROM recipe_attachments ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<RecipeAttachmentEntity>>

    @Query("SELECT * FROM recipe_attachments WHERE recipeId = :recipeId ORDER BY position ASC, id ASC")
    suspend fun getForRecipe(recipeId: Long): List<RecipeAttachmentEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM recipe_attachments WHERE recipeId = :recipeId")
    suspend fun maxPosition(recipeId: Long): Int

    @Insert
    suspend fun insert(attachment: RecipeAttachmentEntity): Long

    @Insert
    suspend fun insertAll(attachments: List<RecipeAttachmentEntity>)

    @Query("DELETE FROM recipe_attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recipe_attachments")
    suspend fun deleteAll()
}

@Dao
interface IngredientDao {
    @Query("SELECT * FROM ingredients WHERE recipeId = :recipeId ORDER BY id ASC")
    suspend fun getForRecipe(recipeId: Long): List<IngredientEntity>

    @Insert
    suspend fun insertAll(ingredients: List<IngredientEntity>)

    @Query("DELETE FROM ingredients WHERE recipeId = :recipeId")
    suspend fun deleteForRecipe(recipeId: Long)

    @Query("DELETE FROM ingredients")
    suspend fun deleteAll()
}
