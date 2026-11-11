package com.trackr.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.UserFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** Android 的单调时钟：`SystemClock.elapsedRealtime()`，和 `Location.elapsedRealtimeNanos` 同一时基 */
val AndroidMonotonicClock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }

/**
 * [LocationSource] 的 Android 实现（系统 LocationManager，WGS-84）。
 *
 * 原来的 `AndroidDeviceLocationProvider` 拆成两半：取 lastKnown、开监听这些系统调用留在这里，
 * 「够新够准就直接用、否则窗口内取最准」的算法搬进 `:core:data` 的 `DefaultLocationRepository`（第 8 步）。
 * 追踪服务的定位（原 `ServiceLocationSource`）第 12 步并进 [fixes]。
 */
class AndroidLocationSource(context: Context) : LocationSource {

    private val appContext: Context = context.applicationContext ?: context
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun hasPrecisePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun hasAnyPermission(): Boolean =
        hasPrecisePermission() ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 系统「位置信息」总开关。关着时所有 provider 都拿不到数据（权限给了也一样），
     * 首页据此把提示写成「手机定位服务未开启」而不是笼统的「定位不可用」。
     */
    override fun isLocationEnabled(): Boolean {
        val lm = locationManager ?: return false
        return try {
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Exception) {
            false
        }
    }

    /** 各个已开启的 provider 缓存的最近定位（fused / network / gps / passive，各一个） */
    override fun lastKnownFixes(): List<UserFix> {
        if (!hasAnyPermission()) return emptyList()
        val lm = locationManager ?: return emptyList()
        return LAST_KNOWN_PROVIDERS.mapNotNull { provider ->
            try {
                if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider)?.toUserFix() else null
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun lastKnown(): UserFix? = lastKnownFixes().maxByOrNull { it.elapsedMillis }

    /** 在 fused / network / gps 上以最高频率（0 毫秒、0 米）监听，收集结束即注销 */
    override fun currentFixes(): Flow<UserFix> = listen(ACTIVE_PROVIDERS, minTimeMillis = 0L)

    /**
     * 按档位持续定位（追踪服务判上车 / 下车用，原 `ServiceLocationSource`，第 12 步并进来）。
     *
     * 只在有精确定位权限时出数据：模糊定位精度在公里级，判断不了人在不在车上。
     * Android 12+ 有 fused 就只用 fused；否则高精度档同时听 gps 和 network，省电档只听 network（都没有时退回 gps）。
     * 多个来源同时回调时「差的不顶掉好的」由调用方按 `TrackingEngine.shouldAcceptServiceFix` 过滤。
     * 收集结束即注销监听。
     */
    @SuppressLint("MissingPermission") // 权限在 hasPrecisePermission() 中检查
    override fun fixes(tier: LocationCadence.Tier): Flow<UserFix> {
        if (tier == LocationCadence.Tier.OFF) return emptyFlow()
        return callbackFlow {
            val lm = locationManager
            if (lm == null || !hasPrecisePermission()) {
                close()
                return@callbackFlow
            }
            // 先发出各 provider 缓存的最近定位，避免刚开定位或切档位时丢掉刚拿到的有效定位
            lastKnownFixes().forEach { trySend(it) }
            val request = LocationRequestCompat.Builder(tier.intervalMillis)
                .setQuality(
                    if (tier.highAccuracy) LocationRequestCompat.QUALITY_HIGH_ACCURACY
                    else LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                )
                .setMinUpdateIntervalMillis(tier.intervalMillis / 2)
                .build()
            val executor = ContextCompat.getMainExecutor(appContext)
            // 每个 provider 一个监听器：同一个监听器重复注册会替换掉前一个 provider
            val listeners = mutableListOf<LocationListenerCompat>()
            pickTrackingProviders(lm, tier).forEach { provider ->
                val listener = LocationListenerCompat { location -> trySend(location.toUserFix()) }
                try {
                    LocationManagerCompat.requestLocationUpdates(lm, provider, request, executor, listener)
                    listeners += listener
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot request location updates from $provider", e)
                }
            }
            if (listeners.isEmpty()) close()
            awaitClose {
                listeners.forEach { listener -> runCatching { LocationManagerCompat.removeUpdates(lm, listener) } }
            }
        }
    }

    private fun pickTrackingProviders(lm: LocationManager, tier: LocationCadence.Tier): List<String> {
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (tier.highAccuracy && LocationManager.GPS_PROVIDER in enabled) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in enabled) add(LocationManager.NETWORK_PROVIDER)
            if (isEmpty() && LocationManager.GPS_PROVIDER in enabled) add(LocationManager.GPS_PROVIDER)
        }
    }

    private fun listen(providers: List<String>, minTimeMillis: Long): Flow<UserFix> = callbackFlow {
        val lm = locationManager
        if (lm == null || !hasAnyPermission()) {
            close()
            return@callbackFlow
        }
        val enabled = providers.filter { provider ->
            try {
                lm.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toUserFix())
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        var requestedAny = false
        for (provider in enabled) {
            try {
                lm.requestLocationUpdates(provider, minTimeMillis, 0f, listener, Looper.getMainLooper())
                requestedAny = true
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        if (!requestedAny) {
            close()
        }
        awaitClose {
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val TAG = "AndroidLocationSource"
        val LAST_KNOWN_PROVIDERS = listOf(
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val ACTIVE_PROVIDERS = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    }
}

/**
 * 系统定位转成 [UserFix]。定位时刻优先用单调时钟 `elapsedRealtimeNanos`（不受用户改系统时间影响），
 * 缺失（为 0）时按墙钟年龄折算到同一时基。
 */
internal fun Location.toUserFix(): UserFix {
    val elapsedMillis = if (elapsedRealtimeNanos > 0L) {
        elapsedRealtimeNanos / 1_000_000L
    } else {
        SystemClock.elapsedRealtime() - (System.currentTimeMillis() - time)
    }
    return UserFix(
        lat = latitude,
        lng = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        elapsedMillis = elapsedMillis
    )
}
