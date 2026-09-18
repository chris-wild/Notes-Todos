package uk.co.promptbuilt.notestodos.ai

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Ephemeral session (no cookies, no disk cache): these requests carry the API key.
private val session: NSURLSession by lazy {
    NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration)
}

/** iOS transport for [httpSend]: URLSession, every status returned. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual suspend fun httpSend(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?,
    timeoutSeconds: Int,
): HttpReply = suspendCancellableCoroutine { continuation ->
    val request = NSMutableURLRequest(uRL = NSURL(string = url)!!).apply {
        setHTTPMethod(method)
        setTimeoutInterval(timeoutSeconds.toDouble())
        headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
        if (body != null) {
            @Suppress("CAST_NEVER_SUCCEEDS")
            setHTTPBody((body as NSString).dataUsingEncoding(NSUTF8StringEncoding))
        }
    }
    val task = session.dataTaskWithRequest(request) { data, response, error ->
        if (error != null) {
            continuation.resumeWithException(RuntimeException(error.localizedDescription))
            return@dataTaskWithRequest
        }
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        val text = data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }.orEmpty()
        continuation.resume(HttpReply(status, text))
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}
