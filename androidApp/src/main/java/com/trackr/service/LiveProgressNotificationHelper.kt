package com.trackr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.trackr.R
import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.model.BoardChoice
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.WatchedLine
import com.trackr.core.navigation.deeplink.DeepLinkUris
import com.trackr.util.TransitCodeHelper
import com.trackr.service.vendor.LiveUpdatePromotion
import com.trackr.service.vendor.OppoFluidCloudAdapter
import com.trackr.service.vendor.XiaomiFocusAdapter

/**
 * 负责构建常驻实时公交进度通知，深度适配 Android 16 / Core-KTX 1.19.0 的 NotificationCompat.ProgressStyle 实时活动
 *
 * 第 4 步从 object 改成类，由 Koin 提供单例；旧的 [LiveProgressNotificationHelper] 暂时转发到这里（第 12 步删）。
 *
 * @param liveNotificationSetting 读「实时通知」设置开关（Koin 里接仓库）
 * @param nextLegLineNameOf 第 N 程之后那一程的线路名（Koin 里接 `TrackingRepository` 的会话）
 * @param showOnLockScreenSetting 读「锁屏显示」开关（D3）；读不到按开
 * @param transitCodeProviderSetting 读「乘车码」默认平台（一起等通知的「乘车码」按钮用）；读不到按「每次询问」
 */
