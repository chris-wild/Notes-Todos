package uk.co.promptbuilt.notestodos.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schemas mirror the web backend's S3 collections (backend/datastore.js record
// constructors), minus user_id. Timestamps are epoch millis.

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val pinned: Boolean = false,
    // Bumped to "now" on pin toggle; used for manual ordering within the pinned section.
    val sortOrder: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "todos", indices = [Index("category")])
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val completed: Boolean = false,
    val category: String = CategoryDefaults.GENERAL,
    val createdAt: Long,
)

object CategoryDefaults {
    const val GENERAL = "General"
    const val SHOPPING_LIST = "Shopping List"
}

@Entity(
    tableName = "todo_categories",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class TodoCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
)

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val ingredientTodoCategory: String? = null,
    val ingredientTodosCount: Int? = null,
    val ingredientTodosCreatedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A PDF attached to a recipe (schema v2: recipes can hold several). */
@Entity(
    tableName = "recipe_attachments",
    indices = [Index("recipeId")],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecipeAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    /** File name inside the platform recipe store (not a full path). */
    val fileName: String,
    val originalName: String? = null,
    val position: Int = 0,
    val createdAt: Long,
)

@Entity(
    tableName = "ingredients",
    indices = [Index("recipeId")],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val name: String,
    val quantity: String? = null,
    val createdAt: Long,
)
