package uk.co.promptbuilt.notestodos.ai

import uk.co.promptbuilt.notestodos.data.RecipeStore
import uk.co.promptbuilt.notestodos.data.RecipesRepository
import uk.co.promptbuilt.notestodos.data.nowMillis
import uk.co.promptbuilt.notestodos.data.TodosRepository
import uk.co.promptbuilt.notestodos.data.db.IngredientEntity

/**
 * Port of POST /api/recipes/:id/create-ingredient-todos (backend/server.js:1073-1170):
 * cached ingredients short-circuit the API call; extraction results are cached;
 * todos land in a category named after the recipe. Whether extraction rides the user's
 * own key or the metered proxy is the injected OcrService's business — a cached rerun
 * costs nothing and needs neither.
 */
class IngredientTodosUseCase(
    private val recipes: RecipesRepository,
    private val todos: TodosRepository,
    private val recipeStore: RecipeStore,
    private val ocr: OcrService,
) {

    data class Outcome(val category: String, val count: Int, val alreadyCreated: Boolean)

    // @Throws matters: without it, SKIE's async bridge treats a Kotlin exception as an
    // UNHANDLED coroutine failure and terminates the iOS app instead of throwing to Swift
    // (diagnosed in the simulator, Sept 2026 — a 502 from the metering Worker killed the app).
    @Throws(Exception::class)
    suspend fun run(recipeId: Long): Outcome {
        val recipe = recipes.getById(recipeId)
            ?: throw IllegalStateException("Recipe not found")

        val cached = recipes.getIngredients(recipeId)
        val hasCached = cached.isNotEmpty()

        val ingredients: List<String> = if (hasCached) {
            cached.map { row ->
                if (!row.quantity.isNullOrBlank()) "${row.quantity} ${row.name}".trim() else row.name
            }
        } else {
            val attachments = recipes.getAttachments(recipeId)
            val extracted = if (attachments.isNotEmpty()) {
                // Merge every attached PDF's list, in attachment order — a recipe
                // photographed across several pages yields one combined list.
                attachments.flatMap { attachment ->
                    val bytes = recipeStore.read(attachment.fileName)
                        ?: throw IllegalStateException("PDF not found: ${attachment.originalName ?: attachment.fileName}")
                    ocr.extractIngredientsFromPdf(bytes, recipe.name)
                }
            } else {
                val text = recipe.notes.trim()
                if (text.isEmpty()) {
                    throw IllegalStateException("Recipe has no PDF and no notes to extract ingredients from")
                }
                ocr.extractIngredientsFromText(text, recipe.name)
            }
            if (extracted.isNotEmpty()) {
                val now = nowMillis()
                recipes.replaceIngredients(
                    recipeId,
                    extracted.map { ing ->
                        val (name, quantity) = IngredientParsing.splitQuantity(ing)
                        IngredientEntity(recipeId = recipeId, name = name, quantity = quantity, createdAt = now)
                    },
                )
            }
            extracted
        }

        val categoryName = recipe.name.trim().ifEmpty { "Recipe" }
        todos.addCategory(categoryName) // no-op when it already exists

        val existing = todos.countInCategory(categoryName)
        var inserted = existing
        if (hasCached || existing == 0) {
            // Recreate the category's todos so previously deleted items come back.
            todos.deleteAllInCategory(categoryName)
            inserted = 0
            for (ing in ingredients) {
                if (todos.addTodo(ing, categoryName) != null) inserted++
            }
            recipes.setIngredientMetadata(recipeId, categoryName, inserted)
        }

        return Outcome(category = categoryName, count = inserted, alreadyCreated = existing > 0)
    }
}
