package uk.co.promptbuilt.notestodos.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.RecipesRepository

/** What Settings shows about recipe file backup. */
data class DriveBackupStatus(
    val enabled: Boolean = false,
    val needsConsent: Boolean = false,
    val syncing: Boolean = false,
    val lastSuccessMillis: Long = 0,
    val backedUpCount: Int = 0,
    val missingCount: Int = 0,
    val lastError: String? = null,
)

/**
 * Backs recipe PDFs up to the hidden Google Drive application data folder. Android's Auto
 * Backup already carries the database and settings, but its 25 MB per-app quota cannot hold a
 * recipe collection, and an app over quota is not backed up at all, so the PDFs are excluded
 * there (res/xml/data_extraction_rules.xml) and handled here instead. After a cloud restore the
 * database returns via Auto Backup and this class brings the PDFs back.
 *
 * State lives in a plain SharedPreferences file, which Auto Backup includes: a restored phone
 * therefore knows backup was on and starts restoring as soon as Drive access is confirmed.
 */
class DriveBackup(
    private val context: Context,
    private val recipeFiles: () -> RecipeFiles,
    private val recipes: () -> RecipesRepository,
) {
    sealed interface EnableResult {
        data object Enabled : EnableResult
        data class NeedsConsent(val pendingIntent: PendingIntent) : EnableResult
        data class Failed(val message: String) : EnableResult
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val syncLock = Mutex()
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<DriveBackupStatus> = _status

    private val authRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA)))
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called at app start: resume the schedule and catch up (including any pending restore). */
    fun onAppStart() {
        // A picked PDF is written before its recipe is saved, so the write alone cannot
        // trigger the upload: the file only becomes worth keeping once an attachment row
        // references it. Every change to the set of attachments therefore requests a pass.
        scope.launch {
            recipes().observeAttachments()
                .map { rows -> rows.map { it.fileName }.toSet() }
                .distinctUntilChanged()
                .drop(1)
                .collect { requestSync() }
        }
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        schedulePeriodic()
        requestSync(delaySeconds = 0)
    }

    /** First step of turning backup on. Shows Google's consent screen if access is not yet granted. */
    suspend fun beginEnable(): EnableResult = try {
        val result = authorize()
        if (result.hasResolution()) {
            EnableResult.NeedsConsent(result.pendingIntent!!)
        } else {
            enable()
            EnableResult.Enabled
        }
    } catch (e: Exception) {
        EnableResult.Failed(e.message ?: "Google Drive access could not be requested")
    }

    /** Completes turning backup on after the consent screen returns. */
    fun completeConsent(data: Intent?): EnableResult = try {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        if (result.accessToken.isNullOrEmpty()) {
            EnableResult.Failed("Google Drive access was not granted")
        } else {
            enable()
            EnableResult.Enabled
        }
    } catch (e: Exception) {
        EnableResult.Failed(e.message ?: "Google Drive access was not granted")
    }

    /** Stops backing up. The existing copies stay in the customer's Drive. */
    fun disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).putBoolean(KEY_NEEDS_CONSENT, false).apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
        publish()
    }

    /** RecipeFiles reports every deletion here, so the Drive copy can be removed too. */
    fun recordDeletion(fileName: String) {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val pending = prefs.getStringSet(KEY_PENDING_DELETES, emptySet())!!.toMutableSet()
        if (pending.add(fileName)) prefs.edit().putStringSet(KEY_PENDING_DELETES, pending).apply()
    }

    /**
     * Debounced: bursts of changes (a backup import writes dozens of files) collapse into one
     * pass. KEEP drops a request while one is already queued; the dirty flag makes a pass that
     * is already running go round once more, so a change made mid-sync is never missed.
     */
    fun requestSync(delaySeconds: Long = DEBOUNCE_SECONDS) {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        prefs.edit().putBoolean(KEY_DIRTY, true).apply()
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(networkConstraint)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    enum class Outcome { DONE, SKIPPED, NEEDS_CONSENT, FAILED }

    /** One reconciliation pass, run by DriveSyncWorker. Never runs twice at once. */
    suspend fun sync(): Outcome = syncLock.withLock {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return Outcome.SKIPPED
        update { it.copy(syncing = true) }
        try {
            var rounds = 0
            do {
                prefs.edit().putBoolean(KEY_DIRTY, false).apply()
                val outcome = syncOnce()
                if (outcome != Outcome.DONE) return outcome
            } while (prefs.getBoolean(KEY_DIRTY, false) && ++rounds < MAX_ROUNDS)
            Outcome.DONE
        } finally {
            update { it.copy(syncing = false) }
        }
    }

    private suspend fun syncOnce(): Outcome {
        val token = silentToken() ?: run {
            prefs.edit().putBoolean(KEY_NEEDS_CONSENT, true).apply()
            publish()
            return Outcome.NEEDS_CONSENT
        }
        return try {
            runPass(DriveAppDataClient(token))
        } catch (expired: DriveAuthExpiredException) {
            // Cached tokens can outlive their validity; clear it and try once with a fresh one.
            withContext(Dispatchers.IO) { GoogleAuthUtil.clearToken(context, token) }
            val fresh = silentToken() ?: return Outcome.NEEDS_CONSENT
            try {
                runPass(DriveAppDataClient(fresh))
            } catch (e: Exception) {
                fail(e)
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private suspend fun runPass(drive: DriveAppDataClient): Outcome {
        val files = recipeFiles()
        val remote = drive.list()
        val referenced = recipes().observeAttachments().first().map { it.fileName }.toSet()
        val pending = prefs.getStringSet(KEY_PENDING_DELETES, emptySet())!!
        val plan = DrivePdfSyncPlan.compute(files.listLocal(), referenced, remote, pending)

        for (name in plan.uploads) drive.upload(name, files.fileFor(name))
        for (stale in plan.replacedRemotes) drive.delete(stale.id)
        for (file in plan.downloads) drive.download(file.id, files.fileFor(file.name))

        val deleted = mutableSetOf<String>()
        for (file in plan.remoteDeletes) {
            drive.delete(file.id)
            deleted += file.name
        }
        val stillPending = prefs.getStringSet(KEY_PENDING_DELETES, emptySet())!!
            .minus(plan.settledPendingDeletes)
            .minus(deleted)

        val backedUp = remote.map { it.name }.toSet() + plan.uploads - deleted
        prefs.edit()
            .putStringSet(KEY_PENDING_DELETES, stillPending)
            .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
            .putInt(KEY_BACKED_UP, backedUp.count { it in referenced })
            .putInt(KEY_MISSING, plan.missingEverywhere.size)
            .putBoolean(KEY_NEEDS_CONSENT, false)
            .remove(KEY_LAST_ERROR)
            .apply()
        publish()
        return Outcome.DONE
    }

    private fun fail(e: Exception): Outcome {
        prefs.edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
        publish()
        return Outcome.FAILED
    }

    private fun enable() {
        prefs.edit().putBoolean(KEY_ENABLED, true).putBoolean(KEY_NEEDS_CONSENT, false).apply()
        publish()
        schedulePeriodic()
        requestSync(delaySeconds = 0)
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private suspend fun authorize(): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(authRequest).await()

    /** A token without any UI, or null when the customer must grant access again. */
    private suspend fun silentToken(): String? = try {
        val result = authorize()
        if (result.hasResolution()) null else result.accessToken
    } catch (_: Exception) {
        null
    }

    private fun readStatus() = DriveBackupStatus(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        needsConsent = prefs.getBoolean(KEY_NEEDS_CONSENT, false),
        lastSuccessMillis = prefs.getLong(KEY_LAST_SUCCESS, 0),
        backedUpCount = prefs.getInt(KEY_BACKED_UP, 0),
        missingCount = prefs.getInt(KEY_MISSING, 0),
        lastError = prefs.getString(KEY_LAST_ERROR, null),
    )

    private fun publish() = update { readStatus().copy(syncing = it.syncing) }

    private fun update(change: (DriveBackupStatus) -> DriveBackupStatus) {
        _status.value = change(_status.value)
    }

    private companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val PREFS = "drive_backup"
        const val KEY_ENABLED = "enabled"
        const val KEY_NEEDS_CONSENT = "needs_consent"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_BACKED_UP = "backed_up_count"
        const val KEY_MISSING = "missing_count"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_PENDING_DELETES = "pending_deletes"
        const val KEY_DIRTY = "dirty"
        const val WORK_PERIODIC = "drive-backup-periodic"
        const val WORK_NOW = "drive-backup-now"
        const val DEBOUNCE_SECONDS = 20L
        const val MAX_ROUNDS = 3

        val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
