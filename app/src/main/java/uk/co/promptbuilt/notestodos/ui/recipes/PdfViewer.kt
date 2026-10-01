package uk.co.promptbuilt.notestodos.ui.recipes

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One page of one attached file, so a page can be drawn again at reading size. */
private data class PageRef(val file: File, val index: Int)

/**
 * The recipe's attachments as one continuous document, the counterpart of iOS's PDFView:
 * pages scroll vertically, pinch zooms, a zoomed page pans, and double-tap zooms in or out.
 * The enlarge button in each page's corner opens the same document full screen at that page,
 * as iOS's full-screen cover does.
 */
@Composable
fun PdfViewer(files: List<File>, modifier: Modifier = Modifier) {
    var pages by remember(files) { mutableStateOf<List<Pair<PageRef, Bitmap>>>(emptyList()) }
    var error by remember(files) { mutableStateOf<String?>(null) }
    var popOutAt by remember(files) { mutableStateOf<Int?>(null) }

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

    ZoomableDocument(
        pages = pages,
        screenWidth = screenWidth,
        startIndex = 0,
        onEnlarge = { popOutAt = it },
        modifier = modifier,
    )

    popOutAt?.let { start ->
        Dialog(
            onDismissRequest = { popOutAt = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                ZoomableDocument(
                    pages = pages,
                    screenWidth = screenWidth,
                    startIndex = start,
                    onEnlarge = null,
                    modifier = Modifier.fillMaxSize(),
                )
                TextButton(
                    onClick = { popOutAt = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small),
                ) {
                    Text("Done", color = Color.White)
                }
            }
        }
    }
}

/**
 * Pages in a lazy column under one zoom. At 1x the column scrolls and flings natively. Once
 * zoomed, this takes over single-finger drags: horizontal movement pans, and vertical movement
 * first slides the magnified window across the visible stretch of pages, then scrolls the
 * column, so every part of every page can be reached.
 */
@Composable
private fun ZoomableDocument(
    pages: List<Pair<PageRef, Bitmap>>,
    screenWidth: Int,
    startIndex: Int,
    onEnlarge: ((Int) -> Unit)?,
    modifier: Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }

    // The column is scaled about its top centre: horizontally it may travel half its extra
    // width either way; vertically the magnified window spans height * (scale - 1).
    fun clampX(x: Float, s: Float): Float {
        val max = width * (s - 1) / 2
        return x.coerceIn(-max, max)
    }

    fun moveVertically(state: LazyListState, dy: Float) {
        val minTy = -height * (scale - 1)
        val next = ty + dy
        when {
            next > 0f -> {
                ty = 0f
                state.dispatchRawDelta(-next / scale)
            }
            next < minTy -> {
                ty = minTy
                state.dispatchRawDelta(-(next - minTy) / scale)
            }
            else -> ty = next
        }
    }

    // Zooms to [next] while keeping the content under [focus] still on screen.
    fun zoomAbout(focus: Offset, next: Float) {
        val previous = scale
        val ratio = next / previous
        val half = width / 2f
        tx = clampX(focus.x - half - (focus.x - half - tx) * ratio, next)
        val wantedTy = focus.y - (focus.y - ty) * ratio
        scale = next
        ty = 0f
        moveVertically(listState, wantedTy)
        if (next == 1f) {
            tx = 0f
            ty = 0f
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged {
                width = it.width
                height = it.height
            }
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tracker = VelocityTracker()
                    var travelled = 0f
                    var draggedZoomed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        val pan = event.calculatePan()
                        travelled += pan.getDistance()
                        val pinching = pressed >= 2
                        val dragging = pressed == 1 && scale > 1f && travelled > viewConfiguration.touchSlop
                        if (pinching) {
                            val zoom = event.calculateZoom()
                            zoomAbout(event.calculateCentroid(useCurrent = true), (scale * zoom).coerceIn(1f, MAX_ZOOM))
                            tx = clampX(tx + pan.x, scale)
                            moveVertically(listState, pan.y)
                            event.changes.forEach { it.consume() }
                        } else if (dragging) {
                            draggedZoomed = true
                            event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            tx = clampX(tx + pan.x, scale)
                            moveVertically(listState, pan.y)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (draggedZoomed && scale > 1f) {
                        val velocity = tracker.calculateVelocity().y / scale
                        scope.launch { listState.scroll { with(fling) { performFling(-velocity) } } }
                    }
                }
            }
            .pointerInput(pages) {
                detectTapGestures(onDoubleTap = { at ->
                    zoomAbout(at, if (scale > 1f) 1f else DOUBLE_TAP_ZOOM)
                })
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = tx,
                    translationY = ty,
                    transformOrigin = TransformOrigin(0.5f, 0f),
                ),
        ) {
            itemsIndexed(pages) { index, (ref, preview) ->
                DocumentPage(
                    ref = ref,
                    preview = preview,
                    renderWidth = (screenWidth * ZOOM_RENDER_FACTOR).toInt(),
                    onEnlarge = onEnlarge?.let { { it(index) } },
                )
            }
        }
    }
}

/** A page shown from its screen-width preview, sharpened to reading resolution once on screen. */
@Composable
private fun DocumentPage(ref: PageRef, preview: Bitmap, renderWidth: Int, onEnlarge: (() -> Unit)?) {
    var sharp by remember(ref) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ref) {
        sharp = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(ref.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderPage(it, ref.index, renderWidth) }
                }
            }.getOrNull()
        }
    }
    Box(modifier = Modifier.padding(vertical = 2.dp)) {
        Image(
            bitmap = (sharp ?: preview).asImageBitmap(),
            contentDescription = "Recipe page ${ref.index + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(preview.width.toFloat() / preview.height.coerceAtLeast(1)),
            contentScale = ContentScale.FillWidth,
        )
        if (onEnlarge != null) {
            FilledTonalIconButton(
                onClick = onEnlarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.OpenInFull, contentDescription = "Enlarge page ${ref.index + 1}")
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
private const val ZOOM_RENDER_FACTOR = 2f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
