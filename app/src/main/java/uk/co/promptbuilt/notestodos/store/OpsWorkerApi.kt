package uk.co.promptbuilt.notestodos.store

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uk.co.promptbuilt.notestodos.BuildConfig

/**
 * Commerce calls to the metering Worker (backend/ops). Extraction itself rides the shared
 * Kotlin MeteredOcrClient; balance and purchase submission live here, as they do in Swift on iOS.
 */
class OpsWorkerApi(private val baseUrl: String) {

    data class Balance(val balance: Int, val purchased: Boolean)

    class WorkerException(val status: Int, body: String) :
        IOException("Credits service answered $status: ${body.take(200)}")

    suspend fun balance(token: String): Balance = withContext(Dispatchers.IO) {
        val json = execute(Request.Builder().url("$baseUrl/v1/balance").header("Authorization", "Bearer $token").get())
        Balance(json.getInt("balance"), json.optBoolean("purchased", false))
    }

    /**
     * Hands a Play purchase to the Worker, which verifies it with Google and credits the pack
     * idempotently. [bearer] is the account the purchase was made for (its obfuscated account
     * id), so a purchase recovered after a reinstall still credits its own account.
     */
    suspend fun submitGooglePurchase(bearer: String, productId: String, purchaseToken: String): Int =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("packageName", BuildConfig.APPLICATION_ID)
                .put("productId", productId)
                .put("purchaseToken", purchaseToken)
                .toString()
            val json = execute(
                Request.Builder()
                    .url("$baseUrl/v1/purchase/google")
                    .header("Authorization", "Bearer $bearer")
                    .post(body.toRequestBody("application/json".toMediaType())),
            )
            json.getInt("balance")
        }

    private fun execute(builder: Request.Builder): JSONObject =
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WorkerException(response.code, text)
            JSONObject(text)
        }

    private companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
