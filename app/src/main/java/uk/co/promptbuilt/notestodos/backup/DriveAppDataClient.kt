package uk.co.promptbuilt.notestodos.backup

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Drive rejected the access token; the caller clears it and re-authorises once. */
class DriveAuthExpiredException : IOException("Google Drive access has expired")

/**
 * The four Drive v3 REST calls recipe backup needs, all confined to the hidden application
 * data folder (scope drive.appdata). Plain REST rather than the Drive Java client: the client
 * library adds megabytes for what is a list, an upload, a download and a delete.
 */
class DriveAppDataClient(private val accessToken: String) {

    suspend fun list(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<RemoteFile>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size)")
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = execute(Request.Builder().url(url).get()).use { JSONObject(it.body!!.string()) }
            val page = json.optJSONArray("files")
            for (i in 0 until (page?.length() ?: 0)) {
                val f = page!!.getJSONObject(i)
                files += RemoteFile(f.getString("id"), f.getString("name"), f.optString("size", "0").toLong())
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        files
    }

    /** Resumable upload, which Drive requires above 5 MB and which works at every size. */
    suspend fun upload(name: String, file: File) = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .toString()
        val session = execute(
            Request.Builder()
                .url("$UPLOAD/files?uploadType=resumable")
                .header("X-Upload-Content-Type", PDF)
                .header("X-Upload-Content-Length", file.length().toString())
                .post(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType())),
        ).use { it.header("Location") ?: throw IOException("Drive returned no upload location") }
        execute(Request.Builder().url(session).put(file.asRequestBody(PDF.toMediaType()))).close()
    }

    /** Downloads to a temporary file first, so an interrupted transfer never leaves a torn PDF. */
    suspend fun download(id: String, target: File) = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            execute(Request.Builder().url("$API/files/$id?alt=media").get()).use { response ->
                partial.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
            }
            if (!partial.renameTo(target)) throw IOException("Could not move ${target.name} into place")
        } finally {
            partial.delete()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$API/files/$id").delete(), allowNotFound = true).close()
    }

    private fun execute(builder: Request.Builder, allowNotFound: Boolean = false): Response {
        val response = http.newCall(builder.header("Authorization", "Bearer $accessToken").build()).execute()
        if (response.isSuccessful || (allowNotFound && response.code == 404)) return response
        val detail = response.body?.string()?.take(300).orEmpty()
        response.close()
        if (response.code == 401) throw DriveAuthExpiredException()
        throw IOException("Google Drive answered ${response.code}: $detail")
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val PDF = "application/pdf"

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
