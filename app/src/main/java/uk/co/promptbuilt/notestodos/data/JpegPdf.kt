package uk.co.promptbuilt.notestodos.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Writes a PDF whose pages are JPEG images, passed through as DCT streams. Android's
 * PdfDocument stores drawn bitmaps losslessly (FlateDecode), which made photographed recipes
 * several times larger than iOS's; iOS's ImageToPDF and PdfCompactor embed JPEG, and this is
 * the same thing written by hand.
 */
object JpegPdf {

    /** One page: [jpeg] is a baseline RGB JPEG of [pixelWidth] x [pixelHeight], drawn to fill the page. */
    class Page(val jpeg: ByteArray, val pixelWidth: Int, val pixelHeight: Int, val pageWidth: Float, val pageHeight: Float)

    fun write(pages: List<Page>, out: OutputStream) {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val buffer = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        fun ascii(text: String) = buffer.write(text.toByteArray(Charsets.US_ASCII))
        fun beginObject(number: Int) {
            while (offsets.size < number) offsets.add(0)
            offsets[number - 1] = buffer.size()
            ascii("$number 0 obj\n")
        }

        // Objects: 1 catalog, 2 page tree, then per page (page, contents, image).
        val pageObject = { index: Int -> 3 + index * 3 }
        ascii("%PDF-1.4\n")
        // A comment of high bytes marks the file as binary, as the PDF specification recommends.
        buffer.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        beginObject(1)
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObject(2)
        val kids = pages.indices.joinToString(" ") { "${pageObject(it)} 0 R" }
        ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

        pages.forEachIndexed { index, page ->
            val pageNumber = pageObject(index)
            val contentsNumber = pageNumber + 1
            val imageNumber = pageNumber + 2
            val w = fmt(page.pageWidth)
            val h = fmt(page.pageHeight)

            beginObject(pageNumber)
            ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] " +
                    "/Resources << /XObject << /Im0 $imageNumber 0 R >> >> /Contents $contentsNumber 0 R >>\nendobj\n",
            )

            val drawing = "q $w 0 0 $h 0 0 cm /Im0 Do Q".toByteArray(Charsets.US_ASCII)
            beginObject(contentsNumber)
            ascii("<< /Length ${drawing.size} >>\nstream\n")
            buffer.write(drawing)
            ascii("\nendstream\nendobj\n")

            beginObject(imageNumber)
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${page.pixelWidth} /Height ${page.pixelHeight} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n",
            )
            buffer.write(page.jpeg)
            ascii("\nendstream\nendobj\n")
        }

        val xref = buffer.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { ascii("%010d 00000 n \n".format(it)) }
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buffer.writeTo(out)
    }

    private fun fmt(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)
}
