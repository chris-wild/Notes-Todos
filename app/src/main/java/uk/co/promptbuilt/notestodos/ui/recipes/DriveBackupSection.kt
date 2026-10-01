package uk.co.promptbuilt.notestodos.ui.recipes

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import uk.co.promptbuilt.notestodos.backup.DriveBackup
import uk.co.promptbuilt.notestodos.backup.DriveBackupStatus

/** Settings section for backing recipe PDFs up to the hidden Google Drive app folder. */
@Composable
fun DriveBackupSection(backup: DriveBackup) {
    val status by backup.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        message = if (result.resultCode == Activity.RESULT_OK) {
            (backup.completeConsent(result.data) as? DriveBackup.EnableResult.Failed)?.message
        } else {
            "Google Drive access was not granted, so recipe files are not backed up."
        }
    }

    fun turnOn() {
        scope.launch {
            message = when (val result = backup.beginEnable()) {
                is DriveBackup.EnableResult.NeedsConsent -> {
                    consent.launch(IntentSenderRequest.Builder(result.pendingIntent.intentSender).build())
                    null
                }
                is DriveBackup.EnableResult.Failed -> result.message
                DriveBackup.EnableResult.Enabled -> null
            }
        }
    }

    Text("Recipe file backup", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = describe(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        Switch(
            checked = status.enabled,
            onCheckedChange = { on -> if (on) turnOn() else backup.disable() },
        )
    }
    if (status.enabled && status.needsConsent) {
        TextButton(onClick = ::turnOn) { Text("Grant Google Drive access") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private fun describe(status: DriveBackupStatus): String = when {
    !status.enabled ->
        "Off. Android already backs up your notes, todos and recipe details. Turn this on to keep " +
            "recipe photos and PDFs in your Google Drive as well, in a private folder only HobPad can see."
    status.needsConsent ->
        "Google Drive access needs to be granted again before recipe files can be backed up or restored."
    status.syncing -> "Backing up recipe files…"
    status.lastSuccessMillis == 0L -> "On. The first backup will start shortly."
    else -> buildString {
        val count = status.backedUpCount
        append("On. ${if (count == 1) "1 recipe file" else "$count recipe files"} backed up, last at ")
        append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.lastSuccessMillis)))
        append(".")
        if (status.missingCount > 0) {
            append(" ${status.missingCount} recipe ${if (status.missingCount == 1) "file is" else "files are"} ")
            append("missing from both this phone and Drive.")
        }
        if (status.lastError != null) append(" The last attempt failed and will be retried.")
    }
}
