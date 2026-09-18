package uk.co.promptbuilt.notestodos.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    fun roundTripPreservesEverything() {
        val bytes = BackupCodec.write(data)
        val back = BackupCodec.read(bytes)

        assertEquals(data.notes, back.notes)
        assertEquals(data.todos, back.todos)
        assertEquals(data.categories, back.categories)
        assertEquals(data.recipes, back.recipes)
        assertEquals(data.ingredients, back.ingredients)
        assertEquals(data.pdfs.keys, back.pdfs.keys)
        assertContentEquals(data.pdfs["abc.pdf"], back.pdfs["abc.pdf"])
    }

    @Test
    fun readsS3StyleRecordsWithNumericBooleansAndPathedPdfFilenames() {
        // Shapes as the retired web backend wrote them: user_id noise,
        // completed as 0/1, pdf_filename as a full S3 key, ISO dates with millis.
        val zip = ZipCodec.write(
            linkedMapOf(
                "data/todos.json" to
                    """[{"id": 7, "text": "Buy milk", "completed": 1, "category": "General",
                        "user_id": 1, "created_at": "2026-03-06T09:00:00.000Z"}]""".encodeToByteArray(),
                "data/recipes.json" to
                    """[{"id": 3, "name": "Pancakes", "notes": "",
                        "pdf_filename": "recipes/1/abc-pancakes.pdf", "pdf_original_name": "pancakes.pdf",
                        "ingredient_todo_category": null, "ingredient_todos_count": null,
                        "ingredient_todos_created_at": null, "user_id": 1,
                        "created_at": "2026-03-06T09:00:00.000Z", "updated_at": "2026-03-07T09:00:00.000Z"}]""".encodeToByteArray(),
                "recipes/abc-pancakes.pdf" to "%PDF".encodeToByteArray(),
            ),
        )

        val back = BackupCodec.read(zip)

        val todo = back.todos.single()
        assertEquals(7, todo.id)
        assertTrue(todo.completed)
        assertEquals(1772787600000, todo.createdAt) // 2026-03-06T09:00:00Z

        val recipe = back.recipes.single()
        assertEquals("abc-pancakes.pdf", recipe.pdfFileName)
        assertNull(recipe.ingredientTodoCategory)
        assertNull(recipe.ingredientTodosCount)
        assertNull(recipe.ingredientTodosCreatedAt)
        assertTrue(back.pdfs.containsKey("abc-pancakes.pdf"))
    }

    @Test
    fun timestampsAcceptWholeSecondsMillisAndOffsets() {
        val zip = ZipCodec.write(
            linkedMapOf(
                "data/notes.json" to
                    """[
                        {"id": 1, "title": "a", "content": "", "pinned": false, "sort_order": 0,
                         "created_at": "2026-03-06T09:00:00Z", "updated_at": "2026-03-06T09:00:00.250Z"},
                        {"id": 2, "title": "b", "content": "", "pinned": false, "sort_order": 0,
                         "created_at": "2026-03-06T10:00:00+01:00", "updated_at": 1772787600000}
                    ]""".encodeToByteArray(),
            ),
        )
        val back = BackupCodec.read(zip)
        assertEquals(1772787600000, back.notes[0].createdAt)
        assertEquals(1772787600250, back.notes[0].updatedAt)
        assertEquals(1772787600000, back.notes[1].createdAt) // +01:00 == 09:00Z
        assertEquals(1772787600000, back.notes[1].updatedAt)
    }
}
