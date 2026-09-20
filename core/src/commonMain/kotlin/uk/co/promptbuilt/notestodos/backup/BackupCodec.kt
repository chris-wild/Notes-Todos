package uk.co.promptbuilt.notestodos.backup

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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
import uk.co.promptbuilt.notestodos.data.db.RecipeAttachmentEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity
import uk.co.promptbuilt.notestodos.data.db.TodoCategoryEntity
import uk.co.promptbuilt.notestodos.data.db.TodoEntity

data class BackupData(
    val notes: List<NoteEntity> = emptyList(),
    val todos: List<TodoEntity> = emptyList(),
    val categories: List<TodoCategoryEntity> = emptyList(),
    val recipes: List<RecipeEntity> = emptyList(),
    val attachments: List<RecipeAttachmentEntity> = emptyList(),
    val ingredients: List<IngredientEntity> = emptyList(),
    /** basename -> bytes, referenced by RecipeAttachmentEntity.fileName */
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

    fun write(data: BackupData): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["data/notes.json"] = notesJson(data.notes).toString().encodeToByteArray()
        entries["data/todos.json"] = todosJson(data.todos).toString().encodeToByteArray()
        entries["data/todo-categories.json"] = categoriesJson(data.categories).toString().encodeToByteArray()
        entries["data/recipes.json"] =
            recipesJson(data.recipes, data.attachments).toString().encodeToByteArray()
        entries["data/ingredients.json"] = ingredientsJson(data.ingredients).toString().encodeToByteArray()
        for ((name, bytes) in data.pdfs) entries["recipes/$name"] = bytes
        return ZipCodec.write(entries)
    }

    fun read(bytes: ByteArray): BackupData {
        val entries = ZipCodec.read(bytes)
        val pdfs = entries.filterKeys { it.startsWith("recipes/") }
            .mapKeys { (name, _) -> name.substringAfterLast('/') }
        val (recipes, attachments) = entries["data/recipes.json"]
            ?.let(::parseRecipesWithAttachments) ?: (emptyList<RecipeEntity>() to emptyList())
        return BackupData(
            notes = entries["data/notes.json"]?.let(::parseNotes) ?: emptyList(),
            todos = entries["data/todos.json"]?.let(::parseTodos) ?: emptyList(),
            categories = entries["data/todo-categories.json"]?.let(::parseCategories) ?: emptyList(),
            recipes = recipes,
            attachments = attachments,
            ingredients = entries["data/ingredients.json"]?.let(::parseIngredients) ?: emptyList(),
            pdfs = pdfs,
        )
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

    private fun recipesJson(
        recipes: List<RecipeEntity>,
        attachments: List<RecipeAttachmentEntity>,
    ) = buildJsonArray {
        val byRecipe = attachments.groupBy { it.recipeId }
        for (r in recipes) add(
            buildJsonObject {
                val mine = byRecipe[r.id].orEmpty().sortedBy { it.position }
                put("id", r.id)
                put("name", r.name)
                put("notes", r.notes)
                // Legacy single-attachment fields (first attachment) so pre-v2
                // apps can still read new backups.
                put("pdf_filename", mine.firstOrNull()?.let { JsonPrimitive("recipes/${it.fileName}") } ?: JsonNull)
                put("pdf_original_name", mine.firstOrNull()?.originalName?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "attachments",
                    buildJsonArray {
                        for (a in mine) add(
                            buildJsonObject {
                                put("file_name", a.fileName)
                                put("original_name", a.originalName?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("position", a.position)
                                put("created_at", iso(a.createdAt))
                            },
                        )
                    },
                )
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

    private fun parseRecipesWithAttachments(
        bytes: ByteArray,
    ): Pair<List<RecipeEntity>, List<RecipeAttachmentEntity>> {
        val recipes = mutableListOf<RecipeEntity>()
        val attachments = mutableListOf<RecipeAttachmentEntity>()
        for (o in objects(bytes)) {
            val recipe = RecipeEntity(
                id = o.long("id"),
                name = o.string("name") ?: "",
                notes = o.string("notes") ?: "",
                ingredientTodoCategory = o.string("ingredient_todo_category"),
                ingredientTodosCount = o.string("ingredient_todos_count")?.toIntOrNull(),
                ingredientTodosCreatedAt = o.timeOrNull("ingredient_todos_created_at"),
                createdAt = o.time("created_at"),
                updatedAt = o.time("updated_at"),
            )
            recipes.add(recipe)

            val array = o["attachments"] as? kotlinx.serialization.json.JsonArray
            if (array != null) {
                for (element in array) {
                    val a = element as? JsonObject ?: continue
                    val fileName = a.string("file_name")?.substringAfterLast('/')?.ifBlank { null } ?: continue
                    attachments.add(
                        RecipeAttachmentEntity(
                            recipeId = recipe.id,
                            fileName = fileName,
                            originalName = a.string("original_name"),
                            position = a.string("position")?.toIntOrNull() ?: 0,
                            createdAt = a.timeOrNull("created_at") ?: recipe.createdAt,
                        ),
                    )
                }
            } else {
                // Legacy single-attachment shape (pre-v2 backups and S3-era exports).
                val legacy = o.string("pdf_filename")?.substringAfterLast('/')?.ifBlank { null }
                if (legacy != null) {
                    attachments.add(
                        RecipeAttachmentEntity(
                            recipeId = recipe.id,
                            fileName = legacy,
                            originalName = o.string("pdf_original_name"),
                            position = 0,
                            createdAt = recipe.createdAt,
                        ),
                    )
                }
            }
        }
        return recipes to attachments
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

    @OptIn(ExperimentalTime::class)
    private fun iso(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

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

    @OptIn(ExperimentalTime::class)
    private fun JsonObject.timeOrNull(key: String): Long? {
        val p = prim(key) ?: return null
        if (p is JsonNull) return null
        p.content.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(p.content).toEpochMilliseconds()
        } catch (_: Exception) {
            null
        }
    }
}
