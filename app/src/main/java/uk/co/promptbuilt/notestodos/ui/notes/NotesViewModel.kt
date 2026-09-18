package uk.co.promptbuilt.notestodos.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.promptbuilt.notestodos.data.NoteSort
import uk.co.promptbuilt.notestodos.data.NoteSorting
import uk.co.promptbuilt.notestodos.data.NotesRepository
import uk.co.promptbuilt.notestodos.data.SettingsRepository
import uk.co.promptbuilt.notestodos.data.ViewMode
import uk.co.promptbuilt.notestodos.data.db.NoteEntity

data class NotesUiState(
    val pinned: List<NoteEntity> = emptyList(),
    val others: List<NoteEntity> = emptyList(),
    val hasAnyNotes: Boolean = false,
    val query: String = "",
    val sort: NoteSort = NoteSort.DateDesc,
    val viewMode: ViewMode = ViewMode.Grid,
)

class NotesViewModel(
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<NotesUiState> = combine(
        notesRepository.observeNotes(),
        query,
        settingsRepository.noteSort,
        settingsRepository.viewMode,
    ) { notes, q, sort, viewMode ->
        val filtered = if (q.isBlank()) {
            notes
        } else {
            notes.filter {
                it.title.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true)
            }
        }
        NotesUiState(
            pinned = NoteSorting.sort(filtered.filter { it.pinned }, sort),
            others = NoteSorting.sort(filtered.filter { !it.pinned }, sort),
            hasAnyNotes = notes.isNotEmpty(),
            query = q,
            sort = sort,
            viewMode = viewMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun create(title: String, content: String) {
        if (title.isBlank() && content.isBlank()) return
        viewModelScope.launch { notesRepository.create(title, content) }
    }

    fun save(id: Long, title: String, content: String) {
        viewModelScope.launch { notesRepository.update(id, title, content) }
    }

    fun togglePin(note: NoteEntity) {
        viewModelScope.launch { notesRepository.setPinned(note.id, !note.pinned) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { notesRepository.delete(id) }
    }

    fun setSort(sort: NoteSort) {
        viewModelScope.launch { settingsRepository.setNoteSort(sort) }
    }

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { settingsRepository.setViewMode(mode) }
    }
}
