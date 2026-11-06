package com.trackr.core.network.remote.common

import com.trackr.core.model.TransitProtocolFailure
import com.trackr.core.model.TransitServiceFailure
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.testing.MockReply
import com.trackr.core.network.testing.mockHttpClient
import com.trackr.core.network.testing.unusedHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BaseTransitApiClientTest {

    private class TestTransitApiClient(httpClient: HttpClient) : BaseTransitApiClient(httpClient) {
        suspend fun testExecuteHttpCall(block: HttpRequestBuilder.() -> Unit): Int = executeHttpCall(block).code
        suspend fun testExecuteJson(block: HttpRequestBuilder.() -> Unit): JSONObject = executeJson(block)
        fun testIsSubwayStationName(name: String) = isSubwayStationName(name)
        fun testCleanSubwaySuffix(name: String) = cleanSubwaySuffix(name)
        fun testResolveDistance(
            serverDistance: Int?,
            userLat: Double?,
            userLng: Double?,
            stationLat: Double,
            stationLng: Double
        ) = resolveDistance(serverDistance, userLat, userLng, stationLat, stationLng)
        fun testSanitizeBusPlate(raw: String?) = sanitizeBusPlate(raw)
    }

    @Test
    fun testSubwayStationNameDetectionAndCleaning() {
        val client = TestTransitApiClient(unusedHttpClient())

        // Subway indicators
        assertTrue(client.testIsSubwayStationName("湖滨中路（地铁站）"))
        assertTrue(client.testIsSubwayStationName("湖滨中路(地铁站)"))
        assertTrue(client.testIsSubwayStationName("湖滨中路站（M）"))
        assertTrue(client.testIsSubwayStationName("湖滨中路站(M)"))

        // Ground stations (should not be detected as pure subway)
        assertFalse(client.testIsSubwayStationName("湖滨中路"))
        assertFalse(client.testIsSubwayStationName("高新园地铁站"))
        assertFalse(client.testIsSubwayStationName("人民广场地铁站公交站"))

        // Cleaning suffix
        assertEquals("湖滨中路", client.testCleanSubwaySuffix("湖滨中路（地铁站）"))
        assertEquals("湖滨中路", client.testCleanSubwaySuffix("湖滨中路(地铁站)"))
        assertEquals("湖滨中路站", client.testCleanSubwaySuffix("湖滨中路站（M）"))
        assertEquals("湖滨中路站", client.testCleanSubwaySuffix("湖滨中路站(M)"))
    }

    @Test
    fun testResolveDistance_prefersServerDistance() {
        val client = TestTransitApiClient(unusedHttpClient())

        // Server distance > 0 is preferred
        val dist = client.testResolveDistance(
            serverDistance = 350,
            userLat = 31.23,
            userLng = 121.47,
            stationLat = 31.24,
            stationLng = 121.48
        )
        assertEquals(350, dist)
    }

    @Test
    fun testResolveDistance_fallsBackToCoordinatesMath() {
        val client = TestTransitApiClient(unusedHttpClient())

        // Server distance is null, calculates from user & station coords
        val dist = client.testResolveDistance(
            serverDistance = null,
            userLat = 31.2300,
            userLng = 121.4700,
            stationLat = 31.2350,
            stationLng = 121.4700
        )
        assertNotNull(dist)
        assertTrue(dist in 500..600)

        // Server distance <= 0, calculates from coords
        val distZero = client.testResolveDistance(
            serverDistance = 0,
            userLat = 31.2300,
            userLng = 121.4700,
            stationLat = 31.2350,
            stationLng = 121.4700
        )
        assertNotNull(distZero)
        assertTrue(distZero in 500..600)

        // No user coords available -> returns null
        val distNoCoords = client.testResolveDistance(
            serverDistance = null,
            userLat = null,
            userLng = null,
            stationLat = 31.2350,
            stationLng = 121.4700
        )
        assertNull(distNoCoords)
    }

    @Test
    fun testSanitizeBusPlate() {
        val client = TestTransitApiClient(unusedHttpClient())

        assertEquals("沪A·D8866", client.testSanitizeBusPlate("沪A·D8866"))
        assertEquals("417", client.testSanitizeBusPlate("417"))
        assertEquals("K102", client.testSanitizeBusPlate("K102"))
        // Internal hashes should be sanitized to empty
        assertEquals("", client.testSanitizeBusPlate("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
        assertEquals("", client.testSanitizeBusPlate(null))
    }

    @Test
    fun testExecuteHttpCallAndExecuteJson() = runBlocking {
        val http = mockHttpClient { request ->
            when (request.encodedPath) {
                "/success" -> MockReply("""{"status": "1", "data": "hello"}""")
                "/error" -> MockReply("""{"error": "fail"}""", code = 500)
                else -> MockReply("", code = 404)
            }
        }
        val client = TestTransitApiClient(http)

        val json = client.testExecuteJson { url("https://example.com/success") }
        assertEquals("1", json.getString("status"))
        assertEquals("hello", json.getString("data"))

        try {
            client.testExecuteJson { url("https://example.com/error") }
            fail("Expected IOException for HTTP 500")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("500") == true)
        }
    }

    @Test
    fun testExecuteJson_errorMessageDoesNotLeakQueryParameters() = runBlocking {
        val client = TestTransitApiClient(mockHttpClient { MockReply("", code = 403) })

        try {
            client.testExecuteJson { url("https://example.com/v3/direction?key=SECRET_KEY&origin=1,2") }
            fail("Expected IOException for HTTP 403")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(msg.contains("403"))
            assertTrue(msg.contains("https://example.com/v3/direction"))
            assertFalse(msg.contains("SECRET_KEY"))
            assertFalse(msg.contains("key="))
        }
    }

    @Test
    fun testExecuteJson_distinguishesProtocolAndServiceFailures() = runBlocking {
        val http = mockHttpClient { request ->
            when (request.encodedPath) {
                "/empty" -> MockReply("")
                "/malformed" -> MockReply("<html>not JSON</html>")
                else -> MockReply("{}", code = 503)
            }
        }
        try {
            val client = TestTransitApiClient(http)
            for (path in listOf("empty", "malformed", "service")) {
                try {
                    client.testExecuteJson { url("https://example.com/$path?key=SECRET_KEY") }
                    fail("Expected failure for $path")
                } catch (error: IOException) {
                    assertEquals(path != "service", error is TransitProtocolFailure)
                    assertEquals(path == "service", error is TransitServiceFailure)
                    assertFalse(error.message.orEmpty().contains("SECRET_KEY"))
                }
            }
        } finally {
            http.close()
        }
    }

    /** 原 testExecuteHttpCall_coroutineCancellationCancelsOkHttpCall：协程取消后，底层请求也被取消、不再完成 */
    @Test
    fun testExecuteHttpCall_coroutineCancellationCancelsUnderlyingCall() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val sawCancel = CompletableDeferred<Unit>()
        val client = TestTransitApiClient(mockHttpClient { _ ->
            entered.complete(Unit)
            try {
                // 模拟慢请求：一直挂起，直到被取消
                awaitCancellation()
            } finally {
                sawCancel.complete(Unit)
            }
        })

        val job = async(Dispatchers.Default) { client.testExecuteHttpCall { url("https://example.com/slow") } }
        withTimeout(2_000L) { entered.await() }
        job.cancelAndJoin()

        withTimeout(2_000L) { sawCancel.await() }
        assertTrue(job.isCancelled)
    }

    /** 总超时：请求挂住超过时限时抛 IOException，消息里只有 scheme + host + path */
    @Test
    fun testExecuteHttpCall_requestTimeout_throwsIOExceptionWithoutQuery() = runBlocking {
        val client = TestTransitApiClient(mockHttpClient(requestTimeoutMillis = 200L) { _ ->
            delay(5_000L)
            MockReply("{}")
        })

        try {
            client.testExecuteHttpCall { url("https://example.com/slow?key=SECRET_KEY") }
            fail("Expected IOException for request timeout")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(msg.contains("timeout", ignoreCase = true), msg)
            assertTrue(msg.contains("https://example.com/slow"), msg)
            assertFalse(msg.contains("SECRET_KEY"), msg)
            assertNull(e.cause, "超时异常的原始消息带完整 URL，不能挂成 cause")
        }
    }

    @Test
    fun testNetworkTimeouts_matchOkHttpEra() {
        assertEquals(20_000L, NetworkTimeouts.REQUEST_MS)
        assertEquals(12_000L, NetworkTimeouts.CONNECT_MS)
        assertEquals(15_000L, NetworkTimeouts.READ_MS)
        assertEquals(12_000L, NetworkTimeouts.WRITE_MS)
    }

    @Test
    fun testRedactUrls_removesQueryFromAnyUrlInText() {
        assertEquals(
            "Request timeout has expired [url=https://restapi.amap.com/v3/assistant/inputtips, request_timeout=20000 ms]",
            NetworkErrors.redactUrls(
                "Request timeout has expired [url=https://restapi.amap.com/v3/assistant/inputtips?keywords=a&key=SECRET, request_timeout=20000 ms]"
            )
        )
        assertEquals(
            "failed: https://restapi.amap.com/v3/direction/transit/integrated",
            NetworkErrors.redactUrls("failed: https://restapi.amap.com/v3/direction/transit/integrated?origin=1,2&key=SECRET")
        )
        assertEquals("no url here", NetworkErrors.redactUrls("no url here"))
    }
}
