package uk.co.promptbuilt.notestodos.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
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
        // Same long-edge limit as iOS's ImageToPDF: plenty for reading and OCR, and it keeps
        // the PDF well inside the Worker's upload cap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Could not decode image")
        // Cameras often store a photo sideways with an orientation tag; iOS applies the tag
        // when it reads the image, so the page must be turned upright here too.
        val orientation = runCatching {
            resolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val longEdge = maxOf(decoded.width, decoded.height)
        val scale = if (longEdge > MAX_IMAGE_EDGE_PX) MAX_IMAGE_EDGE_PX.toFloat() / longEdge else 1f
        val matrix = orientationMatrix(orientation).apply { postScale(scale, scale) }
        val bitmap = if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        try {
            val page = jpegPage(bitmap, bitmap.width.toFloat(), bitmap.height.toFloat())
            target.outputStream().use { JpegPdf.write(listOf(page), it) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * iOS's PdfCompactor at launch: any stored PDF over 8 MB is redrawn page by page as JPEG
     * pages, at most 2200 points on the long edge and rasterised at twice that, and replaced
     * when the result is smaller. Recipe pages stay readable and become convertible again.
     */
    fun compactOversized() {
        val oversized = dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") && f.length() > COMPACT_ABOVE_BYTES }
            .orEmpty()
        for (file in oversized) {
            val compacted = runCatching { compact(file) }.getOrNull() ?: continue
            if (compacted.size < file.length()) {
                val temp = File(file.parentFile, file.name + ".compacting")
                temp.writeBytes(compacted)
                if (!temp.renameTo(file)) temp.delete()
            }
        }
        if (oversized.isNotEmpty()) onChanged()
    }

    /**
     * What automatic naming sends: the file as it is when it has one page, otherwise its first
     * page alone as a JPEG page. A recipe's name is on its first page, and sending a long PDF
     * whole would cost far more for the same answer.
     */
    fun firstPageForNaming(fileName: String): ByteArray? = runCatching {
        val file = fileFor(fileName)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                when {
                    renderer.pageCount == 0 -> null
                    renderer.pageCount == 1 -> file.readBytes()
                    else -> {
                        val page = renderer.openPage(0).use(::jpegPageOf)
                        java.io.ByteArrayOutputStream().also { JpegPdf.write(listOf(page), it) }.toByteArray()
                    }
                }
            }
        }
    }.getOrNull()

    private fun compact(file: File): ByteArray? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0 || renderer.pageCount > COMPACT_MAX_PAGES) return null
                val pages = (0 until renderer.pageCount).map { index -> renderer.openPage(index).use(::jpegPageOf) }
                java.io.ByteArrayOutputStream().also { JpegPdf.write(pages, it) }.toByteArray()
            }
        }

    /** A page redrawn as JPEG, at most 2200 points on the long edge and rasterised at twice that. */
    private fun jpegPageOf(page: PdfRenderer.Page): JpegPdf.Page {
        val fit = minOf(1f, MAX_IMAGE_EDGE_PX.toFloat() / maxOf(page.width, page.height))
        val pageWidth = page.width * fit
        val pageHeight = page.height * fit
        val bitmap = Bitmap.createBitmap(
            (pageWidth * 2).toInt().coerceAtLeast(1),
            (pageHeight * 2).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val toBitmap = Matrix().apply { setScale(fit * 2, fit * 2) }
        page.render(bitmap, null, toBitmap, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return try {
            jpegPage(bitmap, pageWidth, pageHeight)
        } finally {
            bitmap.recycle()
        }
    }

    private fun jpegPage(bitmap: Bitmap, pageWidth: Float, pageHeight: Float): JpegPdf.Page {
        val jpeg = java.io.ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }.toByteArray()
        return JpegPdf.Page(jpeg, bitmap.width, bitmap.height, pageWidth, pageHeight)
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_EDGE_PX = 2200
        const val JPEG_QUALITY = 60
        const val COMPACT_ABOVE_BYTES = 8L * 1024 * 1024
        const val COMPACT_MAX_PAGES = 20
    }
}
