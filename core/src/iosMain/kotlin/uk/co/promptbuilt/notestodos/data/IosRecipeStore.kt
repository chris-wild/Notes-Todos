@file:OptIn(ExperimentalForeignApi::class)

package uk.co.promptbuilt.notestodos.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/** Recipe PDFs in Application Support/recipes/. */
class IosRecipeStore : RecipeStore {

    private val dir: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory, NSUserDomainMask, true,
        ).first() as String
        val path = "$base/recipes"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path, withIntermediateDirectories = true, attributes = null, error = null,
        )
        path
    }

    /** Absolute file path, for Swift-side consumers (PDFKit, the image->PDF writer). */
    fun path(fileName: String): String = "$dir/$fileName"

    @OptIn(ExperimentalForeignApi::class)
    override fun read(fileName: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path(fileName)) ?: return null
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        return ByteArray(size).apply {
            usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun write(fileName: String, bytes: ByteArray) {
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned {
                NSData.create(bytes = it.addressOf(0), length = bytes.size.convert())
            }
        }
        data.writeToFile(path(fileName), atomically = true)
    }

    override fun delete(fileName: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(fileName), error = null)
    }

    override fun clearAll() {
        val fm = NSFileManager.defaultManager
        @Suppress("UNCHECKED_CAST")
        val names = fm.contentsOfDirectoryAtPath(dir, error = null) as? List<String> ?: return
        for (name in names) fm.removeItemAtPath("$dir/$name", error = null)
    }
}
