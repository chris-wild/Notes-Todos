package uk.co.promptbuilt.notestodos.data

/**
 * Byte-level store for recipe attachment PDFs, keyed by file name.
 * Android: app-side RecipeFiles (filesDir/recipes/). iOS: iosMain
 * NSFileManager implementation. Image->PDF conversion is NOT part of this
 * interface — it stays platform-side (Android BitmapFactory/PdfDocument,
 * iOS UIGraphicsPDFRenderer in Swift).
 */
interface RecipeStore {
    fun read(fileName: String): ByteArray?
    fun write(fileName: String, bytes: ByteArray)
    fun delete(fileName: String)
    fun clearAll()
}
