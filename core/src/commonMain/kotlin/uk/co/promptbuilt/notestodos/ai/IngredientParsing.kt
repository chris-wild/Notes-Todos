package uk.co.promptbuilt.notestodos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Pure port of the response parsing and quantity splitting in backend/server.js
 * (claudeExtractIngredientsFromPdf/-Text and the regex at server.js:1128).
 */
object IngredientParsing {

    // ^([\d\s\.\/]+(?:g|kg|ml|l|tsp|tbsp|cup|pinch|piece|slice|clove)?\s+)(.+)$ (case-insensitive)
    private val QUANTITY_REGEX = Regex(
        """^([\d\s./]+(?:g|kg|ml|l|tsp|tbsp|cup|pinch|piece|slice|clove)?\s+)(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Splits "200g plain flour" into name="plain flour", quantity="200g". */
    fun splitQuantity(ingredient: String): Pair<String, String?> {
        val match = QUANTITY_REGEX.find(ingredient) ?: return ingredient to null
        return match.groupValues[2].trim() to match.groupValues[1].trim()
    }

    /**
     * Parses the model output (with the "{" prefill already restored by the caller).
     * Accepts {"ingredients": [...]} or the multi-recipe shape, which is flattened
     * WITHOUT section headings: a shopping list wants "9 garlic cloves", not
     * "— Hollandaise sauce —" as a purchasable item (Chris's bug report, Sept 30) —
     * amalgamation merges duplicates across the sections instead.
     */
    fun parseResponse(outText: String): List<String> {
        val parsed = tryParse(outText)
            ?: tryParse(extractBraces(outText))
            ?: throw IllegalStateException("Claude extract failed: could not parse ingredients JSON")

        (parsed["ingredients"] as? JsonArray)?.let { array ->
            return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                .filter { it.isNotEmpty() }
                .take(200)
        }

        (parsed["recipes"] as? JsonArray)?.let { array ->
            val out = mutableListOf<String>()
            for (element in array) {
                val recipe = element as? JsonObject ?: continue
                val ingredients = (recipe["ingredients"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                out.addAll(ingredients)
            }
            return out.take(400)
        }

        // Valid JSON with neither key: the model looked and found no recipe (it answers
        // {"error": "No recipe found…"} for, say, a photo of flowers attached by mistake).
        // That page simply contributes nothing — the caller decides whether an entirely
        // empty extraction is an error.
        return emptyList()
    }

    private fun tryParse(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun extractBraces(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }
}
