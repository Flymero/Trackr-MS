package com.trackr.data.remote.update

import com.trackr.core.model.UpdateCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class GithubUpdateCheckerTest {

    @Test
    fun checkForUpdates_whenNewVersionAvailable_returnsHasUpdate() = runTest {
        val jsonPayload = """
            {
                "versionCode": 3,
                "versionName": "0.0.3",
                "minSupportedVersionCode": 1,
                "changelog": "新特性 1\n新特性 2",
                "downloadUrl": "https://github.com/5151561/Trackr/releases/tag/v0.0.3",
                "publishTime": "2026-10-30",
                "forceUpdate": false
            }
        """.trimIndent()

        val client = clientReturning(jsonPayload)

        val checker = GithubUpdateChecker(httpClient = client, endpointUrls = listOf("https://example.com/update.json"))
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.HasUpdate)
        val hasUpdate = result as UpdateCheckResult.HasUpdate
        assertEquals(3, hasUpdate.updateInfo.versionCode)
        assertEquals("0.0.3", hasUpdate.updateInfo.versionName)
        assertEquals("新特性 1\n新特性 2", hasUpdate.updateInfo.changelog)
        assertEquals("https://github.com/5151561/Trackr/releases/tag/v0.0.3", hasUpdate.updateInfo.downloadUrl)
        assertFalse(hasUpdate.isForceUpdate)
    }

    @Test
    fun checkForUpdates_whenAlreadyLatestVersion_returnsUpToDate() = runTest {
        val jsonPayload = """
            {
                "versionCode": 2,
                "versionName": "0.0.2",
                "minSupportedVersionCode": 1,
                "changelog": "更新说明",
                "downloadUrl": "https://github.com/5151561/Trackr/releases",
                "publishTime": "2026-10-30",
                "forceUpdate": false
            }
        """.trimIndent()

        val client = clientReturning(jsonPayload)

        val checker = GithubUpdateChecker(httpClient = client, endpointUrls = listOf("https://example.com/update.json"))
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("0.0.2", (result as UpdateCheckResult.UpToDate).currentVersionName)
    }

    @Test
    fun checkForUpdates_whenVersionLowerThanMinSupported_marksForceUpdate() = runTest {
        val jsonPayload = """
            {
                "versionCode": 10,
                "versionName": "1.0.0",
                "minSupportedVersionCode": 5,
                "changelog": "大版本升级",
                "downloadUrl": "https://github.com/5151561/Trackr/releases",
                "forceUpdate": false
            }
        """.trimIndent()

        val client = clientReturning(jsonPayload)

        val checker = GithubUpdateChecker(httpClient = client, endpointUrls = listOf("https://example.com/update.json"))
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.HasUpdate)
        assertTrue((result as UpdateCheckResult.HasUpdate).isForceUpdate)
    }

    @Test
    fun checkForUpdates_whenFirstEndpointFails_fallsBackToNextEndpoint() = runTest {
        val jsonPayload = """
            {
                "versionCode": 3,
                "versionName": "0.0.3",
                "changelog": "Fallback successful",
                "downloadUrl": "https://github.com/5151561/Trackr/releases"
            }
        """.trimIndent()

        val client = HttpClient(MockEngine { request ->
            if (request.url.toString().contains("primary.com")) {
                throw IOException("Network timeout on primary")
            } else {
                respondJson(jsonPayload)
            }
        })

        val checker = GithubUpdateChecker(
            httpClient = client,
            endpointUrls = listOf("https://primary.com/update.json", "https://cdn.example.com/update.json")
        )
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.HasUpdate)
        assertEquals("0.0.3", (result as UpdateCheckResult.HasUpdate).updateInfo.versionName)
    }

    @Test
    fun checkForUpdates_whenAllEndpointsFail_returnsError() = runTest {
        val client = HttpClient(MockEngine { _ -> throw IOException("Unable to resolve host") })

        val checker = GithubUpdateChecker(
            httpClient = client,
            endpointUrls = listOf("https://bad1.com/update.json", "https://bad2.com/update.json")
        )
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.Error)
        assertEquals("Unable to resolve host", (result as UpdateCheckResult.Error).message)
    }

    @Test
    fun checkForUpdates_whenFirstSourceHasIntermediateVersion_returnsNewestVersion() = runTest {
        // #36：0.1.6 应直接找到 0.1.8，不能在缓存的 0.1.7 处提前结束。
        val result = checkSources(releasePayload(11), releasePayload(12), releasePayload(10))
            as UpdateCheckResult.HasUpdate

        assertEquals(12, result.updateInfo.versionCode)
        assertEquals("0.1.8", result.updateInfo.versionName)
        assertEquals("Release 12", result.updateInfo.changelog)
        assertEquals("https://github.com/5151561/Trackr/releases/download/v0.1.8/arm64.apk", result.updateInfo.downloadUrl)
        assertTrue(result.isForceUpdate)
    }

    @Test
    fun checkForUpdates_whenFirstSourceSaysUpToDate_stillChecksForNewerVersion() = runTest {
        val result = checkSources(releasePayload(10), releasePayload(12))
            as UpdateCheckResult.HasUpdate

        assertEquals(12, result.updateInfo.versionCode)
    }

    @Test
    fun checkForUpdates_whenLaterSourcesAreOlderOrFail_keepsNewestValidResponse() = runTest {
        val result = checkSources(releasePayload(12), releasePayload(11), null)
            as UpdateCheckResult.HasUpdate

        assertEquals(12, result.updateInfo.versionCode)
    }

    @Test
    fun checkForUpdates_whenNewestResponseIsInvalid_usesValidRelease() = runTest {
        val invalidRelease = releasePayload(12).replace("https://github.com/", "https://evil.example.com/")
        val result = checkSources(releasePayload(11), invalidRelease, "invalid json")
            as UpdateCheckResult.HasUpdate

        assertEquals(11, result.updateInfo.versionCode)
        assertFalse(result.isForceUpdate)
    }

    @Test
    fun checkForUpdates_whenValidSourcesAreNotNewerAndAnotherFails_returnsUpToDate() = runTest {
        assertEquals(
            UpdateCheckResult.UpToDate("0.1.6"),
            checkSources(releasePayload(9), null, releasePayload(10))
        )
    }

    private suspend fun checkSources(vararg payloads: String?): UpdateCheckResult {
        val client = HttpClient(MockEngine { request ->
            val index = request.url.encodedPath.removePrefix("/").toInt()
            val payload = payloads[index] ?: throw IOException("Source unavailable")
            respondJson(payload)
        })
        return try {
            GithubUpdateChecker(
                httpClient = client,
                endpointUrls = payloads.indices.map { "https://example.com/$it" },
                supportedAbis = listOf("arm64-v8a")
            ).checkForUpdates(currentVersionCode = 10, currentVersionName = "0.1.6")
        } finally {
            client.close()
        }
    }

    private fun releasePayload(versionCode: Int) = """
        {
            "versionCode": $versionCode,
            "versionName": "0.1.${versionCode - 4}",
            "changelog": "Release $versionCode",
            "minSupportedVersionCode": ${if (versionCode == 12) 11 else 1},
            "downloadUrl": "https://github.com/5151561/Trackr/releases/tag/v0.1.${versionCode - 4}",
            "downloadUrls": {
                "arm64-v8a": "https://github.com/5151561/Trackr/releases/download/v0.1.${versionCode - 4}/arm64.apk"
            }
        }
    """.trimIndent()

    private fun MockRequestHandleScope.respondJson(jsonPayload: String) =
        respond(jsonPayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun clientReturning(jsonPayload: String): HttpClient =
        HttpClient(MockEngine { _ -> respondJson(jsonPayload) })

    private fun payloadWithDownloadUrl(downloadUrl: String) = """
        {
            "versionCode": 3,
            "versionName": "0.0.3",
            "changelog": "更新说明",
            "downloadUrl": "$downloadUrl"
        }
    """.trimIndent()

    @Test
    fun checkForUpdates_whenDownloadUrlIsHttp_returnsError() = runTest {
        val checker = GithubUpdateChecker(
            httpClient = clientReturning(payloadWithDownloadUrl("http://github.com/5151561/Trackr/releases")),
            endpointUrls = listOf("https://example.com/update.json")
        )
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun checkForUpdates_whenDownloadHostNotWhitelisted_returnsError() = runTest {
        val checker = GithubUpdateChecker(
            httpClient = clientReturning(payloadWithDownloadUrl("https://evil.example.com/trackr.apk")),
            endpointUrls = listOf("https://example.com/update.json")
        )
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun checkForUpdates_whenCancelled_cancelsAllSourceRequests() = runBlocking {
        val requested = java.util.concurrent.atomic.AtomicInteger(0)
        val cancelled = java.util.concurrent.atomic.AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { _ ->
            if (requested.incrementAndGet() == 2) entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.incrementAndGet()
            }
        })
        try {
            val checker = GithubUpdateChecker(
                httpClient = client,
                endpointUrls = listOf("https://a.example.com/u.json", "https://b.example.com/u.json")
            )

            val job = launch(Dispatchers.Default) { checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2") }
            withTimeout(2_000L) { entered.await() }
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(2, requested.get())
            assertEquals(2, cancelled.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun checkForUpdates_whenDeviceIsArm64_picksArm64Apk() = runTest {
        val checker = GithubUpdateChecker(
            httpClient = clientReturning(payloadWithAbiUrls()),
            endpointUrls = listOf("https://example.com/update.json"),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")
            as UpdateCheckResult.HasUpdate

        assertEquals(
            "https://github.com/5151561/Trackr/releases/download/v0.0.3/trackr-0.0.3-arm64-v8a.apk",
            result.updateInfo.downloadUrl
        )
    }

    @Test
    fun checkForUpdates_whenDeviceIs32Bit_picksArmeabiV7Apk() = runTest {
        val checker = GithubUpdateChecker(
            httpClient = clientReturning(payloadWithAbiUrls()),
            endpointUrls = listOf("https://example.com/update.json"),
            supportedAbis = listOf("armeabi-v7a")
        )

        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")
            as UpdateCheckResult.HasUpdate

        assertEquals(
            "https://github.com/5151561/Trackr/releases/download/v0.0.3/trackr-0.0.3-armeabi-v7a.apk",
            result.updateInfo.downloadUrl
        )
    }

    @Test
    fun checkForUpdates_whenNoAbiEntryMatches_fallsBackToCommonUrl() = runTest {
        val checker = GithubUpdateChecker(
            httpClient = clientReturning(payloadWithAbiUrls()),
            endpointUrls = listOf("https://example.com/update.json"),
            supportedAbis = listOf("x86_64")
        )

        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")
            as UpdateCheckResult.HasUpdate

        assertEquals(
            "https://github.com/5151561/Trackr/releases/tag/v0.0.3",
            result.updateInfo.downloadUrl
        )
    }

    @Test
    fun checkForUpdates_whenAbiUrlHostInvalid_fallsBackToCommonUrl() = runTest {
        val payload = """
            {
                "versionCode": 3,
                "versionName": "0.0.3",
                "changelog": "更新说明",
                "downloadUrl": "https://github.com/5151561/Trackr/releases/tag/v0.0.3",
                "downloadUrls": {
                    "arm64-v8a": "https://evil.example.com/trackr.apk"
                }
            }
        """.trimIndent()
        val checker = GithubUpdateChecker(
            httpClient = clientReturning(payload),
            endpointUrls = listOf("https://example.com/update.json"),
            supportedAbis = listOf("arm64-v8a")
        )

        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")
            as UpdateCheckResult.HasUpdate

        assertEquals(
            "https://github.com/5151561/Trackr/releases/tag/v0.0.3",
            result.updateInfo.downloadUrl
        )
    }

    private fun payloadWithAbiUrls() = """
        {
            "versionCode": 3,
            "versionName": "0.0.3",
            "changelog": "更新说明",
            "downloadUrl": "https://github.com/5151561/Trackr/releases/tag/v0.0.3",
            "downloadUrls": {
                "arm64-v8a": "https://github.com/5151561/Trackr/releases/download/v0.0.3/trackr-0.0.3-arm64-v8a.apk",
                "armeabi-v7a": "https://github.com/5151561/Trackr/releases/download/v0.0.3/trackr-0.0.3-armeabi-v7a.apk"
            }
        }
    """.trimIndent()

    @Test
    fun isAllowedDownloadUrl_validatesSchemeAndHost() {
        assertTrue(GithubUpdateChecker.isAllowedDownloadUrl("https://github.com/5151561/Trackr/releases"))
        assertTrue(GithubUpdateChecker.isAllowedDownloadUrl("https://objects.githubusercontent.com/a/b.apk"))
        assertTrue(GithubUpdateChecker.isAllowedDownloadUrl("https://raw.githubusercontent.com/a/b.apk"))
        assertTrue(GithubUpdateChecker.isAllowedDownloadUrl("https://cdn.jsdelivr.net/gh/a/b.apk"))
        assertTrue(GithubUpdateChecker.isAllowedDownloadUrl("https://fastly.jsdelivr.net/gh/a/b.apk"))
        assertTrue(GithubUpdateChecker.isAllowedDownloadUrl("https://GitHub.com/5151561/Trackr"))

        assertFalse(GithubUpdateChecker.isAllowedDownloadUrl("http://github.com/5151561/Trackr"))
        assertFalse(GithubUpdateChecker.isAllowedDownloadUrl("https://github.com.evil.com/a.apk"))
        assertFalse(GithubUpdateChecker.isAllowedDownloadUrl("https://evilgithub.com/a.apk"))
        assertFalse(GithubUpdateChecker.isAllowedDownloadUrl("https://user@evil.com/github.com"))
        assertFalse(GithubUpdateChecker.isAllowedDownloadUrl("ftp://github.com/a.apk"))
        assertFalse(GithubUpdateChecker.isAllowedDownloadUrl("javascript:alert(1)"))
        assertFalse(GithubUpdateChecker.isAllowedDownloadUrl(""))
    }
}
