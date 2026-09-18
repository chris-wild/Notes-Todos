package uk.co.promptbuilt.notestodos.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.promptbuilt.notestodos.NotesTodosApp
import uk.co.promptbuilt.notestodos.data.NoteSort
import uk.co.promptbuilt.notestodos.data.ViewMode
import uk.co.promptbuilt.notestodos.data.db.NoteEntity
import uk.co.promptbuilt.notestodos.ui.common.LinkifiedText

@Composable
fun NotesScreen() {
    val app = LocalContext.current.applicationContext as NotesTodosApp
    val viewModel: NotesViewModel = viewModel {
        NotesViewModel(app.notesRepository, app.settingsRepository)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<NoteEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        NotesToolbar(
            query = state.query,
            sort = state.sort,
            viewMode = state.viewMode,
            onQueryChange = viewModel::setQuery,
            onSortChange = viewModel::setSort,
            onViewModeChange = viewModel::setViewMode,
        )

        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (state.viewMode == ViewMode.List) 1 else 2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                NoteComposer(onCreate = viewModel::create)
            }

            if (state.pinned.isEmpty() && state.others.isEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        text = if (state.query.isBlank()) {
                            "No notes yet — take one above to get started."
                        } else {
                            "No notes match your search."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // Section labels only appear when pinned notes exist (matches NotesTab.js:132-147)
            if (state.pinned.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) { SectionLabel("Pinned") }
                items(state.pinned, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onOpen = { editingNote = note },
                        onTogglePin = { viewModel.togglePin(note) },
                        onDelete = { deleteTarget = note },
                    )
                }
                if (state.others.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) { SectionLabel("Others") }
                }
            }
            items(state.others, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onOpen = { editingNote = note },
                    onTogglePin = { viewModel.togglePin(note) },
                    onDelete = { deleteTarget = note },
                )
            }
        }
    }

    editingNote?.let { note ->
        NoteEditorDialog(
            note = note,
            onSave = { title, content ->
                viewModel.save(note.id, title, content)
                editingNote = null
            },
            onClose = { editingNote = null },
            onDelete = { deleteTarget = note },
        )
    }

    deleteTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete note?") },
            text = { Text(note.title.ifBlank { "This note" } + " will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(note.id)
                    deleteTarget = null
                    if (editingNote?.id == note.id) editingNote = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NotesToolbar(
    query: String,
    sort: NoteSort,
    viewMode: ViewMode,
    onQueryChange: (String) -> Unit,
    onSortChange: (NoteSort) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search notes") },
            singleLine = true,
        )

        var sortMenuOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort notes")
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                sortOptions.forEach { (option, label) ->
                    DropdownMenuItem(
                        text = { Text(if (option == sort) "$label  ✓" else label) },
                        onClick = {
                            onSortChange(option)
                            sortMenuOpen = false
                        },
                    )
                }
            }
        }

        IconButton(onClick = {
            onViewModeChange(if (viewMode == ViewMode.Grid) ViewMode.List else ViewMode.Grid)
        }) {
            if (viewMode == ViewMode.Grid) {
                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Switch to list view")
            } else {
                Icon(Icons.Filled.GridView, contentDescription = "Switch to grid view")
            }
        }
    }
}

private val sortOptions = listOf(
    NoteSort.DateDesc to "Date ↓",
    NoteSort.DateAsc to "Date ↑",
    NoteSort.AlphaAsc to "A → Z",
    NoteSort.AlphaDesc to "Z → A",
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun NoteComposer(onCreate: (String, String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }

    fun reset() {
        open = false
        title = ""
        content = ""
    }

    Card(
        onClick = { if (!open) open = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!open) {
            Text(
                text = "Take a note…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        } else {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    placeholder = { Text("Take a note…") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = ::reset) { Text("Close") }
                    TextButton(onClick = {
                        onCreate(title, content)
                        reset()
                    }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteEntity,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onOpen) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePin) {
                    if (note.pinned) {
                        Icon(Icons.Filled.PushPin, contentDescription = "Unpin note")
                    } else {
                        Icon(Icons.Outlined.PushPin, contentDescription = "Pin note")
                    }
                }
            }
            // 220-char snippet, matching NoteCard.js:28-31
            val snippet = note.content.take(220) + if (note.content.length > 220) "…" else ""
            LinkifiedText(text = snippet)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteEditorDialog(
    note: NoteEntity,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
    var content by rememberSaveable(note.id) { mutableStateOf(note.content) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDelete) { Text("Delete") }
                    TextButton(onClick = onClose) { Text("Close") }
                    TextButton(onClick = { onSave(title, content) }) { Text("Save") }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp),
                    placeholder = { Text("Take a note…") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
    }
}
