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
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native inline PDF viewing via PdfRenderer (replaces the web iframe). */
@Composable
fun PdfViewer(file: File, modifier: Modifier = Modifier) {
    var pages by remember(file) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val rendered = mutableListOf<Bitmap>()
                        val pageCount = minOf(renderer.pageCount, MAX_PAGES)
                        for (i in 0 until pageCount) {
                            val page = renderer.openPage(i)
                            try {
                                val bitmap = Bitmap.createBitmap(
                                    page.width * RENDER_SCALE,
                                    page.height * RENDER_SCALE,
                                    Bitmap.Config.ARGB_8888,
                                )
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                val matrix = Matrix().apply {
                                    setScale(RENDER_SCALE.toFloat(), RENDER_SCALE.toFloat())
                                }
                                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                rendered.add(bitmap)
                            } finally {
                                page.close()
                            }
                        }
                        pages = rendered
                    }
                }
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

private const val RENDER_SCALE = 2
private const val MAX_PAGES = 50
