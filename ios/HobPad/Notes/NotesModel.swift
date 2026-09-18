import Foundation
import Observation
import HobPadCore

/// Mirrors the Android NotesViewModel: pinned/others derived from the notes
/// flow + query + persisted sort, all sorting delegated to the shared core.
@Observable @MainActor
final class NotesModel {
    private let core: CoreServices

    private(set) var allNotes: [NoteEntity] = []
    var query = ""
    private(set) var sort: NoteSort = .dateDesc
    private(set) var viewMode: ViewMode = .grid

    init(core: CoreServices) {
        self.core = core
    }

    var filtered: [NoteEntity] {
        query.isEmpty ? allNotes : allNotes.filter {
            $0.title.localizedCaseInsensitiveContains(query) ||
            $0.content.localizedCaseInsensitiveContains(query)
        }
    }

    var pinned: [NoteEntity] {
        NoteSorting.shared.sort(notes: filtered.filter(\.pinned), sort: sort)
    }

    var others: [NoteEntity] {
        NoteSorting.shared.sort(notes: filtered.filter { !$0.pinned }, sort: sort)
    }

    func observeNotes() async {
        for await notes in core.notesRepository.observeNotes() {
            allNotes = notes
        }
    }

    func observeSettings() async {
        for await value in core.settingsRepository.noteSort {
            sort = value
        }
    }

    func observeViewMode() async {
        for await value in core.settingsRepository.viewMode {
            viewMode = value
        }
    }

    func setSort(_ sort: NoteSort) {
        Task { try? await core.settingsRepository.setNoteSort(sort: sort) }
    }

    func toggleViewMode() {
        let next: ViewMode = viewMode == .grid ? .list : .grid
        Task { try? await core.settingsRepository.setViewMode(mode: next) }
    }

    func create(title: String, content: String) {
        Task { try? await core.notesRepository.create(title: title, content: content) }
    }

    func save(id: Int64, title: String, content: String) {
        Task { try? await core.notesRepository.update(id: id, title: title, content: content) }
    }

    func togglePin(_ note: NoteEntity) {
        Task { try? await core.notesRepository.setPinned(id: note.id, pinned: !note.pinned) }
    }

    func delete(_ note: NoteEntity) {
        Task { try? await core.notesRepository.delete(id: note.id) }
    }
}
