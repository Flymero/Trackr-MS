package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.EngineMode
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.RailMode
import com.trackr.core.model.UiMode
import com.trackr.core.model.isMeishanLocal
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.ui.platform.LiveUpdateAccess
import com.trackr.ui.platform.LiveUpdateStatus
import com.trackr.ui.platform.NotificationHealth
import com.trackr.ui.platform.NotificationHealthAccess
import com.trackr.ui.platform.NotificationHealthIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的」页状态（v3 分组：出行、通知、地图、关于）。
 *
 * @param alertThresholdStops 到站前提醒：车离上车站还有几站时提醒（1–3）
 * @param remindersSubtitle 「定时提醒」那一行的副标题（最早一条开着的提醒）；没有提醒为 null
 * @param liveUpdate 状态栏胶囊的系统状态；平台没有这项（iOS）或实时通知关着时为 null，不显示那一行
 * @param amapKeyStatus 「高德 Key」那一行的状态文字
 * @param versionName 「关于」那一行显示的版本号；读不到为 null
 * @param elderMode 长辈版开关（`SettingsRepository.uiMode` 是不是 Elder）
 */
data class MeUiState(
    val engineMode: EngineMode = EngineMode.MYGOLBS,
    val preferredTransitCode: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS,
    val alertThresholdStops: Int = 2,
    val reminders: List<ScheduledReminder> = emptyList(),
    val remindersSubtitle: String? = null,
    val isLiveNotificationEnabled: Boolean = true,
    val showOnLockScreen: Boolean = true,
    val liveUpdate: LiveUpdateStatus? = null,
    val notificationHealth: NotificationHealth? = null,
    val amapKeyStatus: String = amapKeyStatusText(hasMapKey = false, canShowMap = false),
    val versionName: String? = null,
    val elderMode: Boolean = false,
    val railMode: RailMode = RailMode.Horizontal,
    val userMessages: List<UserMessage> = emptyList(),
    /** 眉山特化：当前城市属于眉山官方源服务范围（眉山 / 仁寿）时，数据源弹窗才提供「官方」 */
    val isMeishanCity: Boolean = false
)

/** 「我的」页的事件。`Open*` 由 Route 处理（导航），其余交给 [MeViewModel.onEvent] */
sealed interface MeEvent {
    data class SetEngineMode(val mode: EngineMode) : MeEvent
    data class SetTransitCode(val provider: TransitCodeProvider) : MeEvent
    data class SetAlertThresholdStops(val stops: Int) : MeEvent
    data class ToggleReminder(val id: String, val enabled: Boolean) : MeEvent
    data class SetLiveNotificationEnabled(val enabled: Boolean) : MeEvent
    data class SetShowOnLockScreen(val enabled: Boolean) : MeEvent
    data object OpenLiveUpdateSettings : MeEvent
    data class OpenNotificationSettings(val issue: NotificationHealthIssue) : MeEvent
    data class SetElderMode(val enabled: Boolean) : MeEvent
    data class SetRailMode(val mode: RailMode) : MeEvent
    data class MessageShown(val id: Long) : MeEvent

    data class OpenReminder(val id: String) : MeEvent
    data object OpenAmapKey : MeEvent
    data object OpenAbout : MeEvent
}

/** 到站前提醒能选的站数（和通知上「切换」按钮的 1 → 2 → 3 一致） */
val ALERT_THRESHOLD_CHOICES: List<Int> = listOf(1, 2, 3)

