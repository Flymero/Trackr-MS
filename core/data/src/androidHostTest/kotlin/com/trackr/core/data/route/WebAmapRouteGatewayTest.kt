package com.trackr.core.data.route

import com.trackr.core.network.remote.amap.AmapTransitApiClient
import com.trackr.core.network.remote.common.createTrackrHttpClient
import com.trackr.core.testing.FakeAmapKeyRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class WebAmapRouteGatewayTest {
    @Test
    fun missingKeyOrConsentNeverSendsRequest() = runTest {
        val http = createTrackrHttpClient(engine = MockEngine { error("Unexpected network request") })
        try {
            val keys = FakeAmapKeyRepository()
            val gateway = WebAmapRouteGateway(AmapTransitApiClient(http), keys)
            assertFalse(gateway.isConfigured())
            assertTrue(gateway.searchTips("人民广场", "上海", null).isFailure)
            keys.setPrivacyConsent(true)
            assertTrue(gateway.planTransit("121,31", "122,32", "A", "B", "上海", null).isFailure)
            keys.setWebKey("test-web-key")
            keys.setPrivacyConsent(false)
            assertTrue(gateway.isConfigured())
            assertTrue(gateway.searchTips("人民广场", "上海", null).isFailure)
        } finally { http.close() }
    }

    @Test
    fun configuredGatewayPassesCityAndLocationToWebClient() = runTest {
        val http = createTrackrHttpClient(engine = MockEngine { request ->
            assertEquals("restapi.amap.com", request.url.host)
            assertEquals("test-web-key", request.url.parameters["key"])
            assertEquals("上海", request.url.parameters["city"])
            assertEquals("人民广场", request.url.parameters["keywords"])
            assertEquals("121.470000,31.230000", request.url.parameters["location"])
            respond("""{"status":"1","tips":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val keys = FakeAmapKeyRepository(webKey = "test-web-key").also { it.setPrivacyConsent(true) }
            val gateway = WebAmapRouteGateway(AmapTransitApiClient(http), keys)
            assertEquals(emptyList(), gateway.searchTips("人民广场", "上海", "121.47,31.23").getOrThrow())
        } finally { http.close() }
    }
}
