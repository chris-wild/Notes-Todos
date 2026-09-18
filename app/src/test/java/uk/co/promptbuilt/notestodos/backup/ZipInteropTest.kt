package uk.co.promptbuilt.notestodos.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.promptbuilt.notestodos.data.db.NoteEntity

/**
 * JVM-only interop proofs for the shared backup zip format:
 * - reads archives written by java.util.zip's ZipOutputStream, which emits
 *   DEFLATED entries with data descriptors (GP bit 3) — the exact shape old
 *   notes-todos backups have
 * - writes archives that ZipInputStream can read back
 * - reads the real production export when present on this machine
 */
class ZipInteropTest {

    @Test
    fun readsZipOutputStreamArchivesWithDataDescriptors() {
        val notesJson = """[{"id": 9, "title": "t", "content": "c", "pinned": false,
            "sort_order": 0, "created_at": 1000, "updated_at": 2000}]"""
        val pdf = "%PDF-1.4 ".repeat(500).toByteArray()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("data/notes.json")); zip.write(notesJson.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("recipes/a.pdf")); zip.write(pdf); zip.closeEntry()
        }

        val back = BackupCodec.read(out.toByteArray())
        assertEquals(listOf(9L), back.notes.map(NoteEntity::id))
        assertEquals("t", back.notes.single().title)
        assertArrayEquals(pdf, back.pdfs["a.pdf"])
    }

    @Test
    fun zipInputStreamReadsOurArchives() {
        val data = BackupData(
            notes = listOf(NoteEntity(1, "n", "c", createdAt = 1, updatedAt = 2)),
            pdfs = mapOf("b.pdf" to ByteArray(2048) { ((it * 37) and 0xFF).toByte() }),
        )
        val zip = BackupCodec.write(data)

        val back = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                back[entry.name] = stream.readBytes()
                entry = stream.nextEntry
            }
        }
        assertTrue(back.keys.containsAll(setOf("data/notes.json", "recipes/b.pdf")))
        assertArrayEquals(data.pdfs["b.pdf"], back["recipes/b.pdf"])
        assertTrue(back["data/notes.json"]!!.decodeToString().contains("\"title\":\"n\""))
    }

    @Test
    fun readsTheRealProductionExportWhenPresent() {
        val real = File("../export/android-import.zip")
        assumeTrue("real export not present on this machine", real.exists())

        val data = BackupCodec.read(real.readBytes())
        assertEquals(112, data.notes.size)
        assertEquals(38, data.todos.size)
        assertEquals(5, data.categories.size)
        assertEquals(69, data.recipes.size)
        assertEquals(98, data.ingredients.size)
        assertEquals(63, data.pdfs.size)
        // Every PDF entry should actually look like a PDF
        assertTrue(data.pdfs.values.all { it.size > 4 && it.decodeToString(0, 4) == "%PDF" })
    }
}
