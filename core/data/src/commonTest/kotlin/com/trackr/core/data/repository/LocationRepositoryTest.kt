package com.trackr.core.data.repository

import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.UserFix
import com.trackr.core.testing.FakeLocationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [DefaultLocationRepository]：首页「窗口内取最准」和规划页「我的位置」兜底的规则（接 FakeLocationSource）。
 * 迁入原 HomeViewModelTest 的两条：窗口内取最准、旧定位超过 10 分钟不用。
 */
class LocationRepositoryTest {

    private var now = 1_000_000L
    private val clock = MonotonicClock { now }
    private val source = FakeLocationSource()
    private val repository = DefaultLocationRepository(source, clock)

    private fun fix(lat: Double, accuracy: Float?, ageMillis: Long) =
        UserFix(lat = lat, lng = 120.0, accuracyMeters = accuracy, elapsedMillis = now - ageMillis)

    @Test
    fun noPermission_returnsNullWithoutListening() = runTest {
        source.precisePermission = false
        source.lastKnownFixList = listOf(fix(30.0, 10f, 1_000))

        assertNull(repository.bestFix(forceFresh = true))
        assertNull(repository.lastKnown(DefaultLocationRepository.MAX_LAST_KNOWN_AGE_MS))
        assertEquals(0, source.currentFixesCount)
    }

    @Test
    fun coarseOnlyPermission_stillLocates() = runTest {
        source.precisePermission = false
        source.anyPermission = true
        val cached = fix(30.0, 50f, 5_000)
        source.lastKnownFixList = listOf(cached)

        assertEquals(cached, repository.bestFix(forceFresh = true))
    }

    /** 首页分「没授权」和「系统定位没开」两句话，仓库如实把这两层透出来（issue #34） */
    @Test
    fun permissionAndSystemSwitch_arePassedThrough() = runTest {
        assertEquals(true, repository.hasPermission())
        assertEquals(true, repository.isSystemLocationEnabled())

        source.anyPermission = false
        source.systemLocationEnabled = false

        assertEquals(false, repository.hasPermission())
        assertEquals(false, repository.isSystemLocationEnabled())
    }

    @Test
    fun freshAndAccurateLastKnown_isUsedDirectly() = runTest {
        val cached = fix(30.0, 80f, 9_000)
        source.lastKnownFixList = listOf(cached)

        assertEquals(cached, repository.bestFix(forceFresh = true))
        assertEquals(0, source.currentFixesCount)
        assertEquals(cached, repository.latest.value)
    }

    @Test
    fun forceFresh_rejectsLastKnownOlderThanTenSeconds_butNormalAcceptsSixty() = runTest {
        val cached = fix(30.0, 20f, 30_000)
        source.lastKnownFixList = listOf(cached)
        source.currentFixScript = emptyList()

        // 强制刷新：30 秒前的定位不算新鲜，要开窗口；窗口什么都没拿到时退回它（10 分钟内的旧定位）
        assertEquals(cached, repository.bestFix(forceFresh = true))
        assertEquals(1, source.currentFixesCount)

        // 不强制：60 秒内够新，直接用，不开窗口
        assertEquals(cached, repository.bestFix(forceFresh = false))
        assertEquals(1, source.currentFixesCount)
    }

    @Test
    fun freshButInaccurate_opensWindow_andPicksMostAccurateInWindow() = runTest {
        // 原 HomeViewModelTest：窗口内先后来了 800 m 和 300 m 的定位，取 300 m 的
        source.lastKnownFixList = listOf(fix(30.0, 1_500f, 2_000))
        source.currentFixScript = listOf(fix(30.1, 800f, 0), fix(30.2, 300f, 0))

        val result = repository.bestFix(forceFresh = true)
        assertEquals(30.2, result?.lat)
    }

    @Test
    fun windowEndsEarlyOnGoodEnoughFix() = runTest {
        source.lastKnownFixList = emptyList()
        source.currentFixScript = listOf(fix(30.1, 500f, 0), fix(30.2, 90f, 0), fix(30.3, 5f, 0))

        // 拿到 ≤100 m 的就结束，后面更准的不再等
        assertEquals(30.2, repository.bestFix(forceFresh = true)?.lat)
    }

    @Test
    fun windowTimesOutAfterFourAndHalfSeconds_thenUsesBestSeen() = runTest {
        source.lastKnownFixList = listOf(fix(30.0, 2_000f, 3_000))
        // currentFixScript 为 null：currentFixes 永不结束，只能等窗口超时
        var result: UserFix? = null
        val job = launch { result = repository.bestFix(forceFresh = true) }
        runCurrent()
        source.emit(fix(30.5, 400f, 0))
        runCurrent()
        advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS - 1)
        runCurrent()
        assertNull(result)
        advanceTimeBy(2)
        job.join()
        // 窗口里最准的 400 m 比 lastKnown 的 2000 m 准
        assertEquals(30.5, result?.lat)
    }

    @Test
    fun staleLocationOlderThanTenMinutes_isRejected() = runTest {
        // 原 HomeViewModelTest.testRefresh_whenCachedLocationOlderThanTenMinutes_isRejected
        source.lastKnownFixList = listOf(fix(30.0, 30f, DefaultLocationRepository.MAX_STALE_LOCATION_AGE_MS + 1))
        source.currentFixScript = emptyList()

        assertNull(repository.bestFix(forceFresh = true))
    }

    @Test
    fun staleFallback_prefersNewestWithinTenMinutes() = runTest {
        val older = fix(30.0, 10f, 9 * 60_000L)
        val newer = fix(30.1, 900f, 2 * 60_000L)
        source.lastKnownFixList = listOf(older, newer)
        source.currentFixScript = emptyList()

        assertSame(newer, repository.bestFix(forceFresh = true))
    }

    @Test
    fun futureTimestamp_isIgnored() = runTest {
        source.lastKnownFixList = listOf(fix(30.0, 10f, -5_000))
        source.currentFixScript = emptyList()

        assertNull(repository.bestFix(forceFresh = true))
    }

    @Test
    fun lastKnown_picksMostAccurateWithinMaxAge() {
        val accurate = fix(30.0, 15f, 5 * 60_000L)
        val rough = fix(30.1, 200f, 60_000L)
        val tooOld = fix(30.2, 5f, 11 * 60_000L)
        source.lastKnownFixList = listOf(rough, accurate, tooOld)

        assertEquals(accurate, repository.lastKnown(DefaultLocationRepository.MAX_LAST_KNOWN_AGE_MS))
        assertNull(repository.lastKnown(30_000L))
    }

    @Test
    fun fixes_updatesLatest() = runTest {
        val incoming = fix(31.0, 8f, 0)
        source.currentFixScript = null
        val job = launch { repository.fixes(LocationCadence.Tier.BOARDING_CHECK).first() }
        runCurrent()
        source.emit(incoming)
        job.join()
        assertEquals(incoming, repository.latest.value)
        assertEquals(listOf(LocationCadence.Tier.BOARDING_CHECK), source.requestedTiers)
    }
}
