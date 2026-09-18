package uk.co.promptbuilt.notestodos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT DISTINCT category FROM todos")
    fun observeCategoriesInUse(): Flow<List<String>>

    @Insert
    suspend fun insert(todo: TodoEntity): Long

    @Insert
    suspend fun insertAll(todos: List<TodoEntity>)

    @Query("UPDATE todos SET completed = :completed WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean)

    @Query("UPDATE todos SET category = :to WHERE category = :from COLLATE NOCASE")
    suspend fun reassignCategory(from: String, to: String)

    @Query("SELECT COUNT(*) FROM todos WHERE category = :category COLLATE NOCASE")
    suspend fun countByCategory(category: String): Int

    @Query("DELETE FROM todos WHERE category = :category COLLATE NOCASE")
    suspend fun deleteByCategory(category: String)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)
}
