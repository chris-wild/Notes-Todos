package uk.co.promptbuilt.notestodos.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.crc32
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

// windowBits -15 = raw (headerless) DEFLATE, matching java.util.zip's nowrap mode.
private const val RAW_WINDOW_BITS = -15

@OptIn(ExperimentalForeignApi::class)
internal actual fun deflateRaw(data: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    check(
        deflateInit2_(
            stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, RAW_WINDOW_BITS, 8, Z_DEFAULT_STRATEGY,
            zlibVersion()?.toKString(), sizeOf<z_stream>().toInt(),
        ) == Z_OK,
    ) { "deflateInit2 failed" }
    try {
        val bound = deflateBound(stream.ptr, data.size.convert()).toInt()
        val out = ByteArray(bound)
        val produced: Int
        if (data.isEmpty()) {
            out.usePinned { output ->
                stream.next_in = null
                stream.avail_in = 0u
                stream.next_out = output.addressOf(0).reinterpret()
                stream.avail_out = bound.convert()
                check(deflate(stream.ptr, Z_FINISH) == Z_STREAM_END) { "deflate failed" }
            }
            produced = stream.total_out.toInt()
        } else {
            data.usePinned { input ->
                out.usePinned { output ->
                    stream.next_in = input.addressOf(0).reinterpret()
                    stream.avail_in = data.size.convert()
                    stream.next_out = output.addressOf(0).reinterpret()
                    stream.avail_out = bound.convert()
                    check(deflate(stream.ptr, Z_FINISH) == Z_STREAM_END) { "deflate failed" }
                }
            }
            produced = stream.total_out.toInt()
        }
        out.copyOf(produced)
    } finally {
        deflateEnd(stream.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray {
    if (expectedSize == 0) return ByteArray(0)
    return memScoped {
        val stream = alloc<z_stream>()
        check(
            inflateInit2_(
                stream.ptr, RAW_WINDOW_BITS,
                zlibVersion()?.toKString(), sizeOf<z_stream>().toInt(),
            ) == Z_OK,
        ) { "inflateInit2 failed" }
        try {
            val out = ByteArray(expectedSize)
            data.usePinned { input ->
                out.usePinned { output ->
                    stream.next_in = input.addressOf(0).reinterpret()
                    stream.avail_in = data.size.convert()
                    stream.next_out = output.addressOf(0).reinterpret()
                    stream.avail_out = expectedSize.convert()
                    val rc = inflate(stream.ptr, Z_FINISH)
                    check(rc == Z_STREAM_END) { "inflate failed (rc=$rc)" }
                }
            }
            check(stream.total_out.toInt() == expectedSize) {
                "Zip entry inflated to ${stream.total_out} bytes, expected $expectedSize"
            }
            out
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun crc32(data: ByteArray): Long {
    if (data.isEmpty()) return 0L
    return data.usePinned { pinned ->
        crc32(0u.convert(), pinned.addressOf(0).reinterpret(), data.size.convert()).toLong()
    }
}
