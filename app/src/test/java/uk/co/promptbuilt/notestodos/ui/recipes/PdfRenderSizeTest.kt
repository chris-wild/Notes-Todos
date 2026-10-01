package uk.co.promptbuilt.notestodos.ui.recipes

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PdfRenderSizeTest {

    private val screenWidth = 1116

    @Test
    fun aPhotographedPageFitsTheScreenInsteadOfDoubling() {
        // The OnePlus crash: a 4000 x 3008 point photo page rendered at 2x was 192 MB.
        val size = renderSize(4000, 3008, screenWidth)
        assertEquals(screenWidth, size.width)
        assertEquals(839, size.height)
        assertTrue(size.width.toLong() * size.height * 4 < 100_000_000)
    }

    @Test
    fun anA4PageRendersAtScreenWidth() {
        val size = renderSize(595, 842, screenWidth)
        assertEquals(screenWidth, size.width)
        assertEquals(1579, size.height)
    }

    @Test
    fun aVeryTallPageIsCappedByPixelCount() {
        val size = renderSize(595, 40_000, screenWidth)
        assertTrue(size.width.toLong() * size.height <= 12_000_000)
        assertTrue(size.width < screenWidth)
    }
}
