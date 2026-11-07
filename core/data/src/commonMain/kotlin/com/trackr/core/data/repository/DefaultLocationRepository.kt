package com.trackr.core.data.repository

import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.UserFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LocationRepository] 的实现，算法原样搬自 `AndroidDeviceLocationProvider.fetchCurrentLocation`
 * （301c124 首页「窗口内取最准」）和 `lastKnownWgsLocation`（0545e2e 规划页「我的位置」兜底）。
 *
 * 年龄一律用单调时钟算：[clock] 必须和 [source] 给出的 [UserFix.elapsedMillis] 同一时基
 * （Android：`SystemClock.elapsedRealtime`）。
 */
class DefaultLocationRepository(
    private val source: LocationSource,
    private val clock: MonotonicClock
) : LocationRepository {

    private val _latest = MutableStateFlow<UserFix?>(null)
    override val latest: StateFlow<UserFix?> = _latest.asStateFlow()

    override suspend fun bestFix(forceFresh: Boolean): UserFix? {
        if (!source.hasAnyPermission()) return null
        return pickBestFix(forceFresh)?.also { _latest.value = it }
    }

    private suspend fun pickBestFix(forceFresh: Boolean): UserFix? {
        // 强制刷新时仅复用 10 秒内极新鲜的定位，否则 60 秒
        val freshThreshold = if (forceFresh) FORCE_FRESH_MAX_AGE_MS else FRESH_MAX_AGE_MS

        var bestFresh: UserFix? = null
        var bestStale: UserFix? = null
        for (fix in source.lastKnownFixes()) {
            val age = ageOf(fix)
            if (age < 0L) continue
            if (age < freshThreshold && isMoreAccurate(fix, bestFresh)) {
                bestFresh = fix
            }
            if (age <= MAX_STALE_LOCATION_AGE_MS && (bestStale == null || age < ageOf(bestStale))) {
                bestStale = fix
            }
        }

        val freshCandidate = bestFresh
        if (freshCandidate != null && isGoodEnough(freshCandidate)) {
            return freshCandidate
        }

        // 窗口内收集多个定位：拿到够准的提前结束，否则取最准的
        var bestLive: UserFix? = null
        var goodFix: UserFix? = null
        withTimeoutOrNull(LOCATION_WINDOW_MS) {
            source.currentFixes()
                .takeWhile { fix ->
                    if (isMoreAccurate(fix, bestLive)) bestLive = fix
                    if (isGoodEnough(fix)) {
                        goodFix = fix
                        false
                    } else {
                        true
                    }
                }
                .collect { }
        }

        return goodFix
            ?: pickMoreAccurate(bestLive, freshCandidate)
            ?: bestStale
    }

    override fun lastKnown(maxAgeMillis: Long): UserFix? {
        if (!source.hasAnyPermission()) return null
        return source.lastKnownFixes()
            .filter { ageOf(it) in 0..maxAgeMillis }
            .minByOrNull { accuracyOf(it) }
    }

    override fun fixes(tier: LocationCadence.Tier): Flow<UserFix> =
        source.fixes(tier).onEach { _latest.value = it }

    override fun hasPermission(): Boolean = source.hasAnyPermission()

    override fun isSystemLocationEnabled(): Boolean = source.isLocationEnabled()

    private fun ageOf(fix: UserFix): Long = clock.elapsedMillis() - fix.elapsedMillis

    private fun accuracyOf(fix: UserFix): Float = fix.accuracyMeters ?: Float.MAX_VALUE

    private fun isGoodEnough(fix: UserFix): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        return accuracy <= GOOD_ACCURACY_METERS
    }

    private fun isMoreAccurate(candidate: UserFix, current: UserFix?): Boolean =
        current == null || accuracyOf(candidate) < accuracyOf(current)

    private fun pickMoreAccurate(a: UserFix?, b: UserFix?): UserFix? = when {
        a == null -> b
        b == null -> a
        accuracyOf(a) <= accuracyOf(b) -> a
        else -> b
    }

    companion object {
        /** 主动定位的收集窗口 */
        const val LOCATION_WINDOW_MS = 4_500L

        /** 精度达到该值即可提前结束定位收集 */
        const val GOOD_ACCURACY_METERS = 100f

        /** lastKnown 直接可用的最大年龄 */
        const val FRESH_MAX_AGE_MS = 60_000L

        /** 强制刷新时 lastKnown 直接可用的最大年龄 */
        const val FORCE_FRESH_MAX_AGE_MS = 10_000L

        /** 兜底使用的旧定位最大年龄（首页） */
        const val MAX_STALE_LOCATION_AGE_MS = 10 * 60 * 1000L

        /** 路线规划「我的位置」兜底：系统 lastKnown 定位的最大年龄 */
        const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L
    }
}
