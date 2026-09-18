package uk.co.promptbuilt.notestodos.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipCodecTest {

    @Test
    fun roundTripsMixedEntries() {
        val entries = linkedMapOf(
            "data/small.json" to """{"hello":"world"}""".encodeToByteArray(),
            // Highly compressible -> exercises the DEFLATED path
            "data/big.json" to "abcdefgh".repeat(10_000).encodeToByteArray(),
            // Random-ish bytes that won't compress -> exercises the STORED path
            "recipes/blob.pdf" to ByteArray(4096) { ((it * 131) and 0xFF).toByte() },
            // Non-ASCII entry name
            "recipes/crème brûlée.pdf" to "%PDF".encodeToByteArray(),
        )
        val zip = ZipCodec.write(entries)
        val back = ZipCodec.read(zip)

        assertEquals(entries.keys, back.keys)
        for ((name, bytes) in entries) {
            assertContentEquals(bytes, back[name], "entry $name")
        }
    }

    @Test
    fun roundTripsEmptyArchive() {
        assertEquals(emptyMap(), ZipCodec.read(ZipCodec.write(emptyMap())))
    }

    @Test
    fun rejectsNonZipBytes() {
        assertFailsWith<IllegalStateException> {
            ZipCodec.read("definitely not a zip".encodeToByteArray())
        }
    }
}
