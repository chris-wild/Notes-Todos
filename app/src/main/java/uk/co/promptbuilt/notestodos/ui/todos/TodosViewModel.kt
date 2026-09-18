package uk.co.promptbuilt.notestodos.ui.todos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.promptbuilt.notestodos.data.CategoryRules
import uk.co.promptbuilt.notestodos.data.TodosRepository
import uk.co.promptbuilt.notestodos.data.db.CategoryDefaults
import uk.co.promptbuilt.notestodos.data.db.TodoEntity

data class TodosUiState(
    val categories: List<String> = CategoryRules.DEFAULTS,
    val activeCategory: String = CategoryDefaults.GENERAL,
    val visibleTodos: List<TodoEntity> = emptyList(),
    val query: String = "",
)

class TodosViewModel(
    private val repository: TodosRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val query = MutableStateFlow("")

    // Survives tab switches (nav backstack save/restore recreates this ViewModel).
    private val activeCategory =
        savedState.getStateFlow(KEY_ACTIVE_CATEGORY, CategoryDefaults.GENERAL)

    val uiState: StateFlow<TodosUiState> = combine(
        repository.observeTodos(),
        repository.observeCategories(),
        activeCategory,
        query,
    ) { todos, categories, active, q ->
        // Fall back to General when the active category disappears (e.g. deleted).
        val resolved = categories.firstOrNull {
            CategoryRules.normalize(it) == CategoryRules.normalize(active)
        } ?: CategoryDefaults.GENERAL
        val visible = todos
            .filter {
                val cat = it.category.ifBlank { CategoryDefaults.GENERAL }
                CategoryRules.normalize(cat) == CategoryRules.normalize(resolved)
            }
            .filter { q.isBlank() || it.text.contains(q, ignoreCase = true) }
        TodosUiState(
            categories = categories,
            activeCategory = resolved,
            visibleTodos = visible,
            query = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodosUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun selectCategory(name: String) {
        savedState[KEY_ACTIVE_CATEGORY] = name
    }

    fun addTodo(text: String) {
        val category = uiState.value.activeCategory
        viewModelScope.launch { repository.addTodo(text, category) }
    }

    fun toggle(todo: TodoEntity) {
        viewModelScope.launch { repository.setCompleted(todo.id, !todo.completed) }
    }

    fun deleteTodo(id: Long) {
        viewModelScope.launch { repository.deleteTodo(id) }
    }

    /** Returns false when the name is blank or already exists; selects the new category on success. */
    suspend fun addCategory(name: String): Boolean {
        val added = repository.addCategory(name)
        if (added) savedState[KEY_ACTIVE_CATEGORY] = name.trim()
        return added
    }

    fun deleteCategory(name: String) {
        viewModelScope.launch {
            repository.deleteCategory(name)
            if (CategoryRules.normalize(activeCategory.value) == CategoryRules.normalize(name)) {
                savedState[KEY_ACTIVE_CATEGORY] = CategoryDefaults.GENERAL
            }
        }
    }

    private companion object {
        const val KEY_ACTIVE_CATEGORY = "activeCategory"
    }
}
