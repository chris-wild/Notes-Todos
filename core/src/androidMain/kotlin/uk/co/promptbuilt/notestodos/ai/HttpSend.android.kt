package uk.co.promptbuilt.notestodos.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val baseClient = OkHttpClient()

internal actual suspend fun httpSend(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?,
    timeoutSeconds: Int,
): HttpReply = withContext(Dispatchers.IO) {
    val client = baseClient.newBuilder()
        .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
    val builder = Request.Builder().url(url)
    headers.forEach { (name, value) -> builder.header(name, value) }
    builder.method(method, body?.toRequestBody("application/json".toMediaType()))
    client.newCall(builder.build()).execute().use { response ->
        HttpReply(status = response.code, body = response.body?.string().orEmpty())
    }
}