class MeViewModel(
    private val settings: SettingsRepository,
    private val reminderRepository: IScheduledReminderRepository,
    private val amapKeys: AmapKeyRepository,
    private val notices: MeNotices,
    private val liveUpdateAccess: LiveUpdateAccess? = null,
    private val versionName: String? = null,
    private val notificationHealthAccess: NotificationHealthAccess? = null
) : ViewModel() {

    private val liveUpdateStatus = MutableStateFlow(liveUpdateAccess?.status())
    private val notificationHealthStatus = MutableStateFlow(notificationHealthAccess?.status())

    private val settingsPart = combine(
        settings.engineMode,
        settings.preferredTransitCodeProvider,
        settings.alertThresholdStops,
        settings.isLiveNotificationEnabled,
        settings.showOnLockScreen
    ) { engine, code, stops, live, lock -> SettingsPart(engine, code, stops, live, lock) }
        .combine(settings.uiMode) { part, mode -> part.copy(elderMode = mode == UiMode.Elder) }
        .combine(settings.railMode) { part, mode -> part.copy(railMode = mode) }
        // 眉山特化：官方数据源选项只在眉山 / 仁寿提供
        .combine(settings.selectedCity) { part, city -> part.copy(meishanCity = city.isMeishanLocal) }

    private val mapKeyStatus = combine(amapKeys.hasMapKey, amapKeys.canShowMap) { has, canShow ->
        amapKeyStatusText(has, canShow)
    }

    private val platformStatus = combine(liveUpdateStatus, notificationHealthStatus) { live, health ->
        live to health
    }

    val uiState: StateFlow<MeUiState> = combine(
        settingsPart,
        reminderRepository.reminders,
        platformStatus,
        mapKeyStatus,
        notices.messages
    ) { part, reminders, platform, keyStatus, messages ->
        buildState(part, reminders, platform.first, platform.second, keyStatus, messages)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = buildState(
            SettingsPart(
                settings.engineMode.value,
                settings.preferredTransitCodeProvider.value,
                settings.alertThresholdStops.value,
                settings.isLiveNotificationEnabled.value,
                settings.showOnLockScreen.value,
                settings.uiMode.value == UiMode.Elder,
                settings.railMode.value
            ),
            reminderRepository.reminders.value,
            liveUpdateStatus.value,
            notificationHealthStatus.value,
            amapKeyStatusText(amapKeys.hasMapKey.value, amapKeys.canShowMap.value),
            notices.messages.value
        )
    )

    fun onEvent(event: MeEvent) {
        when (event) {
            is MeEvent.SetEngineMode -> settings.setEngineMode(event.mode)
            is MeEvent.SetTransitCode -> viewModelScope.launch {
                settings.setPreferredTransitCodeProvider(event.provider)
            }
            is MeEvent.SetAlertThresholdStops -> settings.setAlertThresholdStops(event.stops)
            is MeEvent.ToggleReminder -> viewModelScope.launch {
                reminderRepository.toggleReminder(event.id, event.enabled)
            }
            is MeEvent.SetLiveNotificationEnabled -> settings.setLiveNotificationEnabled(event.enabled)
            is MeEvent.SetShowOnLockScreen -> settings.setShowOnLockScreen(event.enabled)
            MeEvent.OpenLiveUpdateSettings -> liveUpdateAccess?.openSettings()
            is MeEvent.OpenNotificationSettings -> notificationHealthAccess?.openSettings(event.issue)
            // 第 30 步只换主题；长辈版导航和页面在第 31–34 步
            is MeEvent.SetElderMode -> settings.setUiMode(if (event.enabled) UiMode.Elder else UiMode.Standard)
            is MeEvent.SetRailMode -> settings.setRailMode(event.mode)
            is MeEvent.MessageShown -> notices.consume(event.id)
            // 导航事件由 Route 处理
            is MeEvent.OpenReminder, MeEvent.OpenAmapKey, MeEvent.OpenAbout -> Unit
        }
    }

    /** 回到前台时重查状态栏胶囊权限（用户可能刚从系统设置回来） */
    fun onScreenResumed() {
        liveUpdateStatus.value = liveUpdateAccess?.status()
        notificationHealthStatus.value = notificationHealthAccess?.status()
    }

    private fun buildState(
        part: SettingsPart,
        reminders: List<ScheduledReminder>,
        liveUpdate: LiveUpdateStatus?,
        notificationHealth: NotificationHealth?,
        keyStatus: String,
        messages: List<UserMessage>
    ) = MeUiState(
        engineMode = part.engineMode,
        preferredTransitCode = part.transitCode,
        alertThresholdStops = part.alertThresholdStops,
        reminders = reminders,
        remindersSubtitle = remindersRowSubtitle(reminders),
        isLiveNotificationEnabled = part.liveNotification,
        showOnLockScreen = part.showOnLockScreen,
        elderMode = part.elderMode,
        railMode = part.railMode,
        isMeishanCity = part.meishanCity,
        // 和原来一样：实时通知关着时不显示胶囊权限
        liveUpdate = liveUpdate?.takeIf { part.liveNotification },
        notificationHealth = notificationHealth?.takeIf { !it.healthy },
        amapKeyStatus = keyStatus,
        versionName = versionName,
        userMessages = messages
    )

    private data class SettingsPart(
        val engineMode: EngineMode,
        val transitCode: TransitCodeProvider,
        val alertThresholdStops: Int,
        val liveNotification: Boolean,
        val showOnLockScreen: Boolean,
        val elderMode: Boolean = false,
        val railMode: RailMode = RailMode.Horizontal,
        val meishanCity: Boolean = false
    )
}
