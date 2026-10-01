package uk.co.promptbuilt.notestodos.ui.recipes

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlin.math.sqrt
import kotlinx.coroutines.withContext

/**
 * Native inline PDF viewing via PdfRenderer (replaces the web iframe).
 * Renders every file's pages sequentially — a recipe with several attached
 * PDFs reads as one continuous document.
 */
@Composable
fun PdfViewer(files: List<File>, modifier: Modifier = Modifier) {
    var pages by remember(files) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var error by remember(files) { mutableStateOf<String?>(null) }

    val screenWidth = LocalContext.current.resources.displayMetrics.widthPixels

    LaunchedEffect(files) {
        withContext(Dispatchers.IO) {
            try {
                val rendered = mutableListOf<Bitmap>()
                for (file in files) {
                    if (rendered.size >= MAX_PAGES) break
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val pageCount = minOf(renderer.pageCount, MAX_PAGES - rendered.size)
                            for (i in 0 until pageCount) {
                                val page = renderer.openPage(i)
                                try {
                                    val size = renderSize(page.width, page.height, screenWidth)
                                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    val matrix = Matrix().apply { setScale(size.scale, size.scale) }
                                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    rendered.add(bitmap)
                                } finally {
                                    page.close()
                                }
                            }
                        }
                    }
                }
                pages = rendered
            } catch (e: Exception) {
                error = "Could not render PDF: ${e.message}"
            }
        }
    }

    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    LazyColumn(modifier = modifier) {
        items(pages) { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

internal data class RenderSize(val width: Int, val height: Int, val scale: Float)

/**
 * Pages are drawn at the screen's width, whatever their size in points: a photographed page
 * can be thousands of points across, and a fixed render scale then asks for a bitmap Android
 * refuses to draw (anything over about 100 MB). Very tall pages are capped by pixel count.
 */
internal fun renderSize(pageWidth: Int, pageHeight: Int, targetWidth: Int): RenderSize {
    var scale = targetWidth.toFloat() / pageWidth.coerceAtLeast(1)
    val pixels = targetWidth.toDouble() * pageHeight * scale
    if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels).toFloat()
    return RenderSize(
        width = (pageWidth * scale).toInt().coerceAtLeast(1),
        height = (pageHeight * scale).toInt().coerceAtLeast(1),
        scale = scale,
    )
}

private const val MAX_PIXELS = 12_000_000.0
private const val MAX_PAGES = 50
