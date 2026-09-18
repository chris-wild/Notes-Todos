package uk.co.promptbuilt.notestodos.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.promptbuilt.notestodos.data.db.IngredientEntity
import uk.co.promptbuilt.notestodos.data.db.NoteEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity
import uk.co.promptbuilt.notestodos.data.db.TodoCategoryEntity
import uk.co.promptbuilt.notestodos.data.db.TodoEntity

class BackupCodecTest {

    private val data = BackupData(
        notes = listOf(
            NoteEntity(1, "Shopping", "milk\neggs", pinned = true, sortOrder = 1741270000000, createdAt = 1741270000000, updatedAt = 1741280000000),
        ),
        todos = listOf(
            TodoEntity(1, "Buy milk", completed = true, category = "General", createdAt = 1741270000000),
        ),
        categories = listOf(
            TodoCategoryEntity(1, "Holiday", "holiday", createdAt = 1741270000000),
        ),
        recipes = listOf(
            RecipeEntity(
                1, "Pancakes", "family favourite",
                pdfFileName = "abc.pdf", pdfOriginalName = "pancakes.pdf",
                ingredientTodoCategory = "Pancakes", ingredientTodosCount = 5,
                ingredientTodosCreatedAt = 1741290000000,
                createdAt = 1741270000000, updatedAt = 1741280000000,
            ),
        ),
        ingredients = listOf(
            IngredientEntity(1, recipeId = 1, name = "plain flour", quantity = "200g", createdAt = 1741270000000),
        ),
        pdfs = mapOf("abc.pdf" to byteArrayOf(0x25, 0x50, 0x44, 0x46)),
    )

    @Test
    fun `round trip preserves everything`() {
        val out = ByteArrayOutputStream()
        BackupCodec.write(data, out)
        val back = BackupCodec.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(data.notes, back.notes)
        assertEquals(data.todos, back.todos)
        assertEquals(data.categories, back.categories)
        assertEquals(data.recipes, back.recipes)
        assertEquals(data.ingredients, back.ingredients)
        assertEquals(data.pdfs.keys, back.pdfs.keys)
        assertArrayEquals(data.pdfs["abc.pdf"], back.pdfs["abc.pdf"])
    }

    @Test
    fun `reads S3-style records with numeric booleans and pathed pdf filenames`() {
        // Shapes as the web backend wrote them, including user_id noise,
        // completed as 0/1, and pdf_filename as a full S3 key.
        val zip = zipOf(
            "data/todos.json" to
                """[{"id": 7, "text": "Buy milk", "completed": 1, "category": "General",
                    "user_id": 1, "created_at": "2026-03-06T09:00:00.000Z"}]""",
            "data/recipes.json" to
                """[{"id": 3, "name": "Pancakes", "notes": "",
                    "pdf_filename": "recipes/1/abc-pancakes.pdf", "pdf_original_name": "pancakes.pdf",
                    "ingredient_todo_category": null, "ingredient_todos_count": null,
                    "ingredient_todos_created_at": null, "user_id": 1,
                    "created_at": "2026-03-06T09:00:00.000Z", "updated_at": "2026-03-07T09:00:00.000Z"}]""",
            "recipes/abc-pancakes.pdf" to "%PDF",
        )

        val back = BackupCodec.read(ByteArrayInputStream(zip))

        val todo = back.todos.single()
        assertEquals(7, todo.id)
        assertTrue(todo.completed)
        assertEquals(java.time.Instant.parse("2026-03-06T09:00:00Z").toEpochMilli(), todo.createdAt)

        val recipe = back.recipes.single()
        assertEquals("abc-pancakes.pdf", recipe.pdfFileName)
        assertNull(recipe.ingredientTodoCategory)
        assertNull(recipe.ingredientTodosCount)
        assertNull(recipe.ingredientTodosCreatedAt)
        assertTrue(back.pdfs.containsKey("abc-pancakes.pdf"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
