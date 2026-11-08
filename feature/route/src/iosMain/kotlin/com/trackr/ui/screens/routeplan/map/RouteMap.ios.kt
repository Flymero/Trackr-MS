@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.ui.screens.routeplan.map

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.core.data.repository.AmapKeyRepository
import org.koin.compose.koinInject
import platform.Foundation.*
import platform.UIKit.UIView

/** The native SDK is hosted by UIKit; all route geometry and coordinates remain shared. */
object IosRouteMapBridge {
    var create: ((String) -> UIView)? = null
    var update: ((UIView, String, Double) -> Unit)? = null
    var release: ((UIView) -> Unit)? = null
    var privacy: ((Boolean) -> Unit)? = null
}

actual val isRouteMapSupported: Boolean get() = IosRouteMapBridge.create != null

@Composable
actual fun RouteMap(state: RouteMapState, modifier: Modifier, bottomInset: () -> Dp) {
    val keys = koinInject<AmapKeyRepository>()
    val apiKey by keys.effectiveIosKey.collectAsStateWithLifecycle()
    val consent by keys.privacyConsent.collectAsStateWithLifecycle()
    val factory = IosRouteMapBridge.create
    if (factory == null || apiKey.isBlank() || consent != true) {
        NoMapBackground(modifier)
        return
    }
    val payload = remember(state) { mapPayload(state) }
    key(apiKey) {
        UIKitView(factory = { factory(apiKey) }, modifier = modifier,
            update = { IosRouteMapBridge.update?.invoke(it, payload, bottomInset().value.toDouble()) },
            onRelease = { IosRouteMapBridge.release?.invoke(it) })
    }
}

private fun mapPayload(state: RouteMapState): String {
    fun point(lat: Double, lng: Double) = listOf(lat, lng)
    val overlays = mutableListOf<Map<String, Any>>()
    state.schemes.forEachIndexed { index, scheme ->
        if (index != state.selectedIndex) schemeLines(scheme).forEach { line ->
            overlays += mapOf("kind" to "alternative", "points" to line.points.map { point(it.lat, it.lng) })
        }
    }
    state.schemes.getOrNull(state.selectedIndex)?.let { scheme ->
        selectedSchemeOverlays(scheme).forEach { overlay ->
            overlays += when (overlay) {
                is RouteMapOverlay.Marker -> mapOf("kind" to "marker", "title" to overlay.title,
                    "points" to listOf(point(overlay.point.lat, overlay.point.lng)))
                is RouteMapOverlay.Ride -> mapOf("kind" to "ride", "leg" to overlay.legIndex,
                    "points" to overlay.points.map { point(it.lat, it.lng) })
                is RouteMapOverlay.Walk -> mapOf("kind" to "walk", "points" to overlay.points.map { point(it.lat, it.lng) })
            }
        }
    }
    state.myLocation?.let { overlays += mapOf("kind" to "marker", "title" to "我的位置", "points" to listOf(point(it.lat, it.lng))) }
    val center = state.myLocation ?: state.cityCenter
    val data = NSJSONSerialization.dataWithJSONObject(mapOf(
        "overlays" to overlays,
        "center" to (center?.let { point(it.lat, it.lng) } ?: emptyList<Double>())
    ), 0u, null) ?: return "{}"
    return NSString.create(data = data, encoding = NSUTF8StringEncoding).toString()
}
