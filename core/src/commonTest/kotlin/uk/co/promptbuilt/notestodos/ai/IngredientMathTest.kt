package uk.co.promptbuilt.notestodos.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class IngredientMathTest {

    private fun split(text: String): IngredientLine {
        val (name, quantity) = IngredientParsing.splitQuantity(text)
        return IngredientLine(name, quantity)
    }

    @Test
    fun amalgamates_repeated_ingredients() {
        // Chris's example: the sauce needs 3, the main body 6 -> one line of 9.
        val merged = IngredientMath.amalgamate(
            listOf(split("3 garlic cloves"), split("200g plain flour"), split("6 garlic cloves")),
        )
        assertEquals(
            listOf("9 garlic cloves", "200g plain flour"),
            merged.map { IngredientMath.format(it) },
        )
    }

    @Test
    fun merges_units_and_fractions() {
        val merged = IngredientMath.amalgamate(
            listOf(split("200g butter"), split("1/2 tsp salt"), split("50g butter"), split("1 1/2 tsp salt")),
        )
        assertEquals(
            listOf("250g butter", "2 tsp salt"),
            merged.map { IngredientMath.format(it) },
        )
    }

    @Test
    fun different_units_never_merge() {
        val merged = IngredientMath.amalgamate(listOf(split("200g flour"), split("2 tbsp flour")))
        assertEquals(2, merged.size)
    }

    @Test
    fun unquantified_lines_and_headings_pass_through() {
        val lines = listOf(
            IngredientLine("— Lasagne —", null),
            split("a handful of parsley"),
            split("3 eggs"),
            split("3 eggs"),
        )
        assertEquals(
            listOf("— Lasagne —", "a handful of parsley", "6 eggs"),
            IngredientMath.amalgamate(lines).map { IngredientMath.format(it) },
        )
    }

    @Test
    fun scales_by_the_multiplier() {
        val scaled = IngredientMath.scale(
            listOf(split("3 garlic cloves"), split("200g plain flour"), split("1/2 tsp salt"), split("a handful of parsley")),
            3,
        )
        assertEquals(
            listOf("9 garlic cloves", "600g plain flour", "1.5 tsp salt", "a handful of parsley (×3)"),
            scaled.map { IngredientMath.format(it) },
        )
    }

    @Test
    fun multiplier_of_one_changes_nothing() {
        val lines = listOf(split("3 eggs"))
        assertEquals(lines, IngredientMath.scale(lines, 1))
    }
}
