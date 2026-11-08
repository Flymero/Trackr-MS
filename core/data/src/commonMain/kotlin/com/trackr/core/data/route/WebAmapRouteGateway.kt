package com.trackr.core.data.route

import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.AmapRouteGateway
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.network.remote.amap.AmapTransitApiClient

/** Hosts without the native Amap SDK can still search and plan with a Web Key. */
class WebAmapRouteGateway(
    private val client: AmapTransitApiClient,
    private val keys: AmapKeyRepository
) : AmapRouteGateway {
    override fun isConfigured() = keys.effectiveWebKey.value.isNotBlank()

    override suspend fun searchTips(keyword: String, cityName: String, location: String?): Result<List<AmapTipItem>> {
        failure()?.let { return Result.failure(it) }
        return client.searchPoiTips(keyword, cityName, keys.effectiveWebKey.value, location)
    }

    override suspend fun planTransit(
        originLngLat: String, destinationLngLat: String, originName: String,
        destName: String, cityName: String, cityd: String?
    ): Result<List<AmapTransitSchemeResult>> {
        failure()?.let { return Result.failure(it) }
        return client.planTransit(originLngLat, destinationLngLat, originName, destName, cityName, keys.effectiveWebKey.value, cityd)
    }

    private fun failure(): Exception? = when {
        keys.privacyConsent.value != true -> IllegalStateException("未同意高德隐私说明")
        !isConfigured() -> IllegalStateException("未配置高德 Web Key")
        else -> null
    }
}
