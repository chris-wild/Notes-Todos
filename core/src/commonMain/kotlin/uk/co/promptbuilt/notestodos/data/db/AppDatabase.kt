package uk.co.promptbuilt.notestodos.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        NoteEntity::class,
        TodoEntity::class,
        TodoCategoryEntity::class,
        RecipeEntity::class,
        RecipeAttachmentEntity::class,
        IngredientEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun todoDao(): TodoDao
    abstract fun todoCategoryDao(): TodoCategoryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun recipeAttachmentDao(): RecipeAttachmentDao
    abstract fun ingredientDao(): IngredientDao
}

/**
 * v1 -> v2: recipes gain multiple attachments. The single pdfFileName /
 * pdfOriginalName columns move into the new recipe_attachments table as
 * position-0 rows; the recipes table is REBUILT without those columns
 * (minSdk 26 SQLite has no DROP COLUMN).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `recipe_attachments` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `recipeId` INTEGER NOT NULL,
                `fileName` TEXT NOT NULL,
                `originalName` TEXT,
                `position` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`recipeId`) REFERENCES `recipes`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recipe_attachments_recipeId` ON `recipe_attachments` (`recipeId`)",
        )
        connection.execSQL(
            """INSERT INTO recipe_attachments (recipeId, fileName, originalName, position, createdAt)
               SELECT id, pdfFileName, pdfOriginalName, 0, createdAt FROM recipes
               WHERE pdfFileName IS NOT NULL""",
        )
        connection.execSQL(
            """CREATE TABLE `recipes_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `notes` TEXT NOT NULL,
                `ingredientTodoCategory` TEXT,
                `ingredientTodosCount` INTEGER,
                `ingredientTodosCreatedAt` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL)""",
        )
        connection.execSQL(
            """INSERT INTO recipes_new (id, name, notes, ingredientTodoCategory,
                ingredientTodosCount, ingredientTodosCreatedAt, createdAt, updatedAt)
               SELECT id, name, notes, ingredientTodoCategory,
                ingredientTodosCount, ingredientTodosCreatedAt, createdAt, updatedAt
               FROM recipes""",
        )
        connection.execSQL("DROP TABLE recipes")
        connection.execSQL("ALTER TABLE recipes_new RENAME TO recipes")
    }
}

// The Room compiler generates the per-target actuals.
@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
