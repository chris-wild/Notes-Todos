package uk.co.promptbuilt.notestodos.backup

/** A recipe PDF held in the Drive application data folder. */
data class RemoteFile(val id: String, val name: String, val size: Long)

/**
 * The work one sync pass must do. [replacedRemotes] are stale remote copies to delete only
 * after their fresh upload succeeds, so a file is never left without a backup.
 */
data class SyncPlan(
    val uploads: List<String>,
    val replacedRemotes: List<RemoteFile>,
    val downloads: List<RemoteFile>,
    val remoteDeletes: List<RemoteFile>,
    val settledPendingDeletes: Set<String>,
    val missingEverywhere: Set<String>,
)

/**
 * Decides how to reconcile local recipe PDFs with their Drive copies. The database is the
 * authority on which files matter: only attachment rows are uploaded or restored.
 *
 * Remote deletion is driven solely by [pendingDeletes], the names the app recorded as it
 * deleted them. Absence is never read as deletion: a freshly installed phone, with an empty
 * database and no files, must never erase the customer's backup.
 */
object DrivePdfSyncPlan {

    fun compute(
        local: Map<String, Long>,
        referenced: Set<String>,
        remote: List<RemoteFile>,
        pendingDeletes: Set<String>,
    ): SyncPlan {
        // Duplicate names (an upload retried after a lost reply) keep one copy; the extras go.
        val byName = remote.groupBy { it.name }
        val keep = byName.mapValues { (_, copies) -> copies.first() }
        val duplicates = byName.values.flatMap { it.drop(1) }

        val uploads = mutableListOf<String>()
        val replaced = mutableListOf<RemoteFile>()
        for ((name, size) in local) {
            if (name !in referenced) continue
            val existing = keep[name]
            if (existing == null) {
                uploads += name
            } else if (existing.size != size) {
                uploads += name
                replaced += existing
            }
        }

        val downloads = referenced
            .filter { it !in local }
            .mapNotNull { keep[it] }

        val remoteDeletes = mutableListOf<RemoteFile>()
        val settled = mutableSetOf<String>()
        for (name in pendingDeletes) {
            when {
                // Written again since (a backup import restored it): nothing to delete.
                name in local -> settled += name
                // Still referenced: keeping data wins over honouring the deletion.
                name in referenced -> Unit
                name !in byName -> settled += name
                else -> remoteDeletes += byName.getValue(name)
            }
        }

        return SyncPlan(
            uploads = uploads.sorted(),
            replacedRemotes = replaced,
            downloads = downloads,
            remoteDeletes = remoteDeletes + duplicates.filter { it.name !in pendingDeletes },
            settledPendingDeletes = settled,
            missingEverywhere = referenced.filter { it !in local && it !in keep }.toSet(),
        )
    }
}
