package uk.co.promptbuilt.notestodos.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IngredientParsingTest {

    @Test
    fun parsesSingleRecipeShape() {
        val out = IngredientParsing.parseResponse("""{"ingredients": ["200g flour", " 2 eggs ", ""]}""")
        assertEquals(listOf("200g flour", "2 eggs"), out)
    }

    @Test
    fun parsesMultiRecipeShapeWithHeadings() {
        val out = IngredientParsing.parseResponse(
            """{"recipes": [
                {"name": "Pancakes", "ingredients": ["100g flour", "1 egg"]},
                {"name": "", "ingredients": []},
                {"name": "Syrup", "ingredients": ["50ml maple syrup"]}
            ]}""",
        )
        assertEquals(
            listOf("— Pancakes —", "100g flour", "1 egg", "— Syrup —", "50ml maple syrup"),
            out,
        )
    }

    @Test
    fun recoversJsonWrappedInProse() {
        val out = IngredientParsing.parseResponse(
            """Here you go: {"ingredients": ["1 tsp salt"]} hope that helps""",
        )
        assertEquals(listOf("1 tsp salt"), out)
    }

    @Test
    fun throwsOnUnparseableOutput() {
        assertFailsWith<IllegalStateException> {
            IngredientParsing.parseResponse("no json here")
        }
    }

    @Test
    fun capsSingleRecipeListAt200() {
        val many = (1..300).joinToString(",") { "\"item $it\"" }
        val out = IngredientParsing.parseResponse("""{"ingredients": [$many]}""")
        assertEquals(200, out.size)
    }

    @Test
    fun splitsQuantityWithUnitFromName() {
        val (name, qty) = IngredientParsing.splitQuantity("200g plain flour")
        assertEquals("plain flour", name)
        assertEquals("200g", qty)
    }

    @Test
    fun splitsFractionQuantities() {
        val (name, qty) = IngredientParsing.splitQuantity("1/2 tsp baking powder")
        assertEquals("baking powder", name)
        assertEquals("1/2 tsp", qty)
    }

    @Test
    fun leavesUnquantifiedIngredientsUntouched() {
        val (name, qty) = IngredientParsing.splitQuantity("salt to taste")
        assertEquals("salt to taste", name)
        assertNull(qty)
    }

    @Test
    fun headingLinesAreNotSplit() {
        val (name, qty) = IngredientParsing.splitQuantity("— Pancakes —")
        assertEquals("— Pancakes —", name)
        assertNull(qty)
    }
}
