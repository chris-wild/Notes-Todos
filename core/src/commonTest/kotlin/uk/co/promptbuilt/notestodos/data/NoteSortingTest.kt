package uk.co.promptbuilt.notestodos.data

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.promptbuilt.notestodos.data.db.NoteEntity

class NoteSortingTest {

    private fun note(id: Long, title: String, updatedAt: Long, createdAt: Long = updatedAt) =
        NoteEntity(id = id, title = title, content = "", createdAt = createdAt, updatedAt = updatedAt)

    private val shopping = note(1, "Shopping", updatedAt = 3000)
    private val alpha = note(2, "alpha note", updatedAt = 1000)
    private val zebra = note(3, "Zebra", updatedAt = 2000)

    @Test
    fun `dateDesc puts most recently updated first`() {
        val sorted = NoteSorting.sort(listOf(alpha, zebra, shopping), NoteSort.DateDesc)
        assertEquals(listOf(1L, 3L, 2L), sorted.map { it.id })
    }

    @Test
    fun `dateAsc puts oldest first`() {
        val sorted = NoteSorting.sort(listOf(shopping, alpha, zebra), NoteSort.DateAsc)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `alpha sorting is case-insensitive and trims`() {
        val padded = note(4, "  beta  ", updatedAt = 500)
        val sorted = NoteSorting.sort(listOf(zebra, padded, shopping, alpha), NoteSort.AlphaAsc)
        assertEquals(listOf("alpha note", "  beta  ", "Shopping", "Zebra"), sorted.map { it.title })
    }

    @Test
    fun `alphaDesc reverses title order`() {
        val sorted = NoteSorting.sort(listOf(alpha, zebra, shopping), NoteSort.AlphaDesc)
        assertEquals(listOf("Zebra", "Shopping", "alpha note"), sorted.map { it.title })
    }

    @Test
    fun `alpha tiebreaker is most recently updated first`() {
        val older = note(5, "Same", updatedAt = 100)
        val newer = note(6, "same", updatedAt = 200)
        val sorted = NoteSorting.sort(listOf(older, newer), NoteSort.AlphaAsc)
        assertEquals(listOf(6L, 5L), sorted.map { it.id })
    }

    @Test
    fun `date tiebreaker is title ascending`() {
        val b = note(7, "Bravo", updatedAt = 100)
        val a = note(8, "Alpha", updatedAt = 100)
        val sorted = NoteSorting.sort(listOf(b, a), NoteSort.DateDesc)
        assertEquals(listOf(8L, 7L), sorted.map { it.id })
    }

    @Test
    fun `falls back to createdAt when updatedAt is unset`() {
        val legacy = note(9, "Legacy", updatedAt = 0, createdAt = 5000)
        val sorted = NoteSorting.sort(listOf(shopping, legacy), NoteSort.DateDesc)
        assertEquals(listOf(9L, 1L), sorted.map { it.id })
    }
}
