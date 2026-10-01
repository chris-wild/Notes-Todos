package uk.co.promptbuilt.notestodos.ai

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The metered pack is short: the recipe needs [needed] ops but only [balance] remain.
 * The message doubles as the user-facing copy; Swift also matches its prefix to open the
 * paywall (see RecipesModel), so change the wording in both places or not at all.
 */
class InsufficientOpsException(val needed: Int, val balance: Int) :
    Exception("Not enough conversion credits: need $needed, have $balance")

/**
 * OCR via the HobPad metering Worker (backend/ops): the request carries only the anonymous
 * account token — never an Anthropic key — and the reply is Anthropic's own Messages JSON
 * passed through verbatim, so parsing here mirrors AnthropicClient. Prompts, model and
 * billing all live server-side.
 */
class MeteredOcrClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String,
    /** "metric" / "us" (or null for no conversion): rides as ?units= and the Worker adds
     *  the conversion clause (backend/ops/src/index.js unitsClause — keep in step). */
    private val unitsProvider: () -> String? = { null },
) : OcrService {

    private fun unitsParam(): String =
        unitsProvider()?.let { "&units=${percentEncode(it)}" } ?: ""


    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun extractIngredientsFromPdf(pdfBytes: ByteArray, recipeName: String): List<String> {
        val reply = send("/v1/extract?name=${percentEncode(recipeName)}${unitsParam()}", Base64.encode(pdfBytes))
        return IngredientParsing.parseResponse("{" + contentText(reply))
    }

    override suspend fun extractIngredientsFromText(text: String, recipeName: String): List<String> {
        val reply = send("/v1/extract-text?name=${percentEncode(recipeName)}${unitsParam()}", text)
        return IngredientParsing.parseResponse("{" + contentText(reply))
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun extractRecipeTitle(pdfBytes: ByteArray): String? {
        return try {
            val reply = send("/v1/title", Base64.encode(pdfBytes))
            val out = "{" + contentText(reply)
            ((Json.parseToJsonElement(out).jsonObject["title"] as? JsonPrimitive)?.content)
                ?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun send(path: String, body: String): HttpReply {
        val reply = httpSend(
            method = "POST",
            url = baseUrl + path,
            headers = mapOf(
                "Authorization" to "Bearer ${tokenProvider()}",
                "Content-Type" to "text/plain",
            ),
            body = body,
            // The Worker relays to Anthropic and only then answers; a multi-page PDF can
            // legitimately take a while longer than a direct call.
            timeoutSeconds = 180,
        )
        if (reply.status == 402) {
            val fields = try {
                Json.parseToJsonElement(reply.body).jsonObject
            } catch (_: Exception) {
                null
            }
            throw InsufficientOpsException(
                needed = (fields?.get("needed") as? JsonPrimitive)?.content?.toIntOrNull() ?: 1,
                balance = (fields?.get("balance") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            )
        }
        return reply
    }

    /** The Worker's success body is Anthropic's, so this mirrors AnthropicClient.complete. */
    private fun contentText(reply: HttpReply): String {
        if (reply.status !in 200..299) {
            val message = try {
                ((Json.parseToJsonElement(reply.body).jsonObject["error"] as? JsonObject)
                    ?.get("message") as? JsonPrimitive)?.content
            } catch (_: Exception) {
                null
            } ?: reply.body
            throw IllegalStateException("Claude extract failed: ${reply.status} $message")
        }
        return (Json.parseToJsonElement(reply.body).jsonObject["content"] as? kotlinx.serialization.json.JsonArray)
            ?.let { (it.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive }?.content
            .orEmpty()
    }

    private companion object {
        /** RFC 3986 unreserved characters survive; everything else is UTF-8 percent-encoded. */
        fun percentEncode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val c = byte.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                    append(c)
                } else {
                    val v = byte.toInt() and 0xFF
                    append('%')
                    append("0123456789ABCDEF"[v shr 4])
                    append("0123456789ABCDEF"[v and 0x0F])
                }
            }
        }
    }
}
