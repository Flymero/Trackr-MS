package com.trackr.ui.screens.me

import com.trackr.core.model.EngineMode
import com.trackr.core.model.RailMode
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitCity
import com.trackr.core.model.UiMode
import com.trackr.core.testing.FakeAmapKeyRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.MainDispatcherHolder
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.ui.platform.LiveUpdateAccess
import com.trackr.ui.platform.LiveUpdateStatus
import com.trackr.ui.platform.NotificationHealth
import com.trackr.ui.platform.NotificationHealthAccess
import com.trackr.ui.platform.NotificationHealthIssue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeViewModelTest {

    private val main = MainDispatcherHolder()

    private lateinit var settings: FakeSettingsRepository
    private lateinit var reminders: FakeScheduledReminderRepository
    private lateinit var amapKeys: FakeAmapKeyRepository
    private lateinit var notices: MeNotices
    private lateinit var liveUpdate: FakeLiveUpdateAccess
    private lateinit var notificationHealth: FakeNotificationHealthAccess

    @BeforeTest
    fun setup() {
        main.install()
        settings = FakeSettingsRepository()
        reminders = FakeScheduledReminderRepository()
        amapKeys = FakeAmapKeyRepository()
        notices = MeNotices()
        liveUpdate = FakeLiveUpdateAccess()
        notificationHealth = FakeNotificationHealthAccess()
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    private fun createViewModel(
        liveUpdateAccess: LiveUpdateAccess? = liveUpdate,
        versionName: String? = "0.0.3",
        notificationHealthAccess: NotificationHealthAccess? = notificationHealth
    ) = MeViewModel(
        settings = settings,
        reminderRepository = reminders,
        amapKeys = amapKeys,
        notices = notices,
        liveUpdateAccess = liveUpdateAccess,
        versionName = versionName,
        notificationHealthAccess = notificationHealthAccess
    )

    @Test
    fun initialState_reflectsRepositories() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertEquals(EngineMode.CHELAILE, state.engineMode)
        assertEquals(TransitCodeProvider.ASK_ALWAYS, state.preferredTransitCode)
        assertEquals(2, state.alertThresholdStops)
        assertTrue(state.reminders.isEmpty())
        assertNull(state.remindersSubtitle)
        assertTrue(state.isLiveNotificationEnabled)
        assertTrue(state.showOnLockScreen)
        assertEquals("没填，不显示「路线」页", state.amapKeyStatus)
        assertEquals("0.0.3", state.versionName)
        // 没存过界面模式 = 标准版，开关关着
        assertFalse(state.elderMode)
    }

    @Test
    fun elderSwitch_writesUiMode_andStateFollows() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.onEvent(MeEvent.SetElderMode(true))
        testScheduler.advanceUntilIdle()
        assertEquals(UiMode.Elder, settings.uiMode.value)
        assertTrue(viewModel.uiState.value.elderMode)

        viewModel.onEvent(MeEvent.SetElderMode(false))
        testScheduler.advanceUntilIdle()
        assertEquals(UiMode.Standard, settings.uiMode.value)
        assertFalse(viewModel.uiState.value.elderMode)
    }

    @Test
    fun railMode_defaultsToHorizontal_andFollowsSetting() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        assertEquals(RailMode.Horizontal, viewModel.uiState.value.railMode)

        viewModel.onEvent(MeEvent.SetRailMode(RailMode.Vertical))
        testScheduler.advanceUntilIdle()
        assertEquals(RailMode.Vertical, settings.railMode.value)
        assertEquals(RailMode.Vertical, viewModel.uiState.value.railMode)

        viewModel.onEvent(MeEvent.SetRailMode(RailMode.Horizontal))
        testScheduler.advanceUntilIdle()
        assertEquals(RailMode.Horizontal, viewModel.uiState.value.railMode)
    }

    @Test
    fun elderMode_setElsewhere_showsOn() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        // 比如字号提示里点了「打开长辈版」
        settings.setUiMode(UiMode.Elder)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.elderMode)
    }

    @Test
    fun dataSource_isSingleChoice_andStateFollows() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        // D1：数据源单源；眉山特化多一个「官方」（只在眉山 / 仁寿的弹窗里出现）
        assertEquals(
            listOf(EngineMode.CHELAILE, EngineMode.MYGOLBS, EngineMode.MEISHAN),
            EngineMode.entries.toList()
        )

        viewModel.onEvent(MeEvent.SetEngineMode(EngineMode.MYGOLBS))
        testScheduler.advanceUntilIdle()
        assertEquals(EngineMode.MYGOLBS, settings.engineMode.value)
        assertEquals(EngineMode.MYGOLBS, viewModel.uiState.value.engineMode)

        viewModel.onEvent(MeEvent.SetEngineMode(EngineMode.CHELAILE))
        testScheduler.advanceUntilIdle()
        assertEquals(EngineMode.CHELAILE, viewModel.uiState.value.engineMode)
    }

    @Test
    fun dataSource_meishanOptionVisibleOnlyForMeishanCities() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        // FakeSettingsRepository 默认上海：没有官方选项
        assertFalse(viewModel.uiState.value.isMeishanCity)

        settings.selectCity(TransitCity(cityId = "", cityName = "眉山", province = "四川"))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isMeishanCity)

        settings.selectCity(TransitCity(cityId = "", cityName = "仁寿", province = "四川"))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isMeishanCity)

        settings.selectCity(TransitCity(cityId = "034", cityName = "上海", province = "直辖市"))
        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isMeishanCity)
    }

    @Test
    fun transitCode_updatesRepository() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.onEvent(MeEvent.SetTransitCode(TransitCodeProvider.ALIPAY))
        testScheduler.advanceUntilIdle()
        assertEquals(TransitCodeProvider.ALIPAY, settings.preferredTransitCodeProvider.value)
        assertEquals(TransitCodeProvider.ALIPAY, viewModel.uiState.value.preferredTransitCode)

        viewModel.onEvent(MeEvent.SetTransitCode(TransitCodeProvider.UNIONPAY))
        testScheduler.advanceUntilIdle()
        assertEquals(TransitCodeProvider.UNIONPAY, viewModel.uiState.value.preferredTransitCode)
    }

    @Test
    fun alertThreshold_isGlobalSetting_choicesOneToThree() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        assertEquals(listOf(1, 2, 3), ALERT_THRESHOLD_CHOICES)

        viewModel.onEvent(MeEvent.SetAlertThresholdStops(3))
        testScheduler.advanceUntilIdle()
        assertEquals(3, settings.alertThresholdStops.value)
        assertEquals(3, viewModel.uiState.value.alertThresholdStops)

        viewModel.onEvent(MeEvent.SetAlertThresholdStops(1))
        testScheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.alertThresholdStops)
    }

    @Test
    fun reminders_rowShowsEarliestEnabled_andToggleWritesRepository() = runTest(main.dispatcher) {
        reminders.saveReminder(reminder("late", hour = 18, minute = 30, lineName = "20路"))
        reminders.saveReminder(reminder("early", hour = 7, minute = 50, lineName = "325"))
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.reminders.size)
        assertEquals("工作日 07:50 · 325", viewModel.uiState.value.remindersSubtitle)

        // 关掉早的那条：副标题换成下一条开着的
        viewModel.onEvent(MeEvent.ToggleReminder("early", false))
        testScheduler.advanceUntilIdle()
        assertFalse(reminders.getReminderById("early")!!.enabled)
        assertEquals("工作日 18:30 · 20路", viewModel.uiState.value.remindersSubtitle)

        viewModel.onEvent(MeEvent.ToggleReminder("late", false))
        testScheduler.advanceUntilIdle()
        assertEquals("2 个都关着", viewModel.uiState.value.remindersSubtitle)
    }

    @Test
    fun liveNotification_andLockScreen_toggle() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        viewModel.onEvent(MeEvent.SetShowOnLockScreen(false))
        testScheduler.advanceUntilIdle()
        assertFalse(settings.showOnLockScreen.value)
        assertFalse(viewModel.uiState.value.showOnLockScreen)

        viewModel.onEvent(MeEvent.SetLiveNotificationEnabled(false))
        testScheduler.advanceUntilIdle()
        assertFalse(settings.isLiveNotificationEnabled.value)
        assertFalse(viewModel.uiState.value.isLiveNotificationEnabled)

        viewModel.onEvent(MeEvent.SetLiveNotificationEnabled(true))
        viewModel.onEvent(MeEvent.SetShowOnLockScreen(true))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLiveNotificationEnabled)
        assertTrue(viewModel.uiState.value.showOnLockScreen)
    }

    @Test
    fun liveUpdateRow_hiddenWhenLiveNotificationOff_orNoPlatformSupport() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        assertEquals(LiveUpdateStatus(supported = true, allowed = false, systemName = "小米焦点通知"), viewModel.uiState.value.liveUpdate)

        // 和原来一样：实时通知关着时不显示胶囊权限
        viewModel.onEvent(MeEvent.SetLiveNotificationEnabled(false))
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.liveUpdate)

        // iOS：没有这项
        val ios = createViewModel(liveUpdateAccess = null)
        testScheduler.advanceUntilIdle()
        assertNull(ios.uiState.value.liveUpdate)
    }

    @Test
    fun liveUpdate_openSettings_andRecheckOnResume() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onEvent(MeEvent.OpenLiveUpdateSettings)
        assertEquals(1, liveUpdate.openCount)

        // 用户在系统设置里开了，回到前台重查
        liveUpdate.current = LiveUpdateStatus(supported = true, allowed = true, systemName = "小米焦点通知")
        viewModel.onScreenResumed()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.liveUpdate!!.allowed)
    }

    @Test
    fun notificationHealth_onlyShowsIssues_andRefreshesOnResume() = runTest(main.dispatcher) {
        notificationHealth.current = NotificationHealth()
        val viewModel = createViewModel()
        assertNull(viewModel.uiState.value.notificationHealth)

        notificationHealth.current = NotificationHealth(liveChannelEnabled = false)
        viewModel.onScreenResumed()
        testScheduler.advanceUntilIdle()
        assertEquals(NotificationHealthIssue.LIVE_CHANNEL, viewModel.uiState.value.notificationHealth?.issue)

        notificationHealth.current = NotificationHealth(notificationsEnabled = false, alertChannelAudible = false)
        viewModel.onScreenResumed()
        testScheduler.advanceUntilIdle()
        assertEquals(NotificationHealthIssue.NOTIFICATIONS, viewModel.uiState.value.notificationHealth?.issue)
    }

    @Test
    fun notificationHealth_openSettingsForwardsIssue() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        viewModel.onEvent(MeEvent.OpenNotificationSettings(NotificationHealthIssue.ALERT_CHANNEL))
        assertEquals(NotificationHealthIssue.ALERT_CHANNEL, notificationHealth.openedIssue)
    }

    @Test
    fun amapKeyStatus_followsRepository() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        amapKeys.setWebKey("w".repeat(32))
        testScheduler.advanceUntilIdle()
        assertEquals("只有 Web 服务 Key：能算路线，不显示地图", viewModel.uiState.value.amapKeyStatus)

        amapKeys.setAndroidKey("a".repeat(32))
        testScheduler.advanceUntilIdle()
        assertEquals("已填，路线页显示地图", viewModel.uiState.value.amapKeyStatus)
    }

    @Test
    fun noticesFromChildPages_showOnceThenConsumed() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        notices.post("提醒已保存：工作日 07:50 · 325")
        testScheduler.advanceUntilIdle()

        val message = viewModel.uiState.value.userMessages.single()
        assertEquals("提醒已保存：工作日 07:50 · 325", message.text)

        viewModel.onEvent(MeEvent.MessageShown(message.id))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.userMessages.isEmpty())
    }

    @Test
    fun reminderTexts_shortForms() {
        assertEquals("仅一次", shortRepeatText(emptySet()))
        assertEquals("工作日", shortRepeatText(setOf(1, 2, 3, 4, 5)))
        assertEquals("每天", shortRepeatText((1..7).toSet()))
        assertEquals("周末", shortRepeatText(setOf(6, 7)))
        assertEquals("周一、三", shortRepeatText(setOf(3, 1)))
        assertEquals("工作日 07:05 · 325", reminderSummary(reminder("a", hour = 7, minute = 5, lineName = "325")))
    }

    @Test
    fun amapKeyStatusText_coversThreeStates() {
        assertEquals("没填，不显示「路线」页", amapKeyStatusText(hasMapKey = false, canShowMap = false))
        assertEquals("只有 Web 服务 Key：能算路线，不显示地图", amapKeyStatusText(hasMapKey = true, canShowMap = false))
        assertEquals("已填，路线页显示地图", amapKeyStatusText(hasMapKey = true, canShowMap = true))
    }

    private fun reminder(id: String, hour: Int, minute: Int, lineName: String) = ScheduledReminder(
        id = id,
        hour = hour,
        minute = minute,
        cityId = "034",
        cityName = "上海",
        lineId = "line_$id",
        lineName = lineName,
        direction = 0,
        endStopName = "人民广场",
        targetStopId = "stop_05",
        targetStopName = "南京东路",
        targetOrder = 5
    )
}

/** 状态栏胶囊的 Fake：默认小米、没开权限 */
class FakeNotificationHealthAccess(
    var current: NotificationHealth = NotificationHealth()
) : NotificationHealthAccess {
    var openedIssue: NotificationHealthIssue? = null

    override fun status(): NotificationHealth = current

    override fun openSettings(issue: NotificationHealthIssue) {
        openedIssue = issue
    }
}

class FakeLiveUpdateAccess(
    var current: LiveUpdateStatus = LiveUpdateStatus(supported = true, allowed = false, systemName = "小米焦点通知")
) : LiveUpdateAccess {
    var openCount: Int = 0
        private set

    override fun status(): LiveUpdateStatus = current

    override fun openSettings() {
        openCount++
    }
}
