package uk.co.promptbuilt.notestodos.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import uk.co.promptbuilt.notestodos.data.db.AppDatabase

/**
 * iOS database builder: Documents/notes-todos.db (covered by device backup),
 * Room's bundled SQLite driver, queries off the main thread.
 */
fun buildAppDatabase(): AppDatabase {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true,
    ).first() as String
    return Room.databaseBuilder<AppDatabase>(name = "$documents/notes-todos.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
