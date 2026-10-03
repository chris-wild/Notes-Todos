package uk.co.promptbuilt.notestodos.store

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import uk.co.promptbuilt.notestodos.BuildConfig

/**
 * The device check behind the free starter credits (backend/ops/src/starter.js): a Play Integrity
 * token whose nonce is bound to this install's account, so the Worker can read and set the
 * device's "had its free credits" bit (device recall) without the token being reusable for any
 * other account. Debug builds talk to the staging Worker, which accepts a test check instead,
 * because only a copy installed from Google Play passes Play Integrity.
 */
class StarterCheck(context: Context) {
    private val appContext = context.applicationContext

    suspend fun body(token: String): JSONObject {
        if (BuildConfig.DEBUG) return JSONObject().put("platform", "test")
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce(token))
            .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
            .build()
        val response = suspendCancellableCoroutine { continuation ->
            IntegrityManagerFactory.create(appContext).requestIntegrityToken(request)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        return JSONObject().put("platform", "android").put("integrityToken", response.token())
    }

    companion object {
        /** The Google Cloud project linked to HobPad's Play Integrity settings in Play Console. */
        const val CLOUD_PROJECT_NUMBER = 878126091928L

        /** Must equal the Worker's starterNonce(): SHA-256 of "hobpad-starter:" + token, URL-safe base64. */
        fun nonce(token: String): String =
            Base64.getUrlEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("hobpad-starter:${token.lowercase()}".toByteArray()),
            )
    }
}
