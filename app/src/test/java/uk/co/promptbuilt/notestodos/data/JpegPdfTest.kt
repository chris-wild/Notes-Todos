package uk.co.promptbuilt.notestodos.data

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegPdfTest {

    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun pdf(vararg pages: JpegPdf.Page): String {
        val out = ByteArrayOutputStream()
        JpegPdf.write(pages.toList(), out)
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    @Test
    fun aPhotoPageCarriesItsJpegUnchangedAsADctStream() {
        val text = pdf(JpegPdf.Page(fakeJpeg, 1650, 2200, 1650f, 2200f))
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Filter /DCTDecode /Length 7"))
        assertTrue(text.contains("/Width 1650 /Height 2200"))
        assertTrue(text.contains("/MediaBox [0 0 1650 2200]"))
        assertTrue(text.contains(String(fakeJpeg, Charsets.ISO_8859_1)))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun crossReferenceOffsetsPointAtTheirObjects() {
        val text = pdf(
            JpegPdf.Page(fakeJpeg, 10, 20, 5f, 10f),
            JpegPdf.Page(fakeJpeg, 30, 40, 15f, 20.5f),
        )
        val xrefAt = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        val entries = text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(8, entries.size)
        entries.forEachIndexed { index, line ->
            val offset = line.substring(0, 10).toInt()
            assertTrue("object ${index + 1}", text.startsWith("${index + 1} 0 obj", offset))
        }
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 15 20.50]"))
    }
}
