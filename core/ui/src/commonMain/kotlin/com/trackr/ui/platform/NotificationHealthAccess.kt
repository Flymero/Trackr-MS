package com.trackr.ui.platform

/**
 * “我的”页的通知可靠性体检。
 *
 * Android 只报告系统公开 API 能确认的状态；其他平台不绑定此接口，页面不显示该行。
 */
interface NotificationHealthAccess {
    fun status(): NotificationHealth
    fun openSettings(issue: NotificationHealthIssue)
}

enum class NotificationHealthIssue {
    NOTIFICATIONS,
    ALERT_CHANNEL,
    LIVE_CHANNEL
}

data class NotificationHealth(
    val notificationsEnabled: Boolean = true,
    val alertChannelAudible: Boolean = true,
    val liveChannelEnabled: Boolean = true
) {
    val issue: NotificationHealthIssue?
        get() = when {
            !notificationsEnabled -> NotificationHealthIssue.NOTIFICATIONS
            !alertChannelAudible -> NotificationHealthIssue.ALERT_CHANNEL
            !liveChannelEnabled -> NotificationHealthIssue.LIVE_CHANNEL
            else -> null
        }

    val healthy: Boolean get() = issue == null
}
