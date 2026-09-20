package uk.co.promptbuilt.notestodos.data

import android.content.Context
import androidx.room.Room
import uk.co.promptbuilt.notestodos.data.db.AppDatabase
import uk.co.promptbuilt.notestodos.data.db.MIGRATION_1_2

/**
 * Android database builder. Deliberately stays on Room's Android compatibility
 * path (framework SQLite, databases/notes-todos.db) so installs that predate
 * the KMP split keep opening their existing database unchanged.
 */
fun buildAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder<AppDatabase>(
        context = context,
        name = context.getDatabasePath("notes-todos.db").absolutePath,
    )
        .addMigrations(MIGRATION_1_2)
        .build()
