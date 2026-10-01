package uk.co.promptbuilt.notestodos.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import uk.co.promptbuilt.notestodos.NotesTodosApp

/** Runs one recipe-file sync pass; failures retry with WorkManager's backoff. */
class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backup = (applicationContext as NotesTodosApp).driveBackup
        return when (backup.sync()) {
            DriveBackup.Outcome.FAILED -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
