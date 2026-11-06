package com.trackr.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.data.repository.TrackingSessionStore
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock

/**
 * [AndroidTrackingController] 的冷启动接续：仓库在 init 里异步读盘，接续必须等读盘完成后才发生
 * （否则 onStart 时 `session.value` 还是 null，接续永远不生效）。
 */
@RunWith(RobolectricTestRunner::class)
class TrackingControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val target = TrackingTarget.SingleLine(
        cityId = "sh",
        lineId = "L49",
        lineName = "49路",
        targetStopName = "上海体育馆",
        targetOrder = 10
    )

    /** 读盘被卡住，直到 [release]：模拟冷启动时盘里的会话还没读出来 */
    private class BlockingReadStore(private val delegate: TrackingSessionStore) : TrackingSessionStore {
        val readStarted = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        fun release() = released.complete(Unit)
        override suspend fun read(): List<TrackingSession> {
            readStarted.complete(Unit)
            released.await()
            return delegate.read()
        }
        override suspend fun write(sessions: List<TrackingSession>) = delegate.write(sessions)
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun TrackingSessionStore.seedInterruptedSession() = runBlocking {
        val now = Clock.System.now().toEpochMilliseconds()
        val session = requireNotNull(TrackingEngine.begin("s1", 3, target, now))
        // 用 wall clock 的「现在」，仓库读回时才不会因为太旧被丢掉
        write(listOf(session))
    }

    private fun repoOn(store: TrackingSessionStore) =
        DefaultTrackingRepository(store = store, scope = appScope, clock = Clock.System) { "s9" }

    @Test
    fun resumeInterrupted_waitsForRepositoryLoad_beforeStartingService() = runBlocking {
        val storage = InMemoryTrackingSessionStore()
        storage.seedInterruptedSession()
        val blocking = BlockingReadStore(storage)
        val repo = repoOn(blocking)

        val startedIds = mutableListOf<String>()
        val controller = AndroidTrackingController(
            context = context,
            repository = repo,
            starter = { _, sessionId -> startedIds += sessionId },
            appScope = appScope
        )

        // 读盘还没完成：同步不接续，也绝不提前启动服务
        assertFalse(controller.resumeInterrupted())
        blocking.readStarted.await()
        assertEquals(emptyList<String>(), startedIds)

        // 读盘完成：接续在应用级作用域里进行
        blocking.release()
        withTimeout(5_000) {
            while (startedIds.isEmpty()) delay(10)
        }
        assertEquals(listOf("s1"), startedIds)
        // 接续的确实是那个中断会话
        assertTrue(repo.session.value?.interrupted == true)
    }

    @Test
    fun resumeInterrupted_noInterruptedSession_doesNotStartService() = runBlocking {
        val repo = repoOn(InMemoryTrackingSessionStore())
        val startedIds = mutableListOf<String>()
        val controller = AndroidTrackingController(
            context = context,
            repository = repo,
            starter = { _, sessionId -> startedIds += sessionId },
            appScope = appScope
        )

        assertFalse(controller.resumeInterrupted())

        // 等读盘完成后再空转一会儿，确认没有误启动服务
        repo.awaitLoaded()
        delay(200)
        assertEquals(emptyList<String>(), startedIds)
    }

    @Test
    fun resumeInterrupted_alreadyLoaded_interruptedSession_startsImmediately() = runBlocking {
        val storage = InMemoryTrackingSessionStore()
        storage.seedInterruptedSession()
        val repo = repoOn(storage)
        repo.awaitLoaded()

        val restored = requireNotNull(repo.session.value)
        assertTrue(restored.interrupted)

        val startedIds = mutableListOf<String>()
        val controller = AndroidTrackingController(
            context = context,
            repository = repo,
            starter = { _, sessionId -> startedIds += sessionId },
            appScope = appScope
        )

        // 已经读过盘：行为不变，同步就能接上
        assertTrue(controller.resumeInterrupted())
        assertEquals(listOf("s1"), startedIds)
    }
}
