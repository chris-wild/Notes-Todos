package uk.co.promptbuilt.notestodos.backup

import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual fun deflateRaw(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ArrayList<ByteArray>()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) {
                out.add(buffer.copyOf(n))
                total += n
            }
        }
        val result = ByteArray(total)
        var pos = 0
        for (chunk in out) {
            chunk.copyInto(result, pos)
            pos += chunk.size
        }
        return result
    } finally {
        deflater.end()
    }
}

internal actual fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val result = ByteArray(expectedSize)
        var pos = 0
        while (pos < expectedSize && !inflater.finished()) {
            val n = inflater.inflate(result, pos, expectedSize - pos)
            if (n == 0 && inflater.needsInput()) break
            pos += n
        }
        check(pos == expectedSize) { "Zip entry inflated to $pos bytes, expected $expectedSize" }
        return result
    } finally {
        inflater.end()
    }
}

internal actual fun crc32(data: ByteArray): Long {
    val crc = CRC32()
    crc.update(data)
    return crc.value
}
