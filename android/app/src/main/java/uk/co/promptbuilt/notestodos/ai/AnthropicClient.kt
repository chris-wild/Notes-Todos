package uk.co.promptbuilt.notestodos.ai

import android.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AiUnauthorizedException(message: String) : Exception(message)

/**
 * Direct port of the server's Anthropic Messages calls (backend/server.js:720-871):
 * same model, prompts, prefill trick, and error mapping. This is the app's ONLY
 * outbound network dependency.
 */
class AnthropicClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * Live-checks a key against GET /v1/models (port of server.js:483).
     * Returns null on success, or a human-readable reason on failure.
     */
    suspend fun validateKey(apiKey: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.isSuccessful) return@withContext null
            val apiMessage = try {
                val body = response.body?.string().orEmpty()
                ((Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)
                    ?.get("message") as? JsonPrimitive)?.content
            } catch (_: Exception) {
                null
            }
            "HTTP ${response.code}${apiMessage?.let { ": $it" } ?: ""}"
        }
    }

    suspend fun extractIngredientsFromText(text: String, recipeName: String, apiKey: String): List<String> {
        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 2048)
            put(
                "system",
                "You extract recipe ingredient lists. Return ONLY JSON. " +
                    "Return {\"ingredients\": [\"...\"]}. " +
                    "Keep quantities/units. Do not include method steps.",
            )
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Recipe name: $recipeName.\n\nText to extract from:\n$text")
                        },
                    )
                    // Prefill forces Claude to emit raw JSON with no prose or code fences.
                    add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", "{")
                        },
                    )
                },
            )
        }
        return IngredientParsing.parseResponse("{" + complete(body, apiKey))
    }

    suspend fun extractIngredientsFromPdf(pdfBytes: ByteArray, recipeName: String, apiKey: String): List<String> {
        val base64 = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)
        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 2048)
            put(
                "system",
                "You extract recipe ingredient lists. Return ONLY JSON. " +
                    "If there is a single recipe, return {\"ingredients\": [\"...\"]}. " +
                    "If there are multiple recipes, return {\"recipes\": [{\"name\": \"...\", \"ingredients\": [\"...\"]}]}. " +
                    "Keep quantities/units. Do not include method steps.",
            )
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "document")
                                            put(
                                                "source",
                                                buildJsonObject {
                                                    put("type", "base64")
                                                    put("media_type", "application/pdf")
                                                    put("data", base64)
                                                },
                                            )
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "Extract the ingredient list(s) for: $recipeName. Return JSON only.")
                                        },
                                    )
                                },
                            )
                        },
                    )
                    // Prefill forces Claude to emit raw JSON with no prose or code fences.
                    add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", "{")
                        },
                    )
                },
            )
        }
        return IngredientParsing.parseResponse("{" + complete(body, apiKey))
    }

    /** POSTs a Messages request and returns content[0].text. */
    private suspend fun complete(body: JsonObject, apiKey: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try {
                    (Json.parseToJsonElement(responseText).jsonObject["error"] as? JsonObject)
                        ?.get("message")?.let { (it as? JsonPrimitive)?.content }
                } catch (_: Exception) {
                    null
                } ?: responseText
                if (response.code == 401 || response.code == 403) {
                    throw AiUnauthorizedException("Anthropic API key is invalid. Update it in Settings.")
                }
                throw IllegalStateException("Claude extract failed: ${response.code} $message")
            }
            val content = Json.parseToJsonElement(responseText).jsonObject["content"] as? JsonArray
            (content?.firstOrNull() as? JsonObject)?.get("text")
                ?.let { (it as? JsonPrimitive)?.content }
                .orEmpty()
        }
    }

    private companion object {
        const val MODEL = "claude-haiku-4-5-20251001"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
