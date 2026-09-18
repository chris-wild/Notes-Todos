package uk.co.promptbuilt.notestodos.backup

/**
 * Minimal zip container over the platform deflate/crc primitives — one common
 * codepath on Android, iOS, and the host JVM.
 *
 * Reading parses via the CENTRAL DIRECTORY, never by streaming local headers:
 * Android's ZipOutputStream writes DEFLATED entries with general-purpose bit 3
 * set (sizes/CRC live in a data descriptor after the data, zeros in the local
 * header), so local-header streaming would read zero-length entries from
 * Android-written backups. Python's zipfile and this codec's own writer put
 * sizes in the headers; all three variants read correctly through the central
 * directory.
 *
 * Writing computes CRC/sizes up front and emits no data descriptors, which
 * java.util.zip and Python both read. No zip64 (backups are ~tens of MB).
 */
internal object ZipCodec {

    private const val LOCAL_SIG = 0x04034b50
    private const val CENTRAL_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    // Fixed DOS timestamp (2026-01-01 00:00): readers ignore it; keeps output deterministic.
    private const val DOS_TIME = 0
    private const val DOS_DATE = ((2026 - 1980) shl 9) or (1 shl 5) or 1

    fun write(entries: Map<String, ByteArray>): ByteArray {
        class Prepared(
            val nameBytes: ByteArray,
            val data: ByteArray,
            val method: Int,
            val crc: Long,
            val uncompressedSize: Int,
            var localHeaderOffset: Int = 0,
        )

        val prepared = entries.map { (name, raw) ->
            // Only JSON entries are worth deflating; PDFs are already compressed,
            // and skipping them keeps `data` a REFERENCE to the caller's bytes
            // instead of a transient deflated copy (a 60 MB backup would
            // otherwise hold raw + deflated + output simultaneously — OOM on
            // default Android heaps).
            val deflated = if (name.endsWith(".json")) deflateRaw(raw) else null
            val useDeflate = deflated != null && deflated.size < raw.size
            Prepared(
                nameBytes = name.encodeToByteArray(),
                data = if (useDeflate) deflated else raw,
                method = if (useDeflate) METHOD_DEFLATED else METHOD_STORED,
                crc = crc32(raw),
                uncompressedSize = raw.size,
            )
        }

        // Exact final size, so the builder never reallocates (the doubling copy
        // was the other half of the OOM).
        val exactSize = prepared.sumOf { 30 + it.nameBytes.size + it.data.size } +
            prepared.sumOf { 46 + it.nameBytes.size } + 22
        val out = ByteArrayBuilder(exactSize)
        for (p in prepared) {
            p.localHeaderOffset = out.size
            out.u32(LOCAL_SIG)
            out.u16(20)             // version needed
            out.u16(0)              // flags (no data descriptor)
            out.u16(p.method)
            out.u16(DOS_TIME)
            out.u16(DOS_DATE)
            out.u32(p.crc.toInt())
            out.u32(p.data.size)
            out.u32(p.uncompressedSize)
            out.u16(p.nameBytes.size)
            out.u16(0)              // extra len
            out.bytes(p.nameBytes)
            out.bytes(p.data)
        }

        val centralStart = out.size
        for (p in prepared) {
            out.u32(CENTRAL_SIG)
            out.u16(20)             // version made by
            out.u16(20)             // version needed
            out.u16(0)              // flags
            out.u16(p.method)
            out.u16(DOS_TIME)
            out.u16(DOS_DATE)
            out.u32(p.crc.toInt())
            out.u32(p.data.size)
            out.u32(p.uncompressedSize)
            out.u16(p.nameBytes.size)
            out.u16(0)              // extra len
            out.u16(0)              // comment len
            out.u16(0)              // disk number
            out.u16(0)              // internal attrs
            out.u32(0)              // external attrs
            out.u32(p.localHeaderOffset)
            out.bytes(p.nameBytes)
        }
        val centralSize = out.size - centralStart

        out.u32(EOCD_SIG)
        out.u16(0)                  // disk
        out.u16(0)                  // central dir start disk
        out.u16(prepared.size)
        out.u16(prepared.size)
        out.u32(centralSize)
        out.u32(centralStart)
        out.u16(0)                  // comment len
        return out.toByteArray()
    }

    fun read(bytes: ByteArray): Map<String, ByteArray> {
        val eocd = findEocd(bytes)
        val entryCount = bytes.u16(eocd + 10)
        var pos = bytes.u32(eocd + 16)

        val result = LinkedHashMap<String, ByteArray>()
        repeat(entryCount) {
            check(bytes.u32(pos) == CENTRAL_SIG) { "Bad central directory entry at $pos" }
            val method = bytes.u16(pos + 10)
            val compressedSize = bytes.u32(pos + 20)
            val uncompressedSize = bytes.u32(pos + 24)
            val nameLen = bytes.u16(pos + 28)
            val extraLen = bytes.u16(pos + 30)
            val commentLen = bytes.u16(pos + 32)
            val localOffset = bytes.u32(pos + 42)
            val name = bytes.decodeToString(pos + 46, pos + 46 + nameLen)

            check(bytes.u32(localOffset) == LOCAL_SIG) { "Bad local header for $name" }
            val localNameLen = bytes.u16(localOffset + 26)
            val localExtraLen = bytes.u16(localOffset + 28)
            val dataStart = localOffset + 30 + localNameLen + localExtraLen
            val data = bytes.copyOfRange(dataStart, dataStart + compressedSize)

            if (!name.endsWith("/")) {
                result[name] = when (method) {
                    METHOD_STORED -> data
                    METHOD_DEFLATED -> inflateRaw(data, uncompressedSize)
                    else -> throw IllegalStateException("Unsupported zip method $method for $name")
                }
            }
            pos += 46 + nameLen + extraLen + commentLen
        }
        return result
    }

    private fun findEocd(bytes: ByteArray): Int {
        // EOCD is at least 22 bytes from the end; the trailing comment can push
        // it back up to 64KB. Scan backwards for the signature.
        val start = bytes.size - 22
        val floor = maxOf(0, bytes.size - 22 - 0xFFFF)
        for (i in start downTo floor) {
            if (bytes.u32(i) == EOCD_SIG) return i
        }
        throw IllegalStateException("Not a zip file (no end-of-central-directory record)")
    }

    // ---- little-endian helpers ----

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private class ByteArrayBuilder(initialCapacity: Int = 1 shl 16) {
        private var buffer = ByteArray(initialCapacity)
        var size = 0
            private set

        private fun ensure(extra: Int) {
            if (size + extra > buffer.size) {
                var newSize = buffer.size * 2
                while (size + extra > newSize) newSize *= 2
                buffer = buffer.copyOf(newSize)
            }
        }

        fun u16(v: Int) {
            ensure(2)
            buffer[size++] = (v and 0xFF).toByte()
            buffer[size++] = ((v ushr 8) and 0xFF).toByte()
        }

        fun u32(v: Int) {
            ensure(4)
            buffer[size++] = (v and 0xFF).toByte()
            buffer[size++] = ((v ushr 8) and 0xFF).toByte()
            buffer[size++] = ((v ushr 16) and 0xFF).toByte()
            buffer[size++] = ((v ushr 24) and 0xFF).toByte()
        }

        fun bytes(b: ByteArray) {
            ensure(b.size)
            b.copyInto(buffer, size)
            size += b.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
