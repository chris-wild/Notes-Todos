package uk.co.promptbuilt.notestodos.backup

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import uk.co.promptbuilt.notestodos.data.db.IngredientEntity
import uk.co.promptbuilt.notestodos.data.db.NoteEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity
import uk.co.promptbuilt.notestodos.data.db.TodoCategoryEntity
import uk.co.promptbuilt.notestodos.data.db.TodoEntity

data class BackupData(
    val notes: List<NoteEntity> = emptyList(),
    val todos: List<TodoEntity> = emptyList(),
    val categories: List<TodoCategoryEntity> = emptyList(),
    val recipes: List<RecipeEntity> = emptyList(),
    val ingredients: List<IngredientEntity> = emptyList(),
    /** basename -> bytes, referenced by RecipeEntity.pdfFileName */
    val pdfs: Map<String, ByteArray> = emptyMap(),
)

/**
 * Zip backup format, deliberately identical in shape to the web backend's S3
 * collections (backend/datastore.js) minus user_id:
 *   data/notes.json, data/todos.json, data/todo-categories.json,
 *   data/recipes.json, data/ingredients.json, recipes/<file>.pdf
 * Snake_case field names and ISO-8601 timestamps, so a filtered copy of the raw
 * S3 export imports directly. Reading is tolerant: `completed` may be a boolean
 * or 0/1, and pdf_filename may be an S3-style path (recipes/<userId>/<name>.pdf),
 * which collapses to its basename.
 */
object BackupCodec {

