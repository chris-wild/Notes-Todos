package uk.co.promptbuilt.notestodos.ai

import uk.co.promptbuilt.notestodos.data.SecureKeys

/**
 * How recipe OCR is paid for: either the user's own Anthropic key (BYO, the Android app and
 * iOS Debug builds) or the metered Worker proxy holding the shared key (released iOS).
 * IngredientTodosUseCase neither knows nor cares which.
 */
interface OcrService {
    suspend fun extractIngredientsFromPdf(pdfBytes: ByteArray, recipeName: String): List<String>
    suspend fun extractIngredientsFromText(text: String, recipeName: String): List<String>

    /** A short title for a photographed recipe, or null when naming fails (callers keep their fallback). */
    suspend fun extractRecipeTitle(pdfBytes: ByteArray): String?
}

/** The bring-your-own-key path: the untouched direct Anthropic client plus the stored key. */
class ByoOcrService(
    private val client: AnthropicClient,
    private val secureKeys: SecureKeys,
) : OcrService {

    private fun key(): String = secureKeys.getAnthropicKey()
        ?: throw IllegalStateException("No Anthropic API key configured. Add one in Settings.")

    override suspend fun extractIngredientsFromPdf(pdfBytes: ByteArray, recipeName: String): List<String> =
        client.extractIngredientsFromPdf(pdfBytes, recipeName, key())

    override suspend fun extractIngredientsFromText(text: String, recipeName: String): List<String> =
        client.extractIngredientsFromText(text, recipeName, key())

    override suspend fun extractRecipeTitle(pdfBytes: ByteArray): String? {
        val apiKey = secureKeys.getAnthropicKey() ?: return null
        return client.extractRecipeTitle(pdfBytes, apiKey)
    }
}

/** Picks an implementation per call, so storing or removing a dev key needs no rebuild of the graph. */
class SwitchingOcrService(private val choose: () -> OcrService) : OcrService {
    override suspend fun extractIngredientsFromPdf(pdfBytes: ByteArray, recipeName: String): List<String> =
        choose().extractIngredientsFromPdf(pdfBytes, recipeName)

    override suspend fun extractIngredientsFromText(text: String, recipeName: String): List<String> =
        choose().extractIngredientsFromText(text, recipeName)

    override suspend fun extractRecipeTitle(pdfBytes: ByteArray): String? =
        choose().extractRecipeTitle(pdfBytes)
}
