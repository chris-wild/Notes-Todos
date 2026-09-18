package uk.co.promptbuilt.notestodos.data

import kotlinx.coroutines.flow.Flow
import uk.co.promptbuilt.notestodos.data.db.NoteDao
import uk.co.promptbuilt.notestodos.data.db.NoteEntity

class NotesRepository(private val noteDao: NoteDao) {

    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    suspend fun create(title: String, content: String): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(
            NoteEntity(
                title = title,
                content = content,
                sortOrder = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun update(id: Long, title: String, content: String) {
        val note = noteDao.getById(id) ?: return
        noteDao.update(note.copy(title = title, content = content, updatedAt = System.currentTimeMillis()))
    }

    // Matches web togglePin (App.js:279): flips pinned and stamps sortOrder with "now"
    // so the note surfaces at the top of its section. Deliberately does NOT touch
    // updatedAt, so pinning doesn't reshuffle date-sorted lists.
    suspend fun setPinned(id: Long, pinned: Boolean) {
        val note = noteDao.getById(id) ?: return
        noteDao.update(note.copy(pinned = pinned, sortOrder = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = noteDao.delete(id)
}