class LiveProgressNotifier(
    private val liveNotificationSetting: () -> Boolean,
    private val nextLegLineNameOf: (legIndex: Int) -> String?,
    private val showOnLockScreenSetting: () -> Boolean = { true },
    private val transitCodeProviderSetting: () -> TransitCodeProvider = { TransitCodeProvider.ASK_ALWAYS }
) {

    /**
     * 一起等时通知要多写的东西（第 15 步）：等车时的几条线（按到站先后）和「上了哪辆？」待选。
     * 单线路、路线传 null。
     */
    data class WatchNotice(
        val lines: List<WatchedLine>,
        val boardChoice: BoardChoice?,
        /** 有待选时点通知打开「上了哪辆？」弹层要带的会话 id */
        val sessionId: String = ""
    ) {
        companion object {
            /** 会话是一起等（等车或已上车）时给出，否则 null */
            fun of(session: TrackingSession?): WatchNotice? =
                session?.takeIf { it.watchTarget != null }
                    ?.let { WatchNotice(it.watchedLines, it.boardChoice, it.sessionId) }
        }
    }

    /**
     * 是否开启常驻实时通知，以可注入 provider 的形式提供，
     * 避免直接耦合全局单例，同时保证读取失败时安全降级。
     * 兜底值见 [readLiveNotificationEnabled]。
     */
    internal var liveNotificationEnabledProvider: () -> Boolean = { readLiveNotificationEnabled() }

    internal fun resetLiveNotificationEnabledProvider() {
        liveNotificationEnabledProvider = { readLiveNotificationEnabled() }
    }

    /**
     * 读取“实时通知”开关；读取失败时返回 [LIVE_NOTIFICATION_ENABLED_FALLBACK]。
     * 常驻通知与 [ArrivalAlertNotifier] 共用此函数，保证两处兜底值一致。
     */
    internal fun readLiveNotificationEnabled(): Boolean =
        runCatching { liveNotificationSetting() }
            .getOrDefault(LIVE_NOTIFICATION_ENABLED_FALLBACK)

    /**
     * 确保常驻低干扰渠道创建
     */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID_LIVE_PROGRESS)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_LIVE_PROGRESS,
                    CHANNEL_NAME_LIVE_PROGRESS,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "常驻显示当前正在追踪公交的站间进度、剩余站数与预估时间"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 计算进度标尺：以「追踪起始站 → 目标候车站」的追踪区间为基准，
     * 保证进度条填充比例与「剩 N 站」文案始终一致
     */
    fun calculateSpanAndMaxProgress(startTrackingOrder: Int, targetOrder: Int): Pair<Int, Int> {
        val hops = (targetOrder - startTrackingOrder).coerceAtLeast(1)
        return hops to (hops * 100)
    }

    /**
     * 按阶段确定进度条区间（起始站序 → 目标站序）：
     * - WAITING_BOARD：选中车辆时它所在的站 → 上车站
     * - ON_BOARD：上车站 → 下车站
     * - SINGLE：追踪起始站 → 目标候车站（原有语义）
     */
    fun progressRangeOf(state: TrackingSessionState): Pair<Int, Int> = when (state.legPhase) {
        LegPhase.WAITING_BOARD -> {
            val board = if (state.boardStopOrder > 0) state.boardStopOrder else state.targetOrder
            state.startTrackingOrder.coerceAtMost(board - 1).coerceAtLeast(1) to board
        }
        LegPhase.ON_BOARD -> {
            val board = if (state.boardStopOrder > 0) state.boardStopOrder else state.startTrackingOrder
            board to state.targetOrder
        }
        LegPhase.SINGLE -> state.startTrackingOrder to state.targetOrder
    }

    /**
     * 计算当前车辆相对于追踪区间的进度 [0, maxProgress]
     * 剩余站数是唯一基准：已完成站间数 = 区间站间数 - (剩余站数 + 未走完的当前站间比例)
     */
    fun calculateCurrentProgress(
        state: TrackingSessionState,
        isArrived: Boolean = ArrivalRules.isSessionArrived(state)
    ): Int {
        val (startOrder, endOrder) = progressRangeOf(state)
        val (hops, maxProgress) = calculateSpanAndMaxProgress(startOrder, endOrder)
        if (isArrived) return maxProgress
        if (state.currentBusOrder <= 0 || state.stopsRemaining < 0) return 0

        // 用 Double 计算，避免 1 - 0.6f 之类的浮点误差把 260 截断成 259
        val segmentFraction = state.currentBusProgress.coerceIn(0f, 1f).toDouble()
        val remainingHops = state.stopsRemaining + (1.0 - segmentFraction)
        val completedHops = (hops - remainingHops).coerceIn(0.0, hops.toDouble())
        return (completedHops / hops * maxProgress).toInt().coerceIn(0, maxProgress)
    }

    /**
     * 构建站点标点：系统最多渲染 4 个，且首尾位置的标点会被丢弃，
     * 因此只在区间内部均匀取点，站距过密时抽稀
     */
    private fun buildStationPoints(
        span: Int,
        currentProgress: Int
    ): List<NotificationCompat.ProgressStyle.Point> {
        val maxPoints = 4
        if (span <= 1) return emptyList()
        val innerHops = (1 until span).toList()
        val step = ((innerHops.size + maxPoints - 1) / maxPoints).coerceAtLeast(1)
        return innerHops
            .filterIndexed { index, _ -> index % step == 0 }
            .take(maxPoints)
            .map { hopIndex ->
                val pointPos = hopIndex * 100
                val pointColor = if (pointPos <= currentProgress) COLOR_COMPLETED else COLOR_REMAINING
                NotificationCompat.ProgressStyle.Point(pointPos).setColor(pointColor)
            }
    }

    /**
     * 构建适合前台服务与常驻通知栏的 NotificationCompat 实例
     */
    fun buildNotification(
        context: Context,
        state: TrackingSessionState,
        nextLineName: String? = nextLegLineNameOf(state.currentLegIndex),
        watch: WatchNotice? = null,
        sessionId: String = "",
        allowPromotion: Boolean = true,
        /** 一起等里这条通知属于哪条线（按钮广播带出去，点「结束」只去掉这条线） */
        watchLineId: String = ""
    ): Notification {
        ensureNotificationChannel(context)

        val (startOrder, endOrder) = progressRangeOf(state)
        val (span, maxProgress) = calculateSpanAndMaxProgress(startOrder, endOrder)
        val isArrived = isArrivedState(state)
        val currentProgress = calculateCurrentProgress(state, isArrived)

        // 3. 单段进度条：填充/未填充由 styledByProgress 统一着色，避免双段叠加导致填充比例失真
        val segments = listOf(
            NotificationCompat.ProgressStyle.Segment(maxProgress).setColor(COLOR_COMPLETED)
        )

        // 4. 站点标点：仅取区间内部站点，且受系统 4 个标点上限约束
        val points = buildStationPoints(span, currentProgress)

        val progressStyle = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(currentProgress)
            .setProgressSegments(segments)
            .setProgressPoints(points)
            .setProgressTrackerIcon(
                IconCompat.createWithResource(context, R.drawable.ic_stat_trackr)
            )

        // 5~6. 胶囊短文本与标题 / 正文 / 展开文本（按阶段）
        val texts = if (watch != null) buildWatchTexts(state, watch) else buildTexts(state, nextLineName)

        // 7. 通知点击与 Action Intent：一起等有「上了哪辆？」待选时点通知直接打开那个弹层，否则只拉起 App
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            requestCodeOf(CONTENT_REQUEST_CODE, sessionId, watchLineId),
            contentIntent(context, watch),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isLiveEnabled = liveNotificationEnabledProvider()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LIVE_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_trackr)
            .setContentTitle(texts.title)
            .setContentText(texts.content)
            .setSubText(texts.subText)
            .setStyle(progressStyle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(maxProgress, currentProgress, false)
            .setContentIntent(contentPendingIntent)

        // D3：默认锁屏显示全部内容；关了锁屏开关只显示「追客 · 追踪中」（系统设成隐藏敏感内容时生效）
        if (runCatching { showOnLockScreenSetting() }.getOrDefault(true)) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(buildLockScreenPublicVersion(context))
        }

        // 按钮（最多 3 个）：等车 = 我已上车 / 提前N站提醒 / 结束；乘车 = 跳到下一程（非最后一程）/ 结束
        // 一起等：等车 = 我上车了 / 乘车码 / 结束；乘车 = 乘车码 / 结束
        val actions = if (watch != null) watchNotificationActions(state) else notificationActions(state)
        actions.forEach { action ->
            val intent = if (action.opensTransitCode) {
                transitCodePendingIntent(context, action.requestCode)
            } else {
                receiverPendingIntent(context, action.intentAction, action.requestCode, sessionId, watchLineId)
            }
            builder.addAction(0, action.label, intent)
        }

        if (isLiveEnabled && allowPromotion) {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(texts.shortCriticalText)
            if (XiaomiFocusAdapter.isXiaomiRom()) {
                XiaomiFocusAdapter.attachFocusParams(builder, state, nextLineName)
            }
        }

        val notification = if (isLiveEnabled && allowPromotion) buildPromotable(builder) else builder.build()
        if (isLiveEnabled && allowPromotion && LiveUpdatePromotion.isPromotionSupported()) {
            logPromotionState(context, notification)
        }
        return notification
    }

    /**
     * 染色和上岛的关系，平台代码和官方文档说法正好相反（issue #27）：
     * - AOSP `Notification.hasPromotableCharacteristics()` 要求 `isColorizedRequested()`，
     *   不染色在 Pixel / 原生 Android 16 上永远 promotable=false；
     * - developer.android.com 的 Live Updates 细则却写「不能 setColorized(true)」。
     * 谁对由设备说了算：先按文档不染色，设备仍判定不可上岛再染一次，用设备认可的那个；
     * 两次都不认就保持不染色。系统低于 Android 16 没有这套判定，直接不染色。
     * 只能在真机验证，Robolectric 4.14 还没有 SDK 36 运行时。
     */
    private fun buildPromotable(builder: NotificationCompat.Builder): Notification {
        if (!LiveUpdatePromotion.isPromotionSupported()) return builder.build()
        val plain = builder.build()
        if (isPromotable(plain)) return plain
        val colorized = builder.setColorized(true).build()
        return if (isPromotable(colorized)) colorized else plain
    }

    private fun isPromotable(notification: Notification): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            notification.hasPromotableCharacteristics()

    /** 锁屏开关关掉时锁屏上看到的版本：不带线路、站名和按钮，但点了要能打开 App（不然锁屏上这条是死链） */
    private fun buildLockScreenPublicVersion(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID_LIVE_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_trackr)
            .setContentTitle(LOCK_SCREEN_PUBLIC_TITLE)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    CONTENT_REQUEST_CODE,
                    contentIntent(context, watch = null),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    /** 通知上的文字 */
    data class NotificationTexts(
        val title: String,
        val content: String,
        val subText: String,
        val shortCriticalText: String
    )

    /**
     * 通知按钮。
     * @param opensTransitCode true = 点了打开乘车码（打开 App 的乘车码入口，和桌面快捷方式同一个），不发广播
     */
    data class NotificationAction(
        val label: String,
        val intentAction: String,
        val requestCode: Int,
        val opensTransitCode: Boolean = false
    )

    internal fun isArrivedState(state: TrackingSessionState): Boolean =
        ArrivalRules.isSessionArrived(state)

    private fun isLastLeg(state: TrackingSessionState): Boolean =
        state.currentLegIndex >= state.totalLegs - 1

    private fun etaMinutesText(etaSeconds: Int): String? =
        ArrivalText.minutesOf(etaSeconds)?.let { "约 $it 分钟" }

    /**
     * 状态栏胶囊短文本（Android 16 shortCriticalText，<= 7 字符）。
     * - 等车："B612 2站"，放不下时"等车2站"；车下一站就到上车站时"下一站到"
     * - 乘车："2站下车"；到换乘站"换乘"
     * 「到站」只按 [ArrivalRules] 判（ETA 1..45 秒或已停靠），剩 0 站但没到不写「即将进站」
     */
    fun shortCriticalTextFor(state: TrackingSessionState): String {
        val isArrived = isArrivedState(state)
        return when (state.legPhase) {
            LegPhase.WAITING_BOARD -> when {
                state.stopsRemaining < 0 -> "等车中"
                isArrived -> "到站了"
                state.stopsRemaining == 0 -> "下一站到"
                else -> {
                    val withLine = "${state.lineName} ${state.stopsRemaining}站"
                    if (withLine.length <= MAX_SHORT_TEXT_LENGTH) withLine else "等车${state.stopsRemaining}站"
                }
            }
            LegPhase.ON_BOARD -> when {
                isArrived && !isLastLeg(state) -> "换乘"
                isArrived -> "已到站"
                state.stopsRemaining == 0 -> "准备下车"
                state.stopsRemaining > 0 -> "${state.stopsRemaining}站下车"
                else -> "乘车中"
            }
            LegPhase.SINGLE -> {
                val isMultiLeg = state.totalLegs > 1
                when {
                    isArrived && isMultiLeg && !isLastLeg(state) -> "换乘中"
                    isArrived -> "到站了"
                    state.stopsRemaining == 0 -> "下一站到"
                    state.stopsRemaining > 0 -> "剩${state.stopsRemaining}站"
                    else -> "追踪中"
                }
            }
        }.take(MAX_SHORT_TEXT_LENGTH)
    }

    /**
     * 按阶段生成通知文字。
     * @param nextLineName 下一程线路名（乘车阶段写"下车后换乘 X"；最后一程传 null）
     */
    fun buildTexts(state: TrackingSessionState, nextLineName: String? = null): NotificationTexts {
        val shortText = shortCriticalTextFor(state)
        val isArrived = isArrivedState(state)
        val legLabel = "第 ${state.currentLegIndex + 1}/${state.totalLegs.coerceAtLeast(1)} 程"
        val boardName = state.boardStopName.ifBlank { "上车站" }
        val dropName = state.targetStopName
        val eta = etaMinutesText(state.etaSeconds)

        return when (state.legPhase) {
            LegPhase.WAITING_BOARD -> {
                // 大字进标题、小字进正文，和线路页、追踪卡同一套 ArrivalText
                val arrival = ArrivalText.stateForSession(state)
                val display = ArrivalText.format(arrival)
                val title = when {
                    arrival is ArrivalState.Loading -> "${state.lineName} 等车 · $boardName"
                    arrival is ArrivalState.Arriving -> "${state.lineName} 到站了 · $boardName"
                    arrival is ArrivalState.OnTheWay && arrival.minutes != null ->
                        "${state.lineName} ${display.headline}到 $boardName"
                    state.stopsRemaining == 0 -> "${state.lineName} 下一站就到 $boardName"
                    else -> "${state.lineName} 还有 ${state.stopsRemaining} 站到 $boardName"
                }
                val content = when (arrival) {
                    ArrivalState.Loading -> state.statusSummary
                    is ArrivalState.Arriving -> "快去上车，上车后点「我已上车」"
                    else -> display.detail.ifBlank { "等车中" }
                }
                // 等车区间起点站名拿不到，只写目标站（已在标题中）
                NotificationTexts(title, content, "$legLabel · 等车", shortText)
            }

            LegPhase.ON_BOARD -> {
                val title = when {
                    isArrived -> "到 $dropName 了，请下车"
                    state.stopsRemaining == 0 -> "下一站 $dropName，准备下车"
                    else -> "还有 ${state.stopsRemaining} 站在 $dropName 下车"
                }
                val transfer = nextLineName?.takeIf { !isLastLeg(state) && it.isNotBlank() }?.let { "下车后换乘 $it" }
                val range = state.boardStopName.takeIf { it.isNotBlank() }?.let { "$it → $dropName" }
                val content = if (state.isSignalLost) {
                    listOfNotNull("信号丢失，显示的是上次位置", transfer).joinToString(" · ")
                } else {
                    // ProgressStyle 没有展开大文本，区间两头站名放在内容末尾
                    listOfNotNull(eta, transfer, range).joinToString(" · ").ifEmpty { "乘车中" }
                }
                NotificationTexts(title, content, "$legLabel · 乘车", shortText)
            }

            LegPhase.SINGLE -> buildSingleTexts(state, shortText, isArrived)
        }
    }

    /** 单线路（非分组）追踪：保持原有文案 */
    private fun buildSingleTexts(state: TrackingSessionState, shortText: String, isArrived: Boolean): NotificationTexts {
        val isMultiLeg = state.totalLegs > 1
        val currentLegNum = state.currentLegIndex + 1
        val title = if (isMultiLeg) {
            val destination = state.endStopName.ifEmpty { state.targetStopName }
            "[$currentLegNum/${state.totalLegs}程] ${state.lineName} 开往 $destination"
        } else if (state.endStopName.isNotEmpty()) {
            "${state.lineName} 开往 ${state.endStopName}"
        } else {
            "${state.lineName} 实时在途追踪"
        }

        // 到站文字走 ArrivalText：「科技园：8 分钟 · 还有 4 站」「科技园：到站了，快去上车」
        val display = ArrivalText.format(ArrivalText.stateForSession(state))
        val minutesPart = ArrivalText.minutesText(state.etaSeconds)?.let { "（$it）" }.orEmpty()
        val contentText = when {
            isArrived && isMultiLeg && currentLegNum < state.totalLegs ->
                "到达换乘站 ${state.targetStopName}，自动切换下一程"
            isArrived ->
                "${state.targetStopName}：到站了，快去上车"
            state.stopsRemaining == 0 && isMultiLeg ->
                "下一站就到换乘站 ${state.targetStopName}$minutesPart"
            state.stopsRemaining > 0 && isMultiLeg ->
                "[$currentLegNum/${state.totalLegs}程 换乘] 距换乘站 ${state.targetStopName} 还有 ${state.stopsRemaining} 站$minutesPart"
            state.stopsRemaining >= 0 ->
                "${state.targetStopName}：${display.short}"
            else ->
                "${state.targetStopName}：${state.statusSummary}"
        }

        val subText = if (isMultiLeg) {
            "换乘追踪 · 第$currentLegNum/${state.totalLegs}程"
        } else {
            "候车追踪 · 第${state.targetOrder}站"
        }

        return NotificationTexts(title, contentText, subText, shortText)
    }

    /** 按阶段决定通知按钮（最多 3 个） */
    fun notificationActions(state: TrackingSessionState): List<NotificationAction> {
        val cycle = NotificationAction(
            "提前${state.alertThresholdStops}站提醒",
            TrackingActionReceiver.ACTION_CYCLE_THRESHOLD,
            201
        )
        val stop = NotificationAction("结束", TrackingActionReceiver.ACTION_STOP_TRACKING, 202)
        return when (state.legPhase) {
            LegPhase.WAITING_BOARD -> listOf(
                NotificationAction("我已上车", TrackingActionReceiver.ACTION_CONFIRM_BOARDED, 203),
                cycle,
                stop
            )
            LegPhase.ON_BOARD -> buildList {
                if (!isLastLeg(state)) {
                    add(NotificationAction("跳到下一程", TrackingActionReceiver.ACTION_NEXT_LEG, 204))
                }
                add(stop)
            }
            LegPhase.SINGLE -> listOf(
                cycle.copy(label = "提前${state.alertThresholdStops}站提醒(切换)"),
                stop.copy(label = "结束追踪")
            )
        }
    }

    /**
     * 一起等的通知文字（第 15 步，R6：只写前 2 条）。
     * - 等车：标题写最先到的那辆（「325 4 分钟到 科技园」），正文写它的小字和第 2 条线（「还有 2 站 · M194 9 分钟」）
     * - 「上了哪辆？」：标题写哪几条线都在站
     * - 乘车：没有下车站，写还有几站到终点
     */
    fun buildWatchTexts(state: TrackingSessionState, watch: WatchNotice): NotificationTexts {
        val shortText = shortCriticalTextFor(state)
        val stopName = state.boardStopName.ifBlank { state.targetStopName }.ifBlank { "上车站" }
        if (state.legPhase == LegPhase.ON_BOARD) {
            val title = "已上 ${state.lineName}"
            val content = when {
                state.isSignalLost -> "信号丢失，显示的是上次位置"
                state.lockedBusId == null || state.stopsRemaining < 0 -> state.statusSummary
                isArrivedState(state) -> "到终点站 ${state.targetStopName} 了"
                else -> listOfNotNull(
                    "还有 ${state.stopsRemaining} 站到终点 ${state.targetStopName}",
                    etaMinutesText(state.etaSeconds)
                ).joinToString(" · ")
            }
            return NotificationTexts(title, content, "一起等 · 乘车", shortText)
        }
        val subText = "一起等 ${watch.lines.size.coerceAtLeast(1)} 条"
        val choice = watch.boardChoice
        if (choice != null) {
            val names = choice.options.joinToString("、") { it.lineName }
            return NotificationTexts("$names 都在 $stopName", "上了哪辆？打开追客选一下", subText, "上了哪辆")
        }
        val primaryTexts = buildTexts(state)
        val title = primaryTexts.title
        val primaryDetail = when (ArrivalText.stateForSession(state)) {
            ArrivalState.Loading -> state.statusSummary
            is ArrivalState.Arriving -> "快去上车，上车后点「我上车了」"
            else -> primaryTexts.content
        }
        val second = watch.lines.drop(1).firstOrNull()?.let(::watchLineShort)
        return NotificationTexts(title, listOfNotNull(primaryDetail, second).joinToString(" · "), subText, shortText)
    }

    /** 一起等列表里一条线的短文字：「M194 9 分钟」「M194 3 站」「M194 到站了」「M194 暂无来车」 */
    internal fun watchLineShort(line: WatchedLine): String {
        val name = line.line.lineName
        val minutes = ArrivalText.minutesOf(line.etaSeconds)
        return when {
            line.busId == null -> if (line.hasData) "$name 暂无来车" else "$name 暂无数据"
            line.isArrived -> "$name 到站了"
            minutes != null -> "$name $minutes 分钟"
            line.stopsRemaining >= 0 -> "$name ${line.stopsRemaining} 站"
            else -> name
        }
    }

    /** 一起等的通知按钮：等车 = 我上车了 / 乘车码 / 结束；乘车 = 乘车码 / 结束 */
    fun watchNotificationActions(state: TrackingSessionState): List<NotificationAction> {
        val transitCode = NotificationAction("乘车码", ACTION_OPEN_TRANSIT_CODE, 205, opensTransitCode = true)
        val stop = NotificationAction("结束", TrackingActionReceiver.ACTION_STOP_TRACKING, 202)
        return when (state.legPhase) {
            LegPhase.WAITING_BOARD -> listOf(
                NotificationAction("我上车了", TrackingActionReceiver.ACTION_CONFIRM_BOARDED, 203),
                transitCode,
                stop
            )
            else -> listOf(transitCode, stop)
        }
    }

    /**
     * 「乘车码」按钮：打开 App 的乘车码入口（`trackr://transit-code?provider=`，和桌面快捷方式一样，§3.6）。
     * 默认平台选了「每次询问」或读不到时，装了支付宝用支付宝，否则云闪付。
     */
    private fun transitCodePendingIntent(context: Context, requestCode: Int): PendingIntent {
        val provider = when (runCatching { transitCodeProviderSetting() }.getOrDefault(TransitCodeProvider.ASK_ALWAYS)) {
            TransitCodeProvider.ALIPAY -> TransitCodeProvider.ALIPAY
            TransitCodeProvider.UNIONPAY -> TransitCodeProvider.UNIONPAY
            TransitCodeProvider.ASK_ALWAYS ->
                if (TransitCodeHelper.isAlipayInstalled(context)) TransitCodeProvider.ALIPAY else TransitCodeProvider.UNIONPAY
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinkUris.transitCode(provider)))
            .setClassName(context.packageName, "com.trackr.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 点通知本身：有「上了哪辆？」待选发 `trackr://tracking/board?session=`（§3.6），否则只拉起 App（页面不变） */
    internal fun contentIntent(context: Context, watch: WatchNotice?): Intent {
        val sessionId = watch?.sessionId.orEmpty()
        if (watch?.boardChoice != null && sessionId.isNotBlank()) {
            return Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinkUris.chooseBoardedBus(sessionId)))
                .setClassName(context.packageName, "com.trackr.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "com.trackr.MainActivity")
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return launchIntent
    }

    private fun receiverPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        sessionId: String = "",
        watchLineId: String = ""
    ): PendingIntent {
        val intent = Intent(context, TrackingActionReceiver::class.java).apply {
            this.action = action
            if (sessionId.isNotBlank()) putExtra(TrackingActionReceiver.EXTRA_SESSION_ID, sessionId)
            if (watchLineId.isNotBlank()) putExtra(TrackingActionReceiver.EXTRA_WATCH_LINE_ID, watchLineId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeOf(requestCode, sessionId, watchLineId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 同一份会话的 PendingIntent 请求码。同时追多项时每项一条通知，请求码相同的话
     * （PendingIntent 的身份不含 extras）后一条会把前一条的 sessionId 覆盖掉，按钮就都指向同一项了。
     */
    private fun requestCodeOf(base: Int, sessionId: String, watchLineId: String = ""): Int {
        val key = if (watchLineId.isEmpty()) sessionId else "$sessionId#$watchLineId"
        return if (key.isEmpty()) base else base * 4096 + (key.hashCode() and 0xFFF)
    }

    /**
     * 把 `hasPromotableCharacteristics()` 的每一项单独打出来。
     * 只看总结果 `promotable=false` 定位不了是哪一项不满足（issue #27 就卡在这），
     * 逐项打出来后，用户贴一行日志就能知道差什么。
     */
    @Suppress("DEPRECATION")
    private fun logPromotionState(context: Context, notification: Notification) {
        try {
            val canPost = LiveUpdatePromotion.canPromote(context)
            val promotable = isPromotable(notification)
            val extras = notification.extras
            val hasCustomViews = notification.contentView != null ||
                notification.bigContentView != null ||
                notification.headsUpContentView != null
            Log.i(
                TAG,
                "promotion canPost=$canPost promotable=$promotable " +
                        "requested=${NotificationCompat.isRequestPromotedOngoing(notification)} " +
                        // AOSP 要求染色（Android 16 及主线），官方文档要求不染色，两边都能被这一项验证
                        "colorized=${extras?.getBoolean(NotificationCompat.EXTRA_COLORIZED) == true} " +
                        "ongoing=${notification.flags and Notification.FLAG_ONGOING_EVENT != 0} " +
                        "title=${!extras?.getCharSequence(NotificationCompat.EXTRA_TITLE).isNullOrBlank()} " +
                        "groupSummary=${notification.flags and Notification.FLAG_GROUP_SUMMARY != 0} " +
                        "customViews=$hasCustomViews " +
                        "template=${extras?.getString(NotificationCompat.EXTRA_TEMPLATE)} " +
                        "importance=${channelImportance(context)} " +
                        "fluidCloud=${OppoFluidCloudAdapter.isColorOs()}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect promotion state", e)
        }
    }

    /** 通知渠道重要级别；IMPORTANCE_MIN 会让通知失去上岛资格（文档明列的限制之一） */
    private fun channelImportance(context: Context): Int? =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.getNotificationChannel(CHANNEL_ID_LIVE_PROGRESS)
            ?.importance

    /**
     * 刷新一项追踪的常驻通知（一份会话一条）。
     * @param notificationId 这条通知的 id：一份会话固定一个（见 [notificationIdOf]），由 sessionId 决定
     */
    fun updateNotification(
        context: Context,
        state: TrackingSessionState,
        watch: WatchNotice? = null,
        sessionId: String = "",
        allowPromotion: Boolean = true,
        nextLineName: String? = null,
        notificationId: Int = NOTIFICATION_ID_LIVE_PROGRESS,
        watchLineId: String = ""
    ) {
        if (!state.isActive) {
            cancelNotification(context, notificationId)
            return
        }
        val notification = buildNotification(
            context = context,
            state = state,
            nextLineName = nextLineName,
            watch = watch,
            sessionId = sessionId,
            allowPromotion = allowPromotion,
            watchLineId = watchLineId
        )
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot update notification: missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update live progress notification", e)
        }
    }

    /** 取消一条常驻通知（默认那条：没有会话时进前台用的占位通知） */
    fun cancelNotification(context: Context, notificationId: Int = NOTIFICATION_ID_LIVE_PROGRESS) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel live progress notification", e)
        }
    }

    /**
     * 一份会话的常驻通知 id：由 sessionId 决定，**同一项追踪从开始到结束都用同一个 id**。
     *
     * 不按列表位置分：前面一项结束时后面那项要挪 id（撤一条再发一条），漏撤一次就会在通知栏留下
     * 一条点不动的常驻通知。按 sessionId 分之后，结束哪一项就精确撤哪一条，位置怎么变都不用挪。
     */
    fun notificationIdOf(sessionId: String): Int = notificationIdOfSession(sessionId)

    companion object {
        private const val TAG = "LiveProgressNotif"

        const val CHANNEL_ID_LIVE_PROGRESS = "bus_live_progress"
        private const val CHANNEL_NAME_LIVE_PROGRESS = "实时在途追踪与进度"

        const val NOTIFICATION_ID_LIVE_PROGRESS = 10001

        /** 同时追多项时每项一条的通知 id 起点（10001 留给没有 sessionId 的旧调用） */
        const val NOTIFICATION_ID_FIRST_PER_SESSION = 11000

        /**
         * 一份会话的常驻通知 id。广播接收器也要能算出同一个值（用户在通知栏点「结束」时精确撤掉
         * 那一条），所以是纯函数、只跟 sessionId 有关。
         */
        fun notificationIdOfSession(sessionId: String): Int =
            if (sessionId.isEmpty()) {
                NOTIFICATION_ID_LIVE_PROGRESS
            } else {
                NOTIFICATION_ID_FIRST_PER_SESSION + (sessionId.hashCode() and 0xFFFFF)
            }

        /**
         * 撤掉某一项追踪的常驻通知。会话已经不在仓库里时（进程刚被拉起 / 已经结束过）也照样能撤，
         * 免得留一条怎么点都没反应的常驻通知。
         */
        fun cancelSessionNotification(context: Context, sessionId: String) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(notificationIdOfSession(sessionId))
            }
        }

        /**
         * 一起等里一条线的通知标识：一条线一条常驻通知，各自有进度和「结束」，
         * 所以通知 id 要按「会话 + 线」分，不能只按会话。
         */
        fun watchLineKey(sessionId: String, lineId: String): String = "$sessionId#$lineId"

        /** 撤掉一起等里某条线的通知（那条线被去掉 / 结束后） */
        fun cancelWatchLineNotification(context: Context, sessionId: String, lineId: String) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(notificationIdOfSession(watchLineKey(sessionId, lineId)))
            }
        }

        /** 点通知本身进 App 的 PendingIntent 请求码（多份会话时再加 sessionId 的散列） */
        private const val CONTENT_REQUEST_CODE = 200

        /** Android 16 胶囊短文本的建议长度上限 */
        private const val MAX_SHORT_TEXT_LENGTH = 7

        private val COLOR_COMPLETED = Color.parseColor("#FF1B8755") // 已行驶绿色段
        private val COLOR_REMAINING = Color.parseColor("#FF3B608C") // 剩余蓝色段

        /** 读取失败时的兜底值，与设置项默认值（开启）一致；常驻通知和强提醒共用 */
        internal const val LIVE_NOTIFICATION_ENABLED_FALLBACK = true

        /** 锁屏开关关掉时锁屏上的标题（D3） */
        internal const val LOCK_SCREEN_PUBLIC_TITLE = "追客 · 追踪中"

        /**
         * 「乘车码」按钮在 [NotificationAction] 里的标识。第 18 步起按钮发的是 `trackr://transit-code` 链接，
         * 这个 action 只剩旧版兼容（已发出的旧通知，`DeepLinkIntents` 还认它）。
         */
        const val ACTION_OPEN_TRANSIT_CODE = "com.github.u5151561.trackr.action.OPEN_TRANSIT_CODE"
    }
}

