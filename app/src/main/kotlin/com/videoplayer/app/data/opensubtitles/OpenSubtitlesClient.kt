// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.opensubtitles

import com.videoplayer.core.playback.SubtitleSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.random.Random

sealed interface OsResult<out T> {
    data class Success<T>(val value: T) : OsResult<T>
    data class Failure(val error: OsError) : OsResult<Nothing>
}

sealed interface OsError {
    data object NotLoggedIn : OsError
    data object QuotaExhausted : OsError
    data object Offline : OsError
    data class Http(val code: Int, val message: String) : OsError
    data class Unexpected(val message: String) : OsError
}

data class LoginInfo(
    val token: String,
    val baseUrl: String,
    val allowedDownloads: Int,
    val level: String?,
    val vip: Boolean,
)

data class DownloadInfo(val bytes: ByteArray, val remaining: Int)

/** OSDb movie-hash on the wire: exactly 16 lowercase hex chars. Anything else is a hard 400 at the API. */
private val MOVIEHASH_REGEX = Regex("^[a-f0-9]{16}$")

class OpenSubtitlesClient(
    private val versionName: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://api.opensubtitles.com/api/v1",
) {
    @Volatile private var baseUrl: String = baseUrl
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun Request.Builder.commonHeaders(apiKey: String, token: String?) = apply {
        header("Api-Key", apiKey)
        header("User-Agent", "VideoPlayer v$versionName")
        header("Accept", "application/json")
        if (token != null) header("Authorization", "Bearer $token")
    }

    suspend fun login(apiKey: String, username: String, password: String): OsResult<LoginInfo> =
        execute {
            val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
                .toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("$baseUrl/login")
                .commonHeaders(apiKey, token = null)
                .post(body)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                toResult(resp) {
                    val parsed = json.decodeFromString(LoginResponse.serializer(), it)
                    val newBase = parsed.base_url?.let { host -> "https://$host/api/v1" } ?: baseUrl
                    baseUrl = newBase
                    LoginInfo(
                        token = parsed.token,
                        baseUrl = newBase,
                        allowedDownloads = parsed.user?.allowed_downloads ?: 0,
                        level = parsed.user?.level,
                        vip = parsed.user?.vip ?: false,
                    )
                }
            }
        }

    suspend fun search(
        apiKey: String,
        token: String,
        moviehash: String?,
        query: String?,
        languages: List<String>,
    ): OsResult<List<SubtitleSearchResult>> = execute {
        // OpenSubtitles requires query params lowercased and added in alphabetical KEY order, with the
        // languages CSV itself sorted — otherwise the API answers 301 redirects (documented, and done
        // by the reference VLSub client). Emit them alphabetically: languages, moviehash, query.
        val url = "$baseUrl/subtitles".toHttpUrl().newBuilder().apply {
            val langCsv = languages.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
            // Encoded so the separators stay literal commas (en,tr), not %2C — the value is already safe.
            if (langCsv.isNotEmpty()) addEncodedQueryParameter("languages", langCsv)
            val normHash = moviehash?.trim()?.lowercase()
            // A malformed hash returns a hard 400 that aborts the whole search; drop it instead.
            if (!normHash.isNullOrEmpty() && normHash.matches(MOVIEHASH_REGEX)) addQueryParameter("moviehash", normHash)
            if (!query.isNullOrBlank()) addQueryParameter("query", query.lowercase())
        }.build()
        val req = Request.Builder().url(url).commonHeaders(apiKey, token).get().build()
        httpClient.newCall(req).execute().use { resp ->
            toResult(resp) { json.decodeFromString(SearchResponse.serializer(), it).toSearchResults() }
        }
    }

    suspend fun download(apiKey: String, token: String, fileId: Long): OsResult<DownloadInfo> = execute {
        val body = json.encodeToString(DownloadRequest.serializer(), DownloadRequest(fileId))
            .toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url("$baseUrl/download")
            .commonHeaders(apiKey, token)
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val meta = toResult(resp, quotaOn406 = true) { json.decodeFromString(DownloadResponse.serializer(), it) }
            if (meta is OsResult.Failure) return@execute meta
            val link = (meta as OsResult.Success).value
            val fileReq = Request.Builder()
                .url(link.link)
                .header("User-Agent", "VideoPlayer v$versionName")
                .get()
                .build()
            httpClient.newCall(fileReq).execute().use { dl ->
                // The /download POST already succeeded and SPENT the user's quota; a failure fetching the
                // CDN link must NOT be retriable (that would re-POST and double-spend). Map to a
                // non-retriable error so execute() returns instead of re-running the whole block.
                if (!dl.isSuccessful) {
                    return@execute OsResult.Failure(OsError.Unexpected("subtitle download failed (${dl.code})"))
                }
                OsResult.Success(DownloadInfo(dl.body?.bytes() ?: ByteArray(0), link.remaining))
            }
        }
    }

    /** Run [block] on IO with retry on 429/5xx (max 3 attempts, backoff + jitter, honor Retry-After). */
    private suspend fun <T> execute(block: suspend () -> OsResult<T>): OsResult<T> =
        withContext(Dispatchers.IO) {
            var attempt = 0
            var lastResult: OsResult<T> = OsResult.Failure(OsError.Unexpected("no attempt"))
            while (attempt <= 2) {
                val result = try {
                    block()
                } catch (e: IOException) {
                    return@withContext OsResult.Failure(OsError.Offline)
                }
                lastResult = result
                val retriable = result is OsResult.Failure &&
                    result.error is OsError.Http && (result.error.code == 429 || result.error.code in 500..599)
                if (!retriable) return@withContext result
                if (attempt >= 2) break
                val retryAfterMs = lastRetryAfterMs ?: (250L * (1 shl attempt))
                delay(retryAfterMs + Random.nextLong(0, 100))
                attempt++
            }
            lastResult
        }

    @Volatile private var lastRetryAfterMs: Long? = null

    private inline fun <T> toResult(resp: Response, quotaOn406: Boolean = false, parse: (String) -> T): OsResult<T> {
        lastRetryAfterMs = resp.header("Retry-After")?.toLongOrNull()?.let { it * 1000 }
        return when {
            resp.isSuccessful -> {
                val text = resp.body?.string().orEmpty()
                try { OsResult.Success(parse(text)) }
                catch (e: Exception) { OsResult.Failure(OsError.Unexpected(e.message ?: "parse error")) }
            }
            // 406 means "quota exhausted" ONLY on /download. On /subtitles it is an edge-case bad query,
            // so surface it as a normal Http error rather than a misleading "quota reached" message.
            resp.code == 406 && quotaOn406 -> OsResult.Failure(OsError.QuotaExhausted)
            else -> OsResult.Failure(OsError.Http(resp.code, extractMessage(resp)))
        }
    }

    /** The true cause of a 4xx lives in the X-Reason header (then X-Os-Rule), not the JSON body. */
    private fun extractMessage(resp: Response): String =
        resp.header("X-Reason")
            ?: resp.header("X-Os-Rule")
            ?: runCatching {
                val raw = resp.peekBody(2048).string()
                Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
            }.getOrNull()
            ?: resp.message
}
