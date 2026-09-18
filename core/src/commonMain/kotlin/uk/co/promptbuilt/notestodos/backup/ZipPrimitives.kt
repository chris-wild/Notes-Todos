package uk.co.promptbuilt.notestodos.backup

/** Raw (headerless) DEFLATE. */
internal expect fun deflateRaw(data: ByteArray): ByteArray

/** Inflates a raw DEFLATE stream; expectedSize is the known uncompressed size. */
internal expect fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray

/** IEEE CRC-32 as an unsigned value in a Long. */
internal expect fun crc32(data: ByteArray): Long
