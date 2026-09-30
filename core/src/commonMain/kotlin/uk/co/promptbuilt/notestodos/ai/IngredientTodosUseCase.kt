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
    // [multiplier] scales every quantity (a ×3 shopping trip); the CACHE always holds the
    // ×1 amalgamated list, so re-running at a different multiplier costs nothing.
    @Throws(Exception::class)
    suspend fun run(recipeId: Long, multiplier: Int = 1): Outcome {
        val recipe = recipes.getById(recipeId)
            ?: throw IllegalStateException("Recipe not found")

        val cached = recipes.getIngredients(recipeId)
        val hasCached = cached.isNotEmpty()

        val lines: List<IngredientLine> = if (hasCached) {
            // Amalgamate cached rows too, and drop the "— section —" headings older
            // parses cached: caches written before either change existed stay usable.
            IngredientMath.amalgamate(
                cached.map { row -> IngredientLine(row.name, row.quantity?.takeIf { it.isNotBlank() }) },
            ).filterNot { IngredientMath.isHeading(it) }
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
            // "3 garlic cloves" for the sauce + "6 garlic cloves" for the dish -> "9 garlic cloves".
            val merged = IngredientMath.amalgamate(
                extracted.map { ing ->
                    val (name, quantity) = IngredientParsing.splitQuantity(ing)
                    IngredientLine(name, quantity)
                },
            ).filterNot { IngredientMath.isHeading(it) }
            if (merged.isEmpty()) {
                // Every page came back empty (non-recipe photos, blank pages): say so
                // rather than silently creating an empty shopping list.
                throw IllegalStateException("No ingredients found — the recipe's pages do not contain a readable ingredient list")
            }
            if (merged.isNotEmpty()) {
                val now = nowMillis()
                recipes.replaceIngredients(
                    recipeId,
                    merged.map { line ->
                        IngredientEntity(recipeId = recipeId, name = line.name, quantity = line.quantity, createdAt = now)
                    },
                )
            }
            merged
        }
        val ingredients = IngredientMath.scale(lines, multiplier).map { IngredientMath.format(it) }

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
