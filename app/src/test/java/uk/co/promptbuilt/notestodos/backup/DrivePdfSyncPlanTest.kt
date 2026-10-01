package uk.co.promptbuilt.notestodos.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePdfSyncPlanTest {

    private fun remote(name: String, size: Long = 100, id: String = "id-$name") = RemoteFile(id, name, size)

    @Test
    fun uploadsReferencedLocalFilesMissingFromDrive() {
        val plan = DrivePdfSyncPlan.compute(
            local = mapOf("a.pdf" to 100L, "b.pdf" to 200L),
            referenced = setOf("a.pdf", "b.pdf"),
            remote = listOf(remote("a.pdf")),
            pendingDeletes = emptySet(),
        )
        assertEquals(listOf("b.pdf"), plan.uploads)
        assertTrue(plan.downloads.isEmpty())
        assertTrue(plan.remoteDeletes.isEmpty())
    }

    @Test
    fun ignoresLocalFilesNoRecipeReferences() {
        val plan = DrivePdfSyncPlan.compute(
            local = mapOf("orphan.pdf" to 100L),
            referenced = emptySet(),
            remote = emptyList(),
            pendingDeletes = emptySet(),
        )
        assertTrue(plan.uploads.isEmpty())
    }

    @Test
    fun restoresReferencedFilesMissingLocally() {
        // The cloud-restore case: the database came back via Auto Backup, the PDFs did not.
        val plan = DrivePdfSyncPlan.compute(
            local = emptyMap(),
            referenced = setOf("a.pdf", "b.pdf"),
            remote = listOf(remote("a.pdf"), remote("b.pdf")),
            pendingDeletes = emptySet(),
        )
        assertEquals(setOf("a.pdf", "b.pdf"), plan.downloads.map { it.name }.toSet())
        assertTrue(plan.uploads.isEmpty())
    }

    @Test
    fun freshInstallNeverDeletesTheBackup() {
        val plan = DrivePdfSyncPlan.compute(
            local = emptyMap(),
            referenced = emptySet(),
            remote = listOf(remote("a.pdf"), remote("b.pdf")),
            pendingDeletes = emptySet(),
        )
        assertTrue(plan.remoteDeletes.isEmpty())
        assertTrue(plan.downloads.isEmpty())
    }

    @Test
    fun deletesRemoteCopiesOfRecordedDeletions() {
        val plan = DrivePdfSyncPlan.compute(
            local = emptyMap(),
            referenced = emptySet(),
            remote = listOf(remote("gone.pdf"), remote("kept.pdf")),
            pendingDeletes = setOf("gone.pdf", "never-uploaded.pdf"),
        )
        assertEquals(listOf("gone.pdf"), plan.remoteDeletes.map { it.name })
        assertEquals(setOf("never-uploaded.pdf"), plan.settledPendingDeletes)
    }

    @Test
    fun recordedDeletionOfAFileWrittenAgainIsSettledNotDeleted() {
        // clearAll() during a backup import records every name; the import writes some back.
        val plan = DrivePdfSyncPlan.compute(
            local = mapOf("a.pdf" to 100L),
            referenced = setOf("a.pdf"),
            remote = listOf(remote("a.pdf")),
            pendingDeletes = setOf("a.pdf"),
        )
        assertTrue(plan.remoteDeletes.isEmpty())
        assertEquals(setOf("a.pdf"), plan.settledPendingDeletes)
    }

    @Test
    fun recordedDeletionOfAStillReferencedFileKeepsTheData() {
        val plan = DrivePdfSyncPlan.compute(
            local = emptyMap(),
            referenced = setOf("a.pdf"),
            remote = listOf(remote("a.pdf")),
            pendingDeletes = setOf("a.pdf"),
        )
        assertTrue(plan.remoteDeletes.isEmpty())
        assertEquals(listOf("a.pdf"), plan.downloads.map { it.name })
        assertTrue("stays pending until the reference goes", plan.settledPendingDeletes.isEmpty())
    }

    @Test
    fun reuploadsAChangedFileAndRetiresTheStaleCopy() {
        val stale = remote("a.pdf", size = 999)
        val plan = DrivePdfSyncPlan.compute(
            local = mapOf("a.pdf" to 100L),
            referenced = setOf("a.pdf"),
            remote = listOf(stale),
            pendingDeletes = emptySet(),
        )
        assertEquals(listOf("a.pdf"), plan.uploads)
        assertEquals(listOf(stale), plan.replacedRemotes)
    }

    @Test
    fun removesDuplicateRemoteCopies() {
        val plan = DrivePdfSyncPlan.compute(
            local = mapOf("a.pdf" to 100L),
            referenced = setOf("a.pdf"),
            remote = listOf(remote("a.pdf", id = "first"), remote("a.pdf", id = "second")),
            pendingDeletes = emptySet(),
        )
        assertTrue(plan.uploads.isEmpty())
        assertEquals(listOf("second"), plan.remoteDeletes.map { it.id })
    }

    @Test
    fun reportsFilesMissingEverywhere() {
        val plan = DrivePdfSyncPlan.compute(
            local = emptyMap(),
            referenced = setOf("lost.pdf"),
            remote = emptyList(),
            pendingDeletes = emptySet(),
        )
        assertEquals(setOf("lost.pdf"), plan.missingEverywhere)
    }
}
