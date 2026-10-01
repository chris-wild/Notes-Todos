package uk.co.promptbuilt.notestodos.ui.recipes

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One page of one attached file, with its size in PDF points, so it can be redrawn at any zoom. */
private data class PageRef(val file: File, val index: Int, val widthPt: Int, val heightPt: Int)

/** A sharp redraw of the part of a page that was on screen, placed in the page's own layout. */
private class Tile(val bitmap: Bitmap, val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The recipe's attachments as one continuous document, the counterpart of iOS's PDFView:
 * pages scroll vertically, pinch zooms, a zoomed page pans with momentum, and double-tap zooms
 * in or out. The enlarge button in each page's corner opens the same document full screen at
 * that page, as iOS's full-screen cover does.
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
                                val page = renderPage(renderer, i, screenWidth)
                                rendered.add(PageRef(file, i, page.widthPt, page.heightPt) to page.bitmap)
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
 * Pages in a lazy column under one zoom, with iOS's limits: PDFView allows scale factors from
 * 0.25 to 5 of a page's real size (measured in the iOS 18.5 simulator), so a large photographed
 * page zooms much further than a printed one, and a small page can shrink below the screen's
 * width. At 1x the column scrolls and flings natively. At any other zoom this takes over
 * single-finger drags: horizontal movement pans with momentum, and vertical movement first slides
 * the magnified window across the visible stretch of pages, then scrolls the column, so every
 * part of every page can be reached. Once movement stops, each visible page redraws the part on
 * screen at full resolution, as PDFView re-renders at every zoom.
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
    val density = LocalDensity.current.density
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var touching by remember { mutableStateOf(false) }
    var settled by remember { mutableIntStateOf(0) }
    val viewport = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var panFling by remember { mutableStateOf<Job?>(null) }

    // iOS's limits, converted from scale factors of the widest page to multiples of the width.
    val widestPt = pages.maxOfOrNull { it.first.widthPt } ?: 0
    val viewDp = width / density
    val maxZoom by rememberUpdatedState(if (viewDp > 0 && widestPt > 0) max(1f, IOS_MAX_SCALE * widestPt / viewDp) else 1f)
    val minZoom by rememberUpdatedState(if (viewDp > 0 && widestPt > 0) min(1f, IOS_MIN_SCALE * widestPt / viewDp) else 1f)

    // Above 1x the column is scaled about its top centre: horizontally it may travel half its
    // extra width either way. Below 1x it is narrower than the screen and stays centred.
    fun clampX(x: Float, s: Float): Float {
        if (s <= 1f) return 0f
        val limit = width * (s - 1) / 2
        return x.coerceIn(-limit, limit)
    }

    fun moveVertically(dy: Float) {
        if (scale <= 1f) {
            ty = 0f
            listState.dispatchRawDelta(-dy / scale)
            return
        }
        val minTy = -height * (scale - 1)
        val next = ty + dy
        when {
            next > 0f -> {
                ty = 0f
                listState.dispatchRawDelta(-next / scale)
            }
            next < minTy -> {
                ty = minTy
                listState.dispatchRawDelta(-(next - minTy) / scale)
            }
            else -> ty = next
        }
    }

    // Zooms to [next] while keeping the content under [focus] still on screen.
    fun zoomAbout(focus: Offset, next: Float) {
        val ratio = next / scale
        val half = width / 2f
        val wantedTx = focus.x - half - (focus.x - half - tx) * ratio
        val wantedTy = focus.y - (focus.y - ty) * ratio
        scale = next
        tx = clampX(wantedTx, next)
        ty = 0f
        moveVertically(wantedTy)
    }

    // Movement has stopped: let the visible pages redraw sharply.
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(scale, tx, ty, touching, listState.isScrollInProgress, listState.firstVisibleItemScrollOffset, listState.firstVisibleItemIndex) }
            .collectLatest { state ->
                if (state[3] == false && state[4] == false) {
                    delay(SETTLE_MS)
                    settled++
                }
            }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned {
                viewport[0] = it
                width = it.size.width
                height = it.size.height
            }
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    panFling?.cancel()
                    touching = true
                    val tracker = VelocityTracker()
                    var travelled = 0f
                    var dragged = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        val pan = event.calculatePan()
                        travelled += pan.getDistance()
                        val pinching = pressed >= 2
                        val dragging = pressed == 1 && scale != 1f && travelled > viewConfiguration.touchSlop
                        if (pinching) {
                            val zoom = event.calculateZoom()
                            zoomAbout(event.calculateCentroid(useCurrent = true), (scale * zoom).coerceIn(minZoom, maxZoom))
                            tx = clampX(tx + pan.x, scale)
                            moveVertically(pan.y)
                            event.changes.forEach { it.consume() }
                        } else if (dragging) {
                            dragged = true
                            event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            tx = clampX(tx + pan.x, scale)
                            moveVertically(pan.y)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    touching = false
                    if (dragged && scale != 1f) {
                        val velocity = tracker.calculateVelocity()
                        val zoomAtRelease = scale
                        scope.launch { listState.scroll { with(fling) { performFling(-velocity.y / zoomAtRelease) } } }
                        if (zoomAtRelease > 1f) {
                            panFling = scope.launch {
                                AnimationState(initialValue = tx, initialVelocity = velocity.x)
                                    .animateDecay(exponentialDecay()) { tx = clampX(value, scale) }
                            }
                        }
                    }
                }
            }
            .pointerInput(pages) {
                detectTapGestures(onDoubleTap = { at ->
                    zoomAbout(at, if (scale != 1f) 1f else min(DOUBLE_TAP_ZOOM, maxZoom))
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
                )
                // Zoomed out, the column is laid out taller so the shrunken pages still fill the screen.
                .layout { measurable, constraints ->
                    val tall = if (scale < 1f && constraints.hasBoundedHeight) {
                        (constraints.maxHeight / scale).roundToInt()
                    } else {
                        constraints.maxHeight
                    }
                    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, tall))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                },
        ) {
            itemsIndexed(pages) { index, (ref, preview) ->
                DocumentPage(
                    ref = ref,
                    preview = preview,
                    renderWidth = (screenWidth * BASE_RENDER_FACTOR).toInt(),
                    scale = scale,
                    settled = settled,
                    viewport = { viewport[0] },
                    onEnlarge = onEnlarge?.let { { it(index) } },
                )
            }
        }
    }
}

