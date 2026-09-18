package uk.co.promptbuilt.notestodos.ui.todos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import uk.co.promptbuilt.notestodos.NotesTodosApp
import uk.co.promptbuilt.notestodos.data.CategoryRules
import uk.co.promptbuilt.notestodos.data.db.TodoEntity

@Composable
fun TodosScreen() {
    val app = LocalContext.current.applicationContext as NotesTodosApp
    val viewModel: TodosViewModel = viewModel {
        TodosViewModel(app.todosRepository, createSavedStateHandle())
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var addingCategory by rememberSaveable { mutableStateOf(false) }
    var categoryDraft by rememberSaveable { mutableStateOf("") }
    var categoryError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteCategory by remember { mutableStateOf<String?>(null) }

    fun submitCategory() {
        val name = categoryDraft.trim()
        if (name.isEmpty()) {
            categoryError = "Enter a category name"
            return
        }
        scope.launch {
            if (viewModel.addCategory(name)) {
                addingCategory = false
                categoryDraft = ""
                categoryError = null
            } else {
                categoryError = "Category already exists"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Search todos") },
            singleLine = true,
        )

        // Category tab strip (port of TodosTab.js:31-66): active non-default
        // categories carry a remove affordance; "+" opens the add row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.categories, key = { it }) { category ->
                    val active = CategoryRules.normalize(category) ==
                        CategoryRules.normalize(state.activeCategory)
                    FilterChip(
                        selected = active,
                        onClick = { viewModel.selectCategory(category) },
                        label = { Text(category) },
                        trailingIcon = if (active && !CategoryRules.isDefault(category)) {
                            {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove category $category",
                                    modifier = Modifier.clickable { confirmDeleteCategory = category },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            IconButton(onClick = {
                categoryError = null
                addingCategory = !addingCategory
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add category")
            }
        }

        if (addingCategory) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = categoryDraft,
                    onValueChange = { categoryDraft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter category name") },
                    singleLine = true,
                    isError = categoryError != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitCategory() }),
                )
                TextButton(onClick = ::submitCategory) { Text("Add") }
                TextButton(onClick = {
                    addingCategory = false
                    categoryDraft = ""
                    categoryError = null
                }) { Text("Cancel") }
            }
            categoryError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        AddTodoRow(
            category = state.activeCategory,
            onAdd = viewModel::addTodo,
        )

        if (state.visibleTodos.isEmpty()) {
            Text(
                text = if (state.query.isBlank()) {
                    "Nothing in ${state.activeCategory} yet — add one above."
                } else {
                    "No todos match your search."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.visibleTodos, key = { it.id }) { todo ->
                    TodoRow(
                        todo = todo,
                        onToggle = { viewModel.toggle(todo) },
                        onDelete = { viewModel.deleteTodo(todo.id) },
                    )
                }
            }
        }
    }

    confirmDeleteCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { confirmDeleteCategory = null },
            title = { Text("Delete category?") },
            text = { Text("Todos in \"$category\" will be moved to General.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCategory(category)
                    confirmDeleteCategory = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCategory = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AddTodoRow(category: String, onAdd: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (draft.isNotBlank()) {
            onAdd(draft)
            draft = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Add to $category…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        TextButton(onClick = ::submit) { Text("Add") }
    }
}

@Composable
private fun TodoRow(todo: TodoEntity, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = todo.completed, onCheckedChange = { onToggle() })
        Text(
            text = todo.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
            color = if (todo.completed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete todo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
