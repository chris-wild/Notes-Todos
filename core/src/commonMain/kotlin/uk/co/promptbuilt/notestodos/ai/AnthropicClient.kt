package uk.co.promptbuilt.notestodos.ai

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class AiUnauthorizedException(message: String) : Exception(message)

/**
 * Direct port of the retired server's Anthropic Messages calls: same model,
 * prompts, prefill trick, and error mapping. This is the app's ONLY outbound
 * network dependency, riding the platform httpSend seam.
 */
class AnthropicClient {

    /** Returns null on success, or a human-readable reason on failure. */
    suspend fun validateKey(apiKey: String): String? {
        val reply = httpSend(
            method = "GET",
            url = "https://api.anthropic.com/v1/models",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            ),
            body = null,
            timeoutSeconds = 30,
        )
        if (reply.status in 200..299) return null
        val apiMessage = try {
            ((Json.parseToJsonElement(reply.body).jsonObject["error"] as? JsonObject)
                ?.get("message") as? JsonPrimitive)?.content
        } catch (_: Exception) {
            null
        }
        return "HTTP ${reply.status}${apiMessage?.let { ": $it" } ?: ""}"
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

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun extractIngredientsFromPdf(pdfBytes: ByteArray, recipeName: String, apiKey: String): List<String> {
        val base64 = Base64.encode(pdfBytes)
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
    private suspend fun complete(body: JsonObject, apiKey: String): String {
        val reply = httpSend(
            method = "POST",
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
                "Content-Type" to "application/json",
            ),
            body = body.toString(),
            timeoutSeconds = 120,
        )
        if (reply.status !in 200..299) {
            val message = try {
                ((Json.parseToJsonElement(reply.body).jsonObject["error"] as? JsonObject)
                    ?.get("message") as? JsonPrimitive)?.content
            } catch (_: Exception) {
                null
            } ?: reply.body
            if (reply.status == 401 || reply.status == 403) {
                throw AiUnauthorizedException("Anthropic API key is invalid. Update it in Settings.")
            }
            throw IllegalStateException("Claude extract failed: ${reply.status} $message")
        }
        val content = Json.parseToJsonElement(reply.body).jsonObject["content"] as? JsonArray
        return (content?.firstOrNull() as? JsonObject)?.get("text")
            ?.let { (it as? JsonPrimitive)?.content }
            .orEmpty()
    }

    private companion object {
        const val MODEL = "claude-haiku-4-5-20251001"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