/**
 * A page shown from its screen-width preview, then a 2x render once on screen. Zoomed further,
 * the part of the page on screen is redrawn at screen resolution whenever movement stops.
 */
@Composable
private fun DocumentPage(
    ref: PageRef,
    preview: Bitmap,
    renderWidth: Int,
    scale: Float,
    settled: Int,
    viewport: () -> LayoutCoordinates?,
    onEnlarge: (() -> Unit)?,
) {
    val density = LocalDensity.current
    var base by remember(ref) { mutableStateOf<Bitmap?>(null) }
    var tile by remember(ref) { mutableStateOf<Tile?>(null) }
    val pageCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }

    LaunchedEffect(ref) {
        base = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(ref.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderPage(it, ref.index, renderWidth).bitmap }
                }
            }.getOrNull()
        }
    }

    LaunchedEffect(settled) {
        if (scale <= TILE_ABOVE_ZOOM) {
            tile = null
            return@LaunchedEffect
        }
        val page = pageCoordinates[0] ?: return@LaunchedEffect
        val view = viewport() ?: return@LaunchedEffect
        if (!page.isAttached || !view.isAttached) return@LaunchedEffect
        val onScreen = view.localBoundingBoxOf(page, clipBounds = false)
        val visible = onScreen.intersect(Rect(0f, 0f, view.size.width.toFloat(), view.size.height.toFloat()))
        if (visible.width < 1f || visible.height < 1f) return@LaunchedEffect
        val topLeft = page.localPositionOf(view, visible.topLeft)
        val bottomRight = page.localPositionOf(view, visible.bottomRight)
        val pxPerPt = onScreen.width / ref.widthPt
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                renderRegion(
                    ref,
                    visible.width.roundToInt(),
                    visible.height.roundToInt(),
                    pxPerPt,
                    onScreen.left - visible.left,
                    onScreen.top - visible.top,
                )
            }.getOrNull()
        } ?: return@LaunchedEffect
        tile = Tile(bitmap, topLeft.x, topLeft.y, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
    }

    Box(modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(preview.width.toFloat() / preview.height.coerceAtLeast(1))
                .onGloballyPositioned { pageCoordinates[0] = it },
        ) {
            Image(
                bitmap = (base ?: preview).asImageBitmap(),
                contentDescription = "Recipe page ${ref.index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            tile?.let { t ->
                Image(
                    bitmap = t.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .offset { IntOffset(t.x.roundToInt(), t.y.roundToInt()) }
                        .size(with(density) { t.width.toDp() }, with(density) { t.height.toDp() }),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
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

private class RenderedPage(val bitmap: Bitmap, val widthPt: Int, val heightPt: Int)

private fun renderPage(renderer: PdfRenderer, index: Int, targetWidth: Int): RenderedPage {
    val page = renderer.openPage(index)
    try {
        val size = renderSize(page.width, page.height, targetWidth)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val matrix = Matrix().apply { setScale(size.scale, size.scale) }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return RenderedPage(bitmap, page.width, page.height)
    } finally {
        page.close()
    }
}

/** Draws only the region of a page that is on screen, at [pxPerPt], offset by the page's position. */
private fun renderRegion(ref: PageRef, width: Int, height: Int, pxPerPt: Float, offsetX: Float, offsetY: Float): Bitmap {
    ParcelFileDescriptor.open(ref.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val page = renderer.openPage(ref.index)
            try {
                val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val matrix = Matrix().apply {
                    setScale(pxPerPt, pxPerPt)
                    postTranslate(offsetX, offsetY)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } finally {
                page.close()
            }
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
private const val BASE_RENDER_FACTOR = 2f
private const val TILE_ABOVE_ZOOM = 1.8f
private const val SETTLE_MS = 150L
private const val IOS_MAX_SCALE = 5f
private const val IOS_MIN_SCALE = 0.25f
private const val DOUBLE_TAP_ZOOM = 2.5f
