package uk.co.promptbuilt.notestodos.data

import uk.co.promptbuilt.notestodos.data.db.CategoryDefaults

// Port of the category behavior in backend/server.js:577-621:
// the visible category list is the union of defaults + stored + in-use,
// defaults are always present and undeletable, and matching is by
// trimmed/lowercased name.
object CategoryRules {
    val DEFAULTS = listOf(CategoryDefaults.GENERAL, CategoryDefaults.SHOPPING_LIST)

    fun normalize(name: String): String = name.trim().lowercase()

    fun isDefault(name: String): Boolean =
        DEFAULTS.any { normalize(it) == normalize(name) }

    /**
     * Union of default, stored, and in-use category names, deduplicated by
     * normalized name. Defaults keep their fixed order first; the rest follow
     * alphabetically (case-insensitive). First-seen spelling wins.
     */
    fun union(stored: List<String>, inUse: List<String>): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (name in DEFAULTS + stored + inUse) {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) seen.putIfAbsent(normalize(trimmed), trimmed)
        }
        val defaults = DEFAULTS.map { seen.remove(normalize(it))!! }
        return defaults + seen.values.sortedBy { normalize(it) }
    }
}
