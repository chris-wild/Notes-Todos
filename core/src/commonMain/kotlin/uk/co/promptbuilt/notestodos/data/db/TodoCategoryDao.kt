package uk.co.promptbuilt.notestodos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoCategoryDao {
    @Query("SELECT * FROM todo_categories")
    fun observeAll(): Flow<List<TodoCategoryEntity>>

    @Query("SELECT * FROM todo_categories WHERE normalizedName = :normalizedName")
    suspend fun getByNormalizedName(normalizedName: String): TodoCategoryEntity?

    @Insert
    suspend fun insert(category: TodoCategoryEntity): Long

    @Insert
    suspend fun insertAll(categories: List<TodoCategoryEntity>)

    @Query("DELETE FROM todo_categories WHERE normalizedName = :normalizedName")
    suspend fun deleteByNormalizedName(normalizedName: String)

    @Query("DELETE FROM todo_categories")
    suspend fun deleteAll()
}
