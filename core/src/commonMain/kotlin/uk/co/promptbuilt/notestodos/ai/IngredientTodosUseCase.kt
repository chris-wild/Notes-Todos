package uk.co.promptbuilt.notestodos.ai

import uk.co.promptbuilt.notestodos.data.RecipeStore
import uk.co.promptbuilt.notestodos.data.RecipesRepository
import uk.co.promptbuilt.notestodos.data.nowMillis
import uk.co.promptbuilt.notestodos.data.SecureKeys
import uk.co.promptbuilt.notestodos.data.TodosRepository
import uk.co.promptbuilt.notestodos.data.db.IngredientEntity

/**
 * Port of POST /api/recipes/:id/create-ingredient-todos (backend/server.js:1073-1170):
 * cached ingredients short-circuit the API call; extraction results are cached;
 * todos land in a category named after the recipe.
 */
class IngredientTodosUseCase(
    private val recipes: RecipesRepository,
    private val todos: TodosRepository,
    private val recipeStore: RecipeStore,
    private val client: AnthropicClient,
    private val secureKeys: SecureKeys,
) {

    data class Outcome(val category: String, val count: Int, val alreadyCreated: Boolean)

    suspend fun run(recipeId: Long): Outcome {
        val apiKey = secureKeys.getAnthropicKey()
            ?: throw IllegalStateException("No Anthropic API key configured. Add one in Settings.")
        val recipe = recipes.getById(recipeId)
            ?: throw IllegalStateException("Recipe not found")

        val cached = recipes.getIngredients(recipeId)
        val hasCached = cached.isNotEmpty()

        val ingredients: List<String> = if (hasCached) {
            cached.map { row ->
                if (!row.quantity.isNullOrBlank()) "${row.quantity} ${row.name}".trim() else row.name
            }
        } else {
            val extracted = if (recipe.pdfFileName != null) {
                val bytes = recipeStore.read(recipe.pdfFileName)
                    ?: throw IllegalStateException("PDF not found")
                client.extractIngredientsFromPdf(bytes, recipe.name, apiKey)
            } else {
                val text = recipe.notes.trim()
                if (text.isEmpty()) {
                    throw IllegalStateException("Recipe has no PDF and no notes to extract ingredients from")
                }
                client.extractIngredientsFromText(text, recipe.name, apiKey)
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
