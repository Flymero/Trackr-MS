package com.trackr.ui.screens.routeplan.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.trackr.data.remote.amap.AmapSdkManager
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_map_recenter
import com.trackr.ui.screens.routeplan.LocalAmapMapSession
import org.jetbrains.compose.resources.stringResource

actual val isRouteMapSupported: Boolean = true

/**
 * Android：高德 3D 地图。实例由 Activity 级 [com.trackr.ui.screens.routeplan.AmapMapSession] 托管
 * （`ProvideAmapMapSession` 包在 NavDisplay 外面，随 Activity ON_PAUSE / ON_RESUME 暂停 / 恢复，301c124）。
 *
 * 地图可用性（D2：不再有内置示意图）：
 * - 没有地图会话（测试环境）、设备 ABI 不支持 → 只画底色；
 * - 地图实例创建失败 → 同样只画底色，浮层说明原因并可重试；
 * - 软失败（底图 15 秒没就绪）→ 保留真实地图，浮层提示，避免把「加载慢」当成「用不了」。
 */
@Composable
actual fun RouteMap(state: RouteMapState, modifier: Modifier, bottomInset: () -> Dp) {
    val session = LocalAmapMapSession.current
    val androidKey = state.androidKey
    // 地图跟随应用深浅色设置，包括手动选择的夜间样式。
    val isDarkTheme = TrackrTheme.isDark

    val mapUnavailableReason = remember(androidKey) { AmapSdkManager.mapUnavailableReason(androidKey) }
    var mapRuntimeError by remember(androidKey) { mutableStateOf<String?>(null) }
    var mapLoadTimeout by remember(androidKey) { mutableStateOf<String?>(null) }
    var mapRetryToken by remember(androidKey) { mutableIntStateOf(0) }
    var showMapDiagnostics by remember { mutableStateOf(false) }
    var recenterTrigger by rememberSaveable { mutableIntStateOf(0) }

    val canDrawMap = session != null && mapUnavailableReason == null
    val showMap = canDrawMap && mapRuntimeError == null

    Box(modifier = modifier.fillMaxSize()) {
        if (showMap && session != null) {
            AmapRealVectorMapView(
                session = session,
                schemes = state.schemes,
                selectedIndex = state.selectedIndex,
                customApiKey = androidKey,
                darkMode = isDarkTheme,
                myLocation = state.myLocation,
                cityCenter = state.cityCenter,
                recenterTrigger = recenterTrigger,
                retryToken = mapRetryToken,
                onMapLoaded = { mapLoadTimeout = null },
                onLoadTimeout = { mapLoadTimeout = it },
                onRuntimeError = { mapRuntimeError = it },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            NoMapBackground()
        }

        // 地图实例创建失败 / 底图迟迟没就绪：浮层提示，可以直接重试或看诊断信息
        val mapNotice = if (canDrawMap) mapRuntimeError ?: mapLoadTimeout else null
        if (mapNotice != null) {
            MapLoadTimeoutNotice(
                reason = mapNotice,
                onRetry = {
                    mapRuntimeError = null
                    mapLoadTimeout = null
                    mapRetryToken++
                },
                onShowDiagnostics = { showMapDiagnostics = true },
                onDismiss = {
                    mapLoadTimeout = null
                    mapRuntimeError = null
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }

        // 回到定位按钮：贴着底部方案面板的上沿，面板变高变矮时跟着一起上下
        if (showMap) {
            FloatingActionButton(
                onClick = { recenterTrigger++ },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp)
                    .offset { IntOffset(0, -(bottomInset().roundToPx() + 12.dp.roundToPx())) }
                    .size(42.dp)
                    .testTag("btn_recenter"),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = stringResource(RouteRes.string.route_map_recenter),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // 地图诊断信息（核对包名 / SHA-1 / 生效 Key 是否和控制台一致）
    if (showMapDiagnostics) {
        MapDiagnosticsDialog(
            androidKey = androidKey,
            onDismiss = { showMapDiagnostics = false }
        )
    }
}
