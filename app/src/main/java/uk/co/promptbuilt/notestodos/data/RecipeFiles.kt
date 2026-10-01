package uk.co.promptbuilt.notestodos.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recipe attachments as PDF files in filesDir/recipes/. Replaces the web stack's
 * S3 storage + sharp/pdf-lib image conversion (backend/image-to-pdf.js): images
 * are normalised on-device into a single-page PDF so the viewer only ever deals
 * with PDFs, matching the server behavior.
 */
class RecipeFiles(
    private val context: Context,
    /** Every deletion passes through here so the Drive backup copy can follow (DriveBackup). */
    private val onDeleted: (String) -> Unit = {},
    private val onChanged: () -> Unit = {},
) : RecipeStore {

    private val dir: File
        get() = File(context.filesDir, "recipes").apply { mkdirs() }

    fun fileFor(fileName: String): File = File(dir, fileName)

    /** Stored PDFs by name and size; partial downloads (.part) are not PDFs yet. */
    fun listLocal(): Map<String, Long> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") }
            ?.associate { it.name to it.length() }
            .orEmpty()

    fun deleteIfPresent(fileName: String?) {
        if (!fileName.isNullOrBlank()) delete(fileName)
    }

    override fun read(fileName: String): ByteArray? =
        fileFor(fileName).takeIf { it.exists() }?.readBytes()

    override fun write(fileName: String, bytes: ByteArray) {
        fileFor(fileName).writeBytes(bytes)
        onChanged()
    }

    override fun delete(fileName: String) {
        if (fileFor(fileName).delete()) {
            onDeleted(fileName)
            onChanged()
        }
    }

    override fun clearAll() {
        dir.listFiles()?.forEach { file ->
            if (file.delete() && file.name.endsWith(".pdf")) onDeleted(file.name)
        }
        onChanged()
    }

    data class Imported(val fileName: String, val originalName: String)

    /** Copies a picked PDF, or converts a picked image to a one-page PDF. */
    suspend fun importAttachment(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val originalName = displayName(resolver, uri) ?: "attachment"
        val mime = resolver.getType(uri) ?: ""
        val fileName = "${UUID.randomUUID()}.pdf"
        val target = fileFor(fileName)
        try {
            if (mime == "application/pdf" || originalName.endsWith(".pdf", ignoreCase = true)) {
                resolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            } else {
                imageToPdf(resolver, uri, target)
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        onChanged()
        Imported(fileName, originalName)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }

    private fun imageToPdf(resolver: ContentResolver, uri: Uri, target: File) {
        // Downsample anything over ~4000px on the long edge (matches image-to-pdf.js).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Could not decode image")
        try {
            val document = PdfDocument()
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, Matrix(), null)
                document.finishPage(page)
                target.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_IMAGE_EDGE_PX = 4000
    }
}
