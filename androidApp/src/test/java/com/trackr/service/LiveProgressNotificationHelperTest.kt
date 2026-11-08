package com.trackr.service

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.model.BoardChoice
import com.trackr.core.model.BoardOption
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.WatchLine
import com.trackr.core.model.WatchedLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LiveProgressNotificationHelperTest {

    private lateinit var context: Application

    /** App 启动时 Koin 建好的实例（原来经 object 转发） */
    private val liveNotifier: LiveProgressNotifier get() = GlobalContext.get().get()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        liveNotifier.liveNotificationEnabledProvider = { true }
    }

    @After
    fun tearDown() {
        liveNotifier.resetLiveNotificationEnabledProvider()
    }

    @Test
    fun collidingSessionHashes_keepNotificationActionsSeparate() {
        // 连完整 hash 都相同，不能靠扩大 requestCode 的位数解决。
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val state = TrackingSessionState(isActive = true, lineName = "59路")
        val first = liveNotifier.buildNotification(context, state, sessionId = "Aa")
        val second = liveNotifier.buildNotification(context, state, sessionId = "BB")

        first.actions.forEach { action ->
            val intent = shadowOf(action.actionIntent).savedIntent
            if (intent.component?.className != TrackingActionReceiver::class.java.name) return@forEach
            val other = second.actions.first { it.title == action.title }
            assertNotEquals(action.actionIntent, other.actionIntent)
            assertEquals("Aa", intent.getStringExtra(TrackingActionReceiver.EXTRA_SESSION_ID))
            assertEquals("BB", shadowOf(other.actionIntent).savedIntent.getStringExtra(TrackingActionReceiver.EXTRA_SESSION_ID))
        }
        val refreshed = liveNotifier.buildNotification(context, state, sessionId = "Aa")
        assertEquals(first.actions.map { it.actionIntent }, refreshed.actions.map { it.actionIntent })
    }

    @Test
    fun collidingWatchLineHashes_keepStopActionsSeparate() {
        val state = watchWaitingState()
        val first = liveNotifier.buildNotification(context, state, sessionId = "watch", watchLineId = "Aa")
        val second = liveNotifier.buildNotification(context, state, sessionId = "watch", watchLineId = "BB")
        val firstStop = first.actions.first { it.title.toString().contains("结束") }.actionIntent
        val secondStop = second.actions.first { it.title.toString().contains("结束") }.actionIntent

        assertNotEquals(firstStop, secondStop)
        assertEquals("Aa", shadowOf(firstStop).savedIntent.getStringExtra(TrackingActionReceiver.EXTRA_WATCH_LINE_ID))
        assertEquals("BB", shadowOf(secondStop).savedIntent.getStringExtra(TrackingActionReceiver.EXTRA_WATCH_LINE_ID))
    }

    @Test
    fun testCalculateCurrentProgress_matchesRemainingStops() {
        // 目标候车站：第 10 站，追踪起始站：第 7 站；追踪区间共 3 个站间，maxProgress = 300
        val baseSession = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7
        )

        // 1. 剩 3 站且刚驶离第 7 站（order=8, progress=0.0）：尚未走出追踪区间 → 0/300
        val stateAtStart = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.0f,
            stopsRemaining = 3
        )
        assertEquals(0, liveNotifier.calculateCurrentProgress(stateAtStart))

        // 2. 剩 2 站、行驶到第 7 与第 8 站中点：已完成 0.5 个站间 → 50/300
        val stateMidHop1 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 0.5f,
            stopsRemaining = 2
        )
        assertEquals(50, liveNotifier.calculateCurrentProgress(stateMidHop1))

        // 3. 停靠第 8 站（progress=1.0）且剩 2 站：已完成 1 个站间 → 100/300
        val stateAtStop8 = baseSession.copy(
            currentBusOrder = 8,
            currentBusProgress = 1.0f,
            stopsRemaining = 2
        )
        assertEquals(100, liveNotifier.calculateCurrentProgress(stateAtStop8))

        // 4. 停靠第 9 站且剩 1 站：已完成 2 个站间 → 200/300（绝不能提前满格）
        val stateAtStop9 = baseSession.copy(
            currentBusOrder = 9,
            currentBusProgress = 1.0f,
            stopsRemaining = 1
        )
        assertEquals(200, liveNotifier.calculateCurrentProgress(stateAtStop9))

        // 5. 第 9 与第 10 站之间行驶（order=10, progress=0.6）且剩 0 站：已完成 2.6 个站间 → 260/300
        val stateApproaching = baseSession.copy(
            currentBusOrder = 10,
            currentBusProgress = 0.6f,
            stopsRemaining = 0
        )
        assertEquals(260, liveNotifier.calculateCurrentProgress(stateApproaching))

        // 6. 车辆已到达目标站停靠（isArrived=true）：进度必须为满格 300/300
        val stateArrived = baseSession.copy(
            currentBusOrder = 10,
            currentBusProgress = 1.0f,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        assertEquals(300, liveNotifier.calculateCurrentProgress(stateArrived))
    }

    @Test
    fun testCalculateCurrentProgress_isMonotonicWithRemainingStops() {
        val baseSession = TrackingSessionState(
            isActive = true,
            lineName = "788",
            targetStopName = "珞雄路雄楚大道",
            targetOrder = 11,
            startTrackingOrder = 6
        )

        // 追踪区间：第 6 → 第 11 站，共 5 个站间，maxProgress = 500
        val remainingStops = listOf(4, 3, 2, 1, 0)
        val progresses = remainingStops.map { remaining ->
            liveNotifier.calculateCurrentProgress(
                baseSession.copy(
                    currentBusOrder = 11 - remaining,
                    currentBusProgress = 0.5f,
                    stopsRemaining = remaining
                )
            )
        }

        progresses.zipWithNext().forEach { (previous, next) ->
            assertTrue("剩余站数减少时进度必须单调递增：$progresses", next > previous)
        }
        // 剩 4 站：completed = 5 - (4 + 0.5) = 0.5 → 50/500
        assertEquals(50, progresses.first())
        // 剩 0 站：completed = 5 - 0.5 = 4.5 → 450/500
        assertEquals(450, progresses.last())
    }

    @Test
    fun testBuildNotification_capsuleAndContentTexts_avoidRemainingZeroStops() {
        // stopsRemaining == 0 但 ETA 90 秒（不在 1..45 秒）：还没到站，写“下一站就到”，不写“剩 0 站”也不写“即将进站”
        val stateApproaching = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7,
            currentBusOrder = 10,
            currentBusProgress = 0.5f,
            stopsRemaining = 0,
            etaSeconds = 90,
            isBusArrivedAtStop = false
        )

        val notifApproaching = liveNotifier.buildNotification(context, stateApproaching)
        assertNotNull(notifApproaching)
        val title = notifApproaching.extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = notifApproaching.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("标题应包含线路名", title.contains("49路"))
        assertEquals("上海体育馆：约 2 分钟 · 下一站就到", text)
        assertTrue("正文不应包含剩 0 站", !text.contains("剩 0 站"))
        assertFalse("没到 45 秒内不应说进站", text.contains("即将进站") || text.contains("到站了"))
        assertEquals("下一站到", liveNotifier.shortCriticalTextFor(stateApproaching))

        // 验证到达状态
        val stateArrived = stateApproaching.copy(
            isBusArrivedAtStop = true,
            etaSeconds = 0
        )
        val notifArrived = liveNotifier.buildNotification(context, stateArrived)
        val arrivedText = notifArrived.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertEquals("上海体育馆：到站了，快去上车", arrivedText)
        assertEquals("到站了", liveNotifier.shortCriticalTextFor(stateArrived))
    }

    // ---------------- 分组换乘两阶段 ----------------

    private fun waitingState() = TrackingSessionState(
        isActive = true,
        lineName = "B612",
        targetStopName = "深大北门",
        targetOrder = 9,
        boardStopName = "科技园",
        boardStopOrder = 5,
        startTrackingOrder = 2,
        currentBusOrder = 3,
        currentBusProgress = 1.0f,
        stopsRemaining = 2,
        etaSeconds = 240,
        legPhase = LegPhase.WAITING_BOARD,
        currentLegIndex = 0,
        totalLegs = 2
    )

    private fun onBoardState() = waitingState().copy(
        legPhase = LegPhase.ON_BOARD,
        startTrackingOrder = 5,
        currentBusOrder = 7,
        currentBusProgress = 1.0f,
        stopsRemaining = 2,
        etaSeconds = 300
    )

    @Test
    fun shortCriticalText_byPhase() {
        assertEquals("B612 2站", liveNotifier.shortCriticalTextFor(waitingState()))
        assertEquals("等车2站", liveNotifier.shortCriticalTextFor(waitingState().copy(lineName = "高峰专线12")))
        assertEquals("2站下车", liveNotifier.shortCriticalTextFor(onBoardState()))
        val arrivedTransfer = onBoardState().copy(stopsRemaining = 0, isBusArrivedAtStop = true)
        assertEquals("换乘", liveNotifier.shortCriticalTextFor(arrivedTransfer))
        val arrivedLast = arrivedTransfer.copy(currentLegIndex = 1)
        assertEquals("已到站", liveNotifier.shortCriticalTextFor(arrivedLast))
        listOf(waitingState(), onBoardState(), arrivedTransfer, waitingState().copy(stopsRemaining = 12)).forEach {
            assertTrue(liveNotifier.shortCriticalTextFor(it).length <= 7)
        }
    }

    @Test
    fun buildTexts_waitingBoard() {
        val texts = liveNotifier.buildTexts(waitingState(), nextLineName = "M492")
        assertEquals("B612 约 4 分钟到 科技园", texts.title)
        assertEquals("还有 2 站", texts.content)
        assertEquals("第 1/2 程 · 等车", texts.subText)

        val noEta = liveNotifier.buildTexts(waitingState().copy(etaSeconds = -1))
        assertEquals("B612 还有 2 站到 科技园", noEta.title)
        assertEquals("暂无到站时间", noEta.content)

        val nextStop = liveNotifier.buildTexts(waitingState().copy(stopsRemaining = 0, etaSeconds = 90))
        assertEquals("B612 约 2 分钟到 科技园", nextStop.title)
        assertEquals("下一站就到", nextStop.content)
        assertEquals("下一站到", liveNotifier.shortCriticalTextFor(waitingState().copy(stopsRemaining = 0, etaSeconds = 90)))

        val arrived = waitingState().copy(stopsRemaining = 0, etaSeconds = 30)
        val arrivedTexts = liveNotifier.buildTexts(arrived)
        assertEquals("B612 到站了 · 科技园", arrivedTexts.title)
        assertEquals("快去上车，上车后点「我已上车」", arrivedTexts.content)
        assertEquals("到站了", liveNotifier.shortCriticalTextFor(arrived))
    }

    @Test
    fun buildTexts_onBoard_withTransferAndRange() {
        val texts = liveNotifier.buildTexts(onBoardState(), nextLineName = "M492")
        assertEquals("还有 2 站在 深大北门 下车", texts.title)
        assertEquals("约 5 分钟 · 下车后换乘 M492 · 科技园 → 深大北门", texts.content)
        assertEquals("第 1/2 程 · 乘车", texts.subText)

        // 最后一程不写换乘
        val last = liveNotifier.buildTexts(onBoardState().copy(currentLegIndex = 1), nextLineName = null)
        assertFalse(last.content.contains("换乘"))
        assertEquals("第 2/2 程 · 乘车", last.subText)
    }

    @Test
    fun notificationActions_byPhase() {
        val waiting = liveNotifier.notificationActions(waitingState())
        assertEquals(listOf("我已上车", "提前2站提醒", "结束"), waiting.map { it.label })
        assertEquals(TrackingActionReceiver.ACTION_CONFIRM_BOARDED, waiting.first().intentAction)

        val onBoard = liveNotifier.notificationActions(onBoardState())
        assertEquals(listOf("跳到下一程", "结束"), onBoard.map { it.label })
        assertEquals(TrackingActionReceiver.ACTION_NEXT_LEG, onBoard.first().intentAction)

        val onBoardLast = liveNotifier.notificationActions(onBoardState().copy(currentLegIndex = 1))
        assertEquals(listOf("结束"), onBoardLast.map { it.label })

        val single = liveNotifier.notificationActions(TrackingSessionState(isActive = true))
        assertEquals(listOf("提前2站提醒(切换)", "结束追踪"), single.map { it.label })
    }

    @Test
    fun progressRange_followsPhase() {
        // 等车：选中车辆时所在站(2) → 上车站(5)，共 3 个站间；车停在第 3 站、剩 2 站 → 1/3
        assertEquals(2 to 5, liveNotifier.progressRangeOf(waitingState()))
        assertEquals(100, liveNotifier.calculateCurrentProgress(waitingState()))
        // 乘车：上车站(5) → 下车站(9)，共 4 个站间；车停在第 7 站、剩 2 站 → 2/4
        assertEquals(5 to 9, liveNotifier.progressRangeOf(onBoardState()))
        assertEquals(200, liveNotifier.calculateCurrentProgress(onBoardState()))
    }

    @Test
    fun buildNotification_onBoard_keepsProgressStyleAndPhaseActions() {
        val notification = liveNotifier.buildNotification(context, onBoardState(), nextLineName = "M492")
        assertEquals("还有 2 站在 深大北门 下车", notification.extras.getCharSequence("android.title")?.toString())
        assertEquals("第 1/2 程 · 乘车", notification.extras.getCharSequence("android.subText")?.toString())
        assertEquals(listOf("跳到下一程", "结束"), notification.actions.map { it.title.toString() })
        assertEquals(400, notification.extras.getInt("android.progressMax"))
        assertEquals(200, notification.extras.getInt("android.progress"))
    }

    // ---------------------------------------------------------------- 一起等（第 15 步）

    private fun watched(id: String, name: String, eta: Int = -1, stops: Int = -1, busId: String? = "b-$id", arrived: Boolean = false) =
        WatchedLine(
            line = WatchLine(lineId = id, lineName = name, stopOrder = 5),
            busId = busId,
            stopsRemaining = stops,
            etaSeconds = eta,
            isArrived = arrived,
            hasData = true
        )

    private fun watchWaitingState() = TrackingSessionState(
        isActive = true,
        lineId = "L1",
        lineName = "325",
        legPhase = LegPhase.WAITING_BOARD,
        boardStopOrder = 5,
        boardStopName = "科技园站",
        targetStopName = "科技园站",
        targetOrder = 5,
        startTrackingOrder = 2,
        currentBusId = "b-L1",
        currentBusOrder = 3,
        stopsRemaining = 2,
        etaSeconds = 240
    )

    private val threeLines = LiveProgressNotifier.WatchNotice(
        lines = listOf(
            watched("L1", "325", eta = 240, stops = 2),
            watched("L2", "M194", eta = 540, stops = 4),
            watched("L3", "19", eta = 900, stops = 7)
        ),
        boardChoice = null
    )

    @Test
    fun watchTogether_waiting_titleIsFastest_contentNamesOnlySecondLine() {
        val notification = liveNotifier.buildNotification(context, watchWaitingState(), watch = threeLines)
        assertEquals("325 约 4 分钟到 科技园站", notification.extras.getCharSequence("android.title")?.toString())
        val content = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(content, content.endsWith("M194 9 分钟"))
        // R6：只写前 2 条
        assertFalse(content, content.contains("19 "))
        assertEquals("一起等 3 条", notification.extras.getCharSequence("android.subText")?.toString())
        assertEquals(listOf("我上车了", "乘车码", "结束"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun watchTogether_actions_transitCodeOpensApp_otherButtonsBroadcast() {
        val waiting = liveNotifier.watchNotificationActions(watchWaitingState())
        assertEquals(TrackingActionReceiver.ACTION_CONFIRM_BOARDED, waiting[0].intentAction)
        assertTrue(waiting[1].opensTransitCode)
        assertEquals(TrackingActionReceiver.ACTION_STOP_TRACKING, waiting[2].intentAction)

        val riding = liveNotifier.watchNotificationActions(watchWaitingState().copy(legPhase = LegPhase.ON_BOARD))
        assertEquals(listOf("乘车码", "结束"), riding.map { it.label })

        val notification = liveNotifier.buildNotification(context, watchWaitingState(), watch = threeLines)
        val transitCode = notification.actions[1].actionIntent
        val intent = org.robolectric.Shadows.shadowOf(transitCode).savedIntent
        // 第 18 步：发 trackr://transit-code 链接（不再用 OPEN_TRANSIT_CODE action），显式指向 MainActivity
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.dataString, intent.dataString.orEmpty().startsWith("trackr://transit-code?provider="))
        assertEquals("com.trackr.MainActivity", intent.component?.className)
        assertTrue(org.robolectric.Shadows.shadowOf(transitCode).isActivityIntent)
    }

    @Test
    fun watchTogether_boardChoice_andOnBoardTexts() {
        val choice = LiveProgressNotifier.WatchNotice(
            lines = threeLines.lines,
            boardChoice = BoardChoice(
                options = listOf(
                    BoardOption(lineId = "L1", lineName = "325", busId = "a"),
                    BoardOption(lineId = "L2", lineName = "M194", busId = "b")
                ),
                sinceMillis = 0L
            )
        )
        val texts = liveNotifier.buildWatchTexts(watchWaitingState(), choice)
        assertEquals("325、M194 都在 科技园站", texts.title)
        assertTrue(texts.content.contains("上了哪辆"))

        val onBoard = watchWaitingState().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "a",
            targetOrder = 12,
            targetStopName = "白石洲",
            stopsRemaining = 6,
            etaSeconds = -1
        )
        val riding = liveNotifier.buildWatchTexts(onBoard, LiveProgressNotifier.WatchNotice(emptyList(), null))
        assertEquals("已上 325", riding.title)
        assertEquals("还有 6 站到终点 白石洲", riding.content)
    }

    @Test
    fun watchTogether_boardChoice_tapOpensChooseBoardedBusLink() {
        val pending = LiveProgressNotifier.WatchNotice(
            lines = threeLines.lines,
            boardChoice = BoardChoice(
                options = listOf(
                    BoardOption(lineId = "L1", lineName = "325", busId = "a"),
                    BoardOption(lineId = "L2", lineName = "M194", busId = "b")
                ),
                sinceMillis = 0L
            ),
            sessionId = "s-1"
        )
        val notification = liveNotifier.buildNotification(context, watchWaitingState(), watch = pending)
        val tap = org.robolectric.Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(android.content.Intent.ACTION_VIEW, tap.action)
        assertEquals("trackr://tracking/board?session=s-1", tap.dataString)
        assertEquals("com.trackr.MainActivity", tap.component?.className)

        // 没有待选：点通知照旧只拉起 App，不带链接
        val plain = liveNotifier.buildNotification(context, watchWaitingState(), watch = threeLines.copy(sessionId = "s-1"))
        assertNull(org.robolectric.Shadows.shadowOf(plain.contentIntent).savedIntent.data)
    }

    @Test
    fun watchTogether_lineShortTexts() {
        assertEquals("M194 9 分钟", liveNotifier.watchLineShort(watched("L2", "M194", eta = 540, stops = 4)))
        assertEquals("M194 3 站", liveNotifier.watchLineShort(watched("L2", "M194", stops = 3)))
        assertEquals("M194 到站了", liveNotifier.watchLineShort(watched("L2", "M194", stops = 0, arrived = true)))
        assertEquals("M194 暂无来车", liveNotifier.watchLineShort(watched("L2", "M194", busId = null)))
    }

    // D3：锁屏可见性。直接建实例，不走 object 转发
    private fun notifierWithLockScreen(setting: () -> Boolean) = LiveProgressNotifier(
        liveNotificationSetting = { true },
        nextLegLineNameOf = { null },
        showOnLockScreenSetting = setting
    )

    @Test
    fun buildNotification_lockScreenOn_isPublicWithoutPublicVersion() {
        val notification = notifierWithLockScreen { true }.buildNotification(context, onBoardState(), nextLineName = null)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNull(notification.publicVersion)
    }

    @Test
    fun buildNotification_lockScreenOff_isPrivateWithRedactedPublicVersion() {
        val notification = notifierWithLockScreen { false }.buildNotification(context, onBoardState(), nextLineName = "M492")
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)

        val public = notification.publicVersion
        assertNotNull(public)
        val title = public!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        assertEquals("追客 · 追踪中", title)
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(public.actions.isNullOrEmpty())
        assertNotNull("脱敏锁屏通知仍应能打开 App", public.contentIntent)
        // 完整版照旧
        assertEquals("还有 2 站在 深大北门 下车", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun buildNotification_lockScreenSettingThrows_fallsBackToPublic() {
        val notification = notifierWithLockScreen { error("读不到设置") }
            .buildNotification(context, onBoardState(), nextLineName = null)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNull(notification.publicVersion)
    }
}
