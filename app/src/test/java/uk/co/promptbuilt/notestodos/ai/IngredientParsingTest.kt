package uk.co.promptbuilt.notestodos.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientParsingTest {

    @Test
    fun `parses single-recipe shape`() {
        val out = IngredientParsing.parseResponse("""{"ingredients": ["200g flour", " 2 eggs ", ""]}""")
        assertEquals(listOf("200g flour", "2 eggs"), out)
    }

    @Test
    fun `parses multi-recipe shape with headings`() {
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
    fun `recovers JSON wrapped in prose via brace extraction`() {
        val out = IngredientParsing.parseResponse(
            """Here you go: {"ingredients": ["1 tsp salt"]} hope that helps""",
        )
        assertEquals(listOf("1 tsp salt"), out)
    }

    @Test(expected = IllegalStateException::class)
    fun `throws on unparseable output`() {
        IngredientParsing.parseResponse("no json here")
    }

    @Test
    fun `caps single-recipe list at 200`() {
        val many = (1..300).joinToString(",") { "\"item $it\"" }
        val out = IngredientParsing.parseResponse("""{"ingredients": [$many]}""")
        assertEquals(200, out.size)
    }

    @Test
    fun `splits quantity with unit from name`() {
        val (name, qty) = IngredientParsing.splitQuantity("200g plain flour")
        assertEquals("plain flour", name)
        assertEquals("200g", qty)
    }

    @Test
    fun `splits fraction quantities`() {
        val (name, qty) = IngredientParsing.splitQuantity("1/2 tsp baking powder")
        assertEquals("baking powder", name)
        assertEquals("1/2 tsp", qty)
    }

    @Test
    fun `leaves unquantified ingredients untouched`() {
        val (name, qty) = IngredientParsing.splitQuantity("salt to taste")
        assertEquals("salt to taste", name)
        assertNull(qty)
    }

    @Test
    fun `heading lines are not split`() {
        val (name, qty) = IngredientParsing.splitQuantity("— Pancakes —")
        assertEquals("— Pancakes —", name)
        assertNull(qty)
    }
}
