package uk.co.promptbuilt.notestodos.data

import uk.co.promptbuilt.notestodos.data.db.NoteEntity

// Port of src/utils/sortNotes.js. Pinned and unpinned sections are each sorted
// with the same comparator (see NotesTab.js:29-30 in the web app).
enum class NoteSort(val key: String) {
    DateDesc("dateDesc"),
    DateAsc("dateAsc"),
    AlphaAsc("alphaAsc"),
    AlphaDesc("alphaDesc");

    companion object {
        fun fromKey(key: String?): NoteSort = entries.firstOrNull { it.key == key } ?: DateDesc
    }
}

object NoteSorting {

    fun sort(notes: List<NoteEntity>, sort: NoteSort): List<NoteEntity> =
        notes.sortedWith(comparator(sort))

    private fun comparator(sort: NoteSort) = Comparator<NoteEntity> { a, b ->
        when (sort) {
            NoteSort.AlphaAsc, NoteSort.AlphaDesc -> {
                val cmp = norm(a.title).compareTo(norm(b.title))
                if (cmp != 0) {
                    if (sort == NoteSort.AlphaAsc) cmp else -cmp
                } else {
                    // tiebreaker: most recently updated first
                    time(b).compareTo(time(a))
                }
            }
            NoteSort.DateDesc, NoteSort.DateAsc -> {
                val diff = time(a).compareTo(time(b))
                if (diff != 0) {
                    if (sort == NoteSort.DateAsc) diff else -diff
                } else {
                    // tiebreaker: title
                    norm(a.title).compareTo(norm(b.title))
                }
            }
        }
    }

    private fun norm(s: String?): String = (s ?: "").trim().lowercase()

    private fun time(n: NoteEntity): Long = if (n.updatedAt != 0L) n.updatedAt else n.createdAt
}
