package uk.co.promptbuilt.notestodos.ui.recipes

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One page of one attached file, so a tapped page can be drawn again at reading size. */
private data class PageRef(val file: File, val index: Int)

/**
 * Native inline PDF viewing via PdfRenderer (replaces the web iframe).
 * Renders every file's pages sequentially — a recipe with several attached
 * PDFs reads as one continuous document. Tapping a page opens it full screen with
 * pinch-to-zoom, as iOS's PDFView allows in place.
 */
@Composable
fun PdfViewer(files: List<File>, modifier: Modifier = Modifier) {
    var pages by remember(files) { mutableStateOf<List<Pair<PageRef, Bitmap>>>(emptyList()) }
    var error by remember(files) { mutableStateOf<String?>(null) }
    var zoomed by remember(files) { mutableStateOf<PageRef?>(null) }

    val screenWidth = LocalContext.current.resources.displayMetrics.widthPixels

    LaunchedEffect(files) {
        withContext(Dispatchers.IO) {
            try {
                val rendered = mutableListOf<Pair<PageRef, Bitmap>>()
                for (file in files) {
                    if (rendered.size >= MAX_PAGES) break
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val pageCount = minOf(renderer.pageCount, MAX_PAGES - rendered.size)
                            for (i in 0 until pageCount) {
                                rendered.add(PageRef(file, i) to renderPage(renderer, i, screenWidth))
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
        itemsIndexed(pages) { _, (ref, bitmap) ->
            // The corner button is iOS's enlarge control; tapping the page itself works too.
            Box(modifier = Modifier.padding(vertical = 2.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Recipe page ${ref.index + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { zoomed = ref },
                    contentScale = ContentScale.FillWidth,
                )
                FilledTonalIconButton(
                    onClick = { zoomed = ref },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = "Enlarge page ${ref.index + 1}")
                }
            }
        }
    }

    zoomed?.let { ref -> ZoomedPage(ref, screenWidth, onClose = { zoomed = null }) }
}

/** Full-screen page: pinch to zoom, drag to pan, double-tap to zoom in or back out. */
@Composable
private fun ZoomedPage(ref: PageRef, screenWidth: Int, onClose: () -> Unit) {
    var bitmap by remember(ref) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(ref) { mutableFloatStateOf(1f) }
    var offset by remember(ref) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(ref) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(ref.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderPage(it, ref.index, (screenWidth * ZOOM_RENDER_FACTOR).toInt()) }
                }
            }.getOrNull()
        }
    }

    // Panning stops at the page's edges: the page is fitted inside the screen, so at a given
    // zoom it can travel only as far as it overhangs the screen on each axis.
    fun clamp(o: Offset, s: Float): Offset {
        val page = bitmap ?: return Offset.Zero
        if (box.width == 0 || box.height == 0) return Offset.Zero
        val fit = minOf(box.width.toFloat() / page.width, box.height.toFloat() / page.height)
        val maxX = ((page.width * fit * s - box.width) / 2).coerceAtLeast(0f)
        val maxY = ((page.height * fit * s - box.height) / 2).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(ref) { box = size }
                .pointerInput(ref) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        offset = clamp(offset + pan, next)
                        scale = next
                    }
                }
                .pointerInput(ref) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        offset = Offset.Zero
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            val page = bitmap
            if (page == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = "Recipe page ${ref.index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small),
            ) {
                Text("Close", color = Color.White)
            }
        }
    }
}

private fun renderPage(renderer: PdfRenderer, index: Int, targetWidth: Int): Bitmap {
    val page = renderer.openPage(index)
    try {
        val size = renderSize(page.width, page.height, targetWidth)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val matrix = Matrix().apply { setScale(size.scale, size.scale) }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    } finally {
        page.close()
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
private const val ZOOM_RENDER_FACTOR = 2.5f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