    fun write(data: BackupData, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("data/notes.json", notesJson(data.notes).toString().toByteArray())
            entry("data/todos.json", todosJson(data.todos).toString().toByteArray())
            entry("data/todo-categories.json", categoriesJson(data.categories).toString().toByteArray())
            entry("data/recipes.json", recipesJson(data.recipes).toString().toByteArray())
            entry("data/ingredients.json", ingredientsJson(data.ingredients).toString().toByteArray())
            for ((name, bytes) in data.pdfs) entry("recipes/$name", bytes)
        }
    }

    fun read(input: InputStream): BackupData {
        var notes: List<NoteEntity> = emptyList()
        var todos: List<TodoEntity> = emptyList()
        var categories: List<TodoCategoryEntity> = emptyList()
        var recipes: List<RecipeEntity> = emptyList()
        var ingredients: List<IngredientEntity> = emptyList()
        val pdfs = mutableMapOf<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "data/notes.json" -> notes = parseNotes(zip.readBytes())
                    name == "data/todos.json" -> todos = parseTodos(zip.readBytes())
                    name == "data/todo-categories.json" -> categories = parseCategories(zip.readBytes())
                    name == "data/recipes.json" -> recipes = parseRecipes(zip.readBytes())
                    name == "data/ingredients.json" -> ingredients = parseIngredients(zip.readBytes())
                    name.startsWith("recipes/") && !entry.isDirectory ->
                        pdfs[name.substringAfterLast('/')] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return BackupData(notes, todos, categories, recipes, ingredients, pdfs)
    }

    // ---- export ----

    private fun notesJson(notes: List<NoteEntity>) = buildJsonArray {
        for (n in notes) add(
            buildJsonObject {
                put("id", n.id)
                put("title", n.title)
                put("content", n.content)
                put("pinned", n.pinned)
                put("sort_order", n.sortOrder)
                put("created_at", iso(n.createdAt))
                put("updated_at", iso(n.updatedAt))
            },
        )
    }

    private fun todosJson(todos: List<TodoEntity>) = buildJsonArray {
        for (t in todos) add(
            buildJsonObject {
                put("id", t.id)
                put("text", t.text)
                put("completed", t.completed)
                put("category", t.category)
                put("created_at", iso(t.createdAt))
            },
        )
    }

    private fun categoriesJson(categories: List<TodoCategoryEntity>) = buildJsonArray {
        for (c in categories) add(
            buildJsonObject {
                put("id", c.id)
                put("name", c.name)
                put("normalized_name", c.normalizedName)
                put("created_at", iso(c.createdAt))
            },
        )
    }

    private fun recipesJson(recipes: List<RecipeEntity>) = buildJsonArray {
        for (r in recipes) add(
            buildJsonObject {
                put("id", r.id)
                put("name", r.name)
                put("notes", r.notes)
                put("pdf_filename", r.pdfFileName?.let { JsonPrimitive("recipes/$it") } ?: JsonNull)
                put("pdf_original_name", r.pdfOriginalName?.let { JsonPrimitive(it) } ?: JsonNull)
                put("ingredient_todo_category", r.ingredientTodoCategory?.let { JsonPrimitive(it) } ?: JsonNull)
                put("ingredient_todos_count", r.ingredientTodosCount?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "ingredient_todos_created_at",
                    r.ingredientTodosCreatedAt?.let { JsonPrimitive(iso(it)) } ?: JsonNull,
                )
                put("created_at", iso(r.createdAt))
                put("updated_at", iso(r.updatedAt))
            },
        )
    }

    private fun ingredientsJson(ingredients: List<IngredientEntity>) = buildJsonArray {
        for (i in ingredients) add(
            buildJsonObject {
                put("id", i.id)
                put("recipe_id", i.recipeId)
                put("name", i.name)
                put("quantity", i.quantity?.let { JsonPrimitive(it) } ?: JsonNull)
                put("created_at", iso(i.createdAt))
            },
        )
    }

    // ---- import ----

    private fun parseNotes(bytes: ByteArray): List<NoteEntity> = objects(bytes).map { o ->
        NoteEntity(
            id = o.long("id"),
            title = o.string("title") ?: "",
            content = o.string("content") ?: "",
            pinned = o.boolish("pinned"),
            sortOrder = o.longOrZero("sort_order"),
            createdAt = o.time("created_at"),
            updatedAt = o.time("updated_at"),
        )
    }

    private fun parseTodos(bytes: ByteArray): List<TodoEntity> = objects(bytes).map { o ->
        TodoEntity(
            id = o.long("id"),
            text = o.string("text") ?: "",
            completed = o.boolish("completed"),
            category = o.string("category")?.ifBlank { null } ?: "General",
            createdAt = o.time("created_at"),
        )
    }

    private fun parseCategories(bytes: ByteArray): List<TodoCategoryEntity> = objects(bytes).map { o ->
        val name = o.string("name") ?: ""
        TodoCategoryEntity(
            id = o.long("id"),
            name = name,
            normalizedName = o.string("normalized_name") ?: name.trim().lowercase(),
            createdAt = o.time("created_at"),
        )
    }

    private fun parseRecipes(bytes: ByteArray): List<RecipeEntity> = objects(bytes).map { o ->
        RecipeEntity(
            id = o.long("id"),
            name = o.string("name") ?: "",
            notes = o.string("notes") ?: "",
            pdfFileName = o.string("pdf_filename")?.substringAfterLast('/')?.ifBlank { null },
            pdfOriginalName = o.string("pdf_original_name"),
            ingredientTodoCategory = o.string("ingredient_todo_category"),
            ingredientTodosCount = o.string("ingredient_todos_count")?.toIntOrNull(),
            ingredientTodosCreatedAt = o.timeOrNull("ingredient_todos_created_at"),
            createdAt = o.time("created_at"),
            updatedAt = o.time("updated_at"),
        )
    }

    private fun parseIngredients(bytes: ByteArray): List<IngredientEntity> = objects(bytes).map { o ->
        IngredientEntity(
            id = o.long("id"),
            recipeId = o.long("recipe_id"),
            name = o.string("name") ?: "",
            quantity = o.string("quantity"),
            createdAt = o.time("created_at"),
        )
    }

    // ---- helpers ----

    private fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    private fun objects(bytes: ByteArray): List<JsonObject> =
        Json.parseToJsonElement(bytes.decodeToString()).jsonArray.filterIsInstance<JsonObject>()

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.string(key: String): String? {
        val p = prim(key) ?: return null
        return if (p is JsonNull) null else p.content
    }

    private fun JsonObject.long(key: String): Long = prim(key)?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.longOrZero(key: String): Long {
        val p = prim(key) ?: return 0L
        if (p is JsonNull) return 0L
        return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong() ?: 0L
    }

    /** Accepts true/false, 0/1, or "0"/"1". */
    private fun JsonObject.boolish(key: String): Boolean {
        val p = prim(key) ?: return false
        if (p is JsonNull) return false
        return when (p.content.lowercase()) {
            "true", "1" -> true
            else -> false
        }
    }

    /** Accepts ISO-8601 strings or epoch millis; missing/invalid -> 0. */
    private fun JsonObject.time(key: String): Long = timeOrNull(key) ?: 0L

    private fun JsonObject.timeOrNull(key: String): Long? {
        val p = prim(key) ?: return null
        if (p is JsonNull) return null
        p.content.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(p.content).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
