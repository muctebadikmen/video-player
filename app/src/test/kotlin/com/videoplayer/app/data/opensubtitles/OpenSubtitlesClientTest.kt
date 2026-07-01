// SPDX-License-Identifier: GPL-3.0-or-later
package com.videoplayer.app.data.opensubtitles

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenSubtitlesClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenSubtitlesClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OpenSubtitlesClient(
            versionName = "1.3.0",
            httpClient = OkHttpClient(),
            baseUrl = server.url("/api/v1").toString().trimEnd('/'),
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `login parses token base_url quota and sends mandatory headers`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"token":"tok123","base_url":"vip.opensubtitles.com","user":{"allowed_downloads":100,"level":"VIP member","vip":true}}""",
            ),
        )
        val result = client.login("KEY", "user", "pass")
        assertThat(result).isInstanceOf(OsResult.Success::class.java)
        val info = (result as OsResult.Success).value
        assertThat(info.token).isEqualTo("tok123")
        assertThat(info.allowedDownloads).isEqualTo(100)
        assertThat(info.baseUrl).isEqualTo("https://vip.opensubtitles.com/api/v1")

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.getHeader("Api-Key")).isEqualTo("KEY")
        assertThat(req.getHeader("User-Agent")).isEqualTo("VideoPlayer v1.3.0")
        assertThat(req.getHeader("Accept")).isEqualTo("application/json")
        assertThat(req.getHeader("Content-Type")).startsWith("application/json")
        assertThat(req.body.readUtf8()).contains("\"username\":\"user\"")
    }

    @Test fun `search maps results and sends Bearer token`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"total_count":1,"data":[{"attributes":{"language":"en","download_count":42,"ratings":7.5,
                   "from_trusted":true,"ai_translated":false,"machine_translated":false,"moviehash_match":true,
                   "release":"BluRay","files":[{"file_id":555,"file_name":"movie.en.srt"}]}}]}""",
            ),
        )
        val result = client.search("KEY", "tok123", moviehash = "abcdef0123456789", query = "movie", languages = listOf("en", "tr"))
        assertThat(result).isInstanceOf(OsResult.Success::class.java)
        val list = (result as OsResult.Success).value
        assertThat(list).hasSize(1)
        assertThat(list[0].fileId).isEqualTo(555L)
        assertThat(list[0].hashMatch).isTrue()

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.path).contains("moviehash=abcdef0123456789")
        assertThat(req.path).contains("languages=en,tr")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok123")
    }

    // --- Regression tests for the "login works but search fails" bug (v1.9.x) ---
    // The API answers 301 redirects when the request is not normalized (lowercased values, params in
    // alphabetical KEY order, languages CSV sorted). The app used to send tr,en + a raw mixed-case
    // filename + non-alphabetical param order, which surfaced to the user as a generic "Server error".

    @Test fun `search normalizes params - lowercased, alphabetical key order, sorted languages`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"total_count":0,"data":[]}"""))
        client.search(
            "KEY", "tok",
            moviehash = "ABCDEF0123456789",           // upper-case: must be lowercased on the wire
            query = "The.Movie.2021.x264-GRP",         // upper-case: must be lowercased on the wire
            languages = listOf("tr", "en"),            // the real default order: must be sorted to en,tr
        )
        val path = server.takeRequest().path!!
        assertThat(path).isEqualTo(
            "/api/v1/subtitles?languages=en,tr&moviehash=abcdef0123456789&query=the.movie.2021.x264-grp",
        )
    }

    @Test fun `search drops a malformed moviehash instead of sending a 400-bound value`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"total_count":0,"data":[]}"""))
        client.search("KEY", "tok", moviehash = "xyz", query = "movie", languages = listOf("en"))
        assertThat(server.takeRequest().path!!).doesNotContain("moviehash")
    }

    @Test fun `4xx surfaces the X-Reason header as the error message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setHeader("X-Reason", "query >= 3 characters").setBody("{}"),
        )
        val result = client.search("KEY", "tok", moviehash = null, query = "ab", languages = listOf("en"))
        assertThat(result).isEqualTo(OsResult.Failure(OsError.Http(400, "query >= 3 characters")))
    }

    @Test fun `search 406 is surfaced as Http not QuotaExhausted (quota-406 is download-only)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(406).setBody("{}"))
        val result = client.search("KEY", "tok", moviehash = null, query = "movie", languages = listOf("en"))
        assertThat(result).isInstanceOf(OsResult.Failure::class.java)
        assertThat((result as OsResult.Failure).error).isInstanceOf(OsError.Http::class.java)
    }

    @Test fun `download returns link bytes and remaining quota`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"link":"${server.url("/dl/file.srt")}","remaining":9,"reset_time_utc":"2026-06-20T00:00:00Z"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("1\n00:00:01,000 --> 00:00:02,000\nhello\n"))
        val result = client.download("KEY", "tok", fileId = 555)
        assertThat(result).isInstanceOf(OsResult.Success::class.java)
        val info = (result as OsResult.Success).value
        assertThat(String(info.bytes)).contains("hello")
        assertThat(info.remaining).isEqualTo(9)
    }

    @Test fun `406 maps to QuotaExhausted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(406).setBody("""{"message":"quota"}"""))
        val result = client.download("KEY", "tok", fileId = 1)
        assertThat(result).isEqualTo(OsResult.Failure(OsError.QuotaExhausted))
    }

    @Test fun `401 surfaces as Http so the caller can refresh`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"invalid token"}"""))
        val result = client.search("KEY", "stale", moviehash = null, query = "x", languages = listOf("en"))
        assertThat(result).isEqualTo(OsResult.Failure(OsError.Http(401, "invalid token")))
    }

    @Test fun `429 is retried with backoff then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"total_count":0,"data":[]}"""))
        val result = client.search("KEY", "tok", moviehash = null, query = "x", languages = listOf("en"))
        assertThat(result).isInstanceOf(OsResult.Success::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `403 bad user agent surfaces as Http 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"forbidden"}"""))
        val result = client.search("KEY", "tok", moviehash = null, query = "x", languages = listOf("en"))
        assertThat(result).isEqualTo(OsResult.Failure(OsError.Http(403, "forbidden")))
    }

    @Test fun `a failed file download does not retry the quota-spending POST`() = runTest {
        // POST /download succeeds (quota spent), then the CDN file GET 500s.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"link":"${server.url("/dl/file.srt")}","remaining":9}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500)) // CDN file fetch fails
        val result = client.download("KEY", "tok", fileId = 555)
        assertThat(result).isInstanceOf(OsResult.Failure::class.java)
        // Exactly 2 requests total (one POST + one GET) — NOT 4 (no re-POST of /download).
        assertThat(server.requestCount).isEqualTo(2)
    }
}
