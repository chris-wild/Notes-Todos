package uk.co.promptbuilt.notestodos.ai

import kotlin.math.roundToLong

/** One shopping-list line, already split by [IngredientParsing.splitQuantity]. */
data class IngredientLine(val name: String, val quantity: String?)

/**
 * Arithmetic over ingredient lines: amalgamating duplicates ("3 garlic cloves" +
 * "6 garlic cloves" -> "9 garlic cloves") and scaling by a recipe multiplier. Both work
 * on the quantity strings splitQuantity produces — a number ("3", "1.5", "1/2", "1 1/2")
 * with an optional unit word attached or trailing ("200g", "1/2 tsp"). Anything that
 * cannot be parsed is passed through untouched: never guess at someone's shopping list.
 */
object IngredientMath {

    private val AMOUNT = Regex("""^(\d[\d\s./]*?)\s*([a-zA-Z]*)$""")

    /** "— Lasagne —" section headings from multi-recipe extractions never merge or scale. */
    private fun isHeading(line: IngredientLine) = line.quantity == null && line.name.startsWith("—")

    /** ("1 1/2", "tsp") from "1 1/2 tsp"; null when the quantity is missing or not numeric. */
    internal fun parseAmount(quantity: String?): Pair<Double, String>? {
        val match = AMOUNT.find(quantity?.trim() ?: return null) ?: return null
        val value = parseNumber(match.groupValues[1]) ?: return null
        return value to match.groupValues[2].lowercase()
    }

    /** Plain, decimal, fraction, and mixed ("1 1/2") numbers. */
    private fun parseNumber(text: String): Double? {
        val tokens = text.trim().split(Regex("""\s+"""))
        if (tokens.isEmpty()) return null
        var total = 0.0
        for (token in tokens) {
            total += if ("/" in token) {
                val parts = token.split("/", limit = 2)
                val numerator = parts[0].toDoubleOrNull() ?: return null
                val denominator = parts[1].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
                numerator / denominator
            } else {
                token.toDoubleOrNull() ?: return null
            }
        }
        return total
    }

    internal fun formatAmount(value: Double, unit: String): String {
        val number = if (value == value.roundToLong().toDouble()) {
            value.roundToLong().toString()
        } else {
            // Two decimals, trailing zeros trimmed: 0.75, 1.5.
            ((value * 100).roundToLong() / 100.0).toString().trimEnd('0').trimEnd('.')
        }
        return when {
            unit.isEmpty() -> number
            unit.length <= 2 -> "$number$unit" // 200g, 1.5kg — metric symbols read attached
            else -> "$number $unit"            // 2 tsp, 3 cup — word units read spaced
        }
    }

    /**
     * Merge repeated ingredients: lines whose name (case-insensitive) and unit agree are
     * summed into the FIRST occurrence's position. Lines without a numeric quantity, and
     * section headings, stay where they are, untouched.
     */
    fun amalgamate(lines: List<IngredientLine>): List<IngredientLine> {
        val out = mutableListOf<IngredientLine>()
        val positions = mutableMapOf<String, Int>()
        val sums = mutableMapOf<String, Double>()
        for (line in lines) {
            val amount = if (isHeading(line)) null else parseAmount(line.quantity)
            if (amount == null) {
                out.add(line)
                continue
            }
            val key = "${line.name.trim().lowercase()}|${amount.second}"
            val existing = positions[key]
            if (existing == null) {
                positions[key] = out.size
                sums[key] = amount.first
                out.add(line)
            } else {
                val sum = sums.getValue(key) + amount.first
                sums[key] = sum
                out[existing] = out[existing].copy(quantity = formatAmount(sum, amount.second))
            }
        }
        return out
    }

    /** Multiply every numeric quantity; a quantity-less line is annotated "(×N)" instead. */
    fun scale(lines: List<IngredientLine>, multiplier: Int): List<IngredientLine> {
        if (multiplier <= 1) return lines
        return lines.map { line ->
            if (isHeading(line)) return@map line
            val amount = parseAmount(line.quantity)
            when {
                amount != null -> line.copy(quantity = formatAmount(amount.first * multiplier, amount.second))
                line.quantity != null -> line.copy(quantity = "$multiplier× ${line.quantity}")
                else -> line.copy(name = "${line.name} (×$multiplier)")
            }
        }
    }

    /** The todo text: "9 garlic cloves", "200g plain flour", or a bare name. */
    fun format(line: IngredientLine): String =
        line.quantity?.takeIf { it.isNotBlank() }?.let { "$it ${line.name}".trim() } ?: line.name
}
