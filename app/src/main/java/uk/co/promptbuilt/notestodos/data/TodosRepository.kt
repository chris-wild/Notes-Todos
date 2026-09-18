package uk.co.promptbuilt.notestodos.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import uk.co.promptbuilt.notestodos.data.db.AppDatabase
import uk.co.promptbuilt.notestodos.data.db.CategoryDefaults
import uk.co.promptbuilt.notestodos.data.db.TodoCategoryDao
import uk.co.promptbuilt.notestodos.data.db.TodoCategoryEntity
import uk.co.promptbuilt.notestodos.data.db.TodoDao
import uk.co.promptbuilt.notestodos.data.db.TodoEntity

class TodosRepository(
    private val db: AppDatabase,
    private val todoDao: TodoDao,
    private val categoryDao: TodoCategoryDao,
) {

    fun observeTodos(): Flow<List<TodoEntity>> = todoDao.observeAll()

    /** Defaults + stored + in-use, deduplicated — see CategoryRules.union. */
    fun observeCategories(): Flow<List<String>> =
        combine(categoryDao.observeAll(), todoDao.observeCategoriesInUse()) { stored, inUse ->
            CategoryRules.union(stored.map { it.name }, inUse)
        }

    suspend fun addTodo(text: String, category: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return todoDao.insert(
            TodoEntity(text = trimmed, category = category, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun setCompleted(id: Long, completed: Boolean) = todoDao.setCompleted(id, completed)

    suspend fun deleteTodo(id: Long) = todoDao.delete(id)

    suspend fun countInCategory(category: String): Int = todoDao.countByCategory(category)

    suspend fun deleteAllInCategory(category: String) = todoDao.deleteByCategory(category)

    /** Returns false when the name is empty or already exists (incl. defaults). */
    suspend fun addCategory(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val normalized = CategoryRules.normalize(trimmed)
        if (CategoryRules.isDefault(trimmed)) return false
        if (categoryDao.getByNormalizedName(normalized) != null) return false
        categoryDao.insert(
            TodoCategoryEntity(
                name = trimmed,
                normalizedName = normalized,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    /**
     * Deletes a non-default category; its todos move to General
     * (matches DELETE /api/todo-categories, backend/server.js:621).
     */
    suspend fun deleteCategory(name: String): Boolean {
        if (CategoryRules.isDefault(name)) return false
        db.withTransaction {
            todoDao.reassignCategory(from = name, to = CategoryDefaults.GENERAL)
            categoryDao.deleteByNormalizedName(CategoryRules.normalize(name))
        }
        return true
    }
}
