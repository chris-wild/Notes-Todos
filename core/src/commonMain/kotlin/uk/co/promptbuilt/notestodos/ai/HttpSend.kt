package uk.co.promptbuilt.notestodos.ai

/**
 * Minimal platform HTTP seam (RiderNav pattern) — keeps HTTP client libraries
 * out of the shared framework. Returns EVERY status (callers parse non-2xx
 * bodies); throws only when no answer was obtained (network failure, timeout).
 */
internal class HttpReply(val status: Int, val body: String)

internal expect suspend fun httpSend(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?,
    timeoutSeconds: Int,
): HttpReply
