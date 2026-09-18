package uk.co.promptbuilt.notestodos.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android SAF shim over the shared bytes-based BackupManager. */
class SafBackup(
    private val context: Context,
    private val manager: BackupManager,
) {

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = manager.exportBytes()
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not open destination for writing")
    }

    suspend fun importFrom(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open backup for reading")
        manager.importBytes(bytes)
    }
}
