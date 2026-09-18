package uk.co.promptbuilt.notestodos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryRulesTest {

    @Test
    fun `defaults always present and first, even with no other categories`() {
        assertEquals(listOf("General", "Shopping List"), CategoryRules.union(emptyList(), emptyList()))
    }

    @Test
    fun `union merges stored and in-use, others sorted alphabetically`() {
        val result = CategoryRules.union(
            stored = listOf("Work", "DIY"),
            inUse = listOf("Holiday", "Work"),
        )
        assertEquals(listOf("General", "Shopping List", "DIY", "Holiday", "Work"), result)
    }

    @Test
    fun `dedupes case-insensitively with first-seen spelling winning`() {
        val result = CategoryRules.union(
            stored = listOf("shopping list", "Work"),
            inUse = listOf("WORK"),
        )
        // "shopping list" collapses into the default; "WORK" into stored "Work"
        assertEquals(listOf("General", "Shopping List", "Work"), result)
    }

    @Test
    fun `blank names are ignored`() {
        val result = CategoryRules.union(stored = listOf("  ", ""), inUse = listOf("\t"))
        assertEquals(listOf("General", "Shopping List"), result)
    }

    @Test
    fun `isDefault matches ignoring case and whitespace`() {
        assertTrue(CategoryRules.isDefault("general"))
        assertTrue(CategoryRules.isDefault(" SHOPPING LIST "))
        assertFalse(CategoryRules.isDefault("Work"))
    }
}
