package uk.co.promptbuilt.notestodos.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CategoryRulesTest {

    @Test
    fun defaultsAlwaysPresentAndFirst() {
        assertEquals(listOf("General", "Shopping List"), CategoryRules.union(emptyList(), emptyList()))
    }

    @Test
    fun unionMergesStoredAndInUseOthersSorted() {
        val result = CategoryRules.union(
            stored = listOf("Work", "DIY"),
            inUse = listOf("Holiday", "Work"),
        )
        assertEquals(listOf("General", "Shopping List", "DIY", "Holiday", "Work"), result)
    }

    @Test
    fun dedupesCaseInsensitivelyFirstSeenSpellingWins() {
        val result = CategoryRules.union(
            stored = listOf("shopping list", "Work"),
            inUse = listOf("WORK"),
        )
        assertEquals(listOf("General", "Shopping List", "Work"), result)
    }

    @Test
    fun blankNamesAreIgnored() {
        val result = CategoryRules.union(stored = listOf("  ", ""), inUse = listOf("\t"))
        assertEquals(listOf("General", "Shopping List"), result)
    }

    @Test
    fun isDefaultMatchesIgnoringCaseAndWhitespace() {
        assertTrue(CategoryRules.isDefault("general"))
        assertTrue(CategoryRules.isDefault(" SHOPPING LIST "))
        assertFalse(CategoryRules.isDefault("Work"))
    }
}
