package com.trackr.service.vendor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSessionState
import com.trackr.service.LiveProgressNotifier
import org.json.JSONObject

/**
 * 小米（澎湃OS / MIUI）焦点通知、超级岛的端侧协议适配器。
 *
 * 注意这三件事不是一回事，别混在一起判断：
 * - **MIUI（含 13、14）没有焦点通知**，更没有超级岛，任何「去开一下」的引导对它都是死路；
 * - **焦点通知**从澎湃OS 1 开始有（OS1 / OS2），显示在状态栏和通知中心；
 * - **超级岛**是澎湃OS 3 的形态（OS3），复用同一套 `miui.focus.param` 数据接口。
 *
 * 所以判断顺序必须是：先查这个 ROM 有没有焦点通知（[focusProtocolVersion]），再看用户开没开
 * （[canShowFocus]）。只看后者会把「这台机器压根没这功能」当成「用户没开」，于是每次点追踪都弹一次引导。
 *
 * 这些版本差异只用在**能力判断**上；给用户看的文案统一叫「实时活动通知」，不按 ROM 换称呼
 * （小米叫焦点通知 / 超级岛、OPPO 叫流体云、原生叫实时活动，以后还会更多，文案跟不上）。
 */
object XiaomiFocusAdapter {

    const val EXTRA_KEY = "miui.focus.param"
    const val MAX_JSON_BYTES = 3072

    /** 小米官方查询接口用的 Settings.System 键，见澎湃OS 开发指南「五、查询接口」 */
    const val SETTINGS_KEY_FOCUS_PROTOCOL = "notification_focus_protocol"

    /** 与系统通知共用文案和进度算法（这几个方法不读设置、不取下一程，所以用一个不接仓库的实例就行） */
    private val notifierTexts = LiveProgressNotifier(liveNotificationSetting = { true }, nextLegLineNameOf = { null })

    /**
     * 判断当前设备是否为小米/Redmi 设备
     */
    fun isXiaomiRom(): Boolean {
        val brand = Build.BRAND.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        return brand.contains("xiaomi", ignoreCase = true) ||
                brand.contains("redmi", ignoreCase = true) ||
                manufacturer.contains("xiaomi", ignoreCase = true) ||
                manufacturer.contains("redmi", ignoreCase = true)
    }

    /**
     * 当前 ROM 的焦点通知协议版本（小米官方查询接口 `Settings.System.notification_focus_protocol`）：
     * 0 = 这个 ROM 没有焦点通知功能（MIUI 全系、非小米），1 = 澎湃OS 1，2 = 澎湃OS 2，3 = 澎湃OS 3（超级岛）。
     *
     * 读不到（旧 MIUI 没有这个键）就是 0，也就是「没这功能」，不该拿它当「用户没开」。
     */
    fun focusProtocolVersion(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, SETTINGS_KEY_FOCUS_PROTOCOL, 0)
    }.getOrDefault(0)

    /** 这台机器到底有没有焦点通知 / 超级岛（MIUI 全系为 false） */
    fun isFocusSupported(context: Context): Boolean {
        if (!isXiaomiRom()) return false
        // 极少数 ROM 不暴露协议版本这个键，那时权限查询说「已允许」也说明这功能确实在
        return isFocusSupported(isXiaomi = true, protocolVersion = focusProtocolVersion(context)) ||
            canShowFocus(context)
    }

    internal fun isFocusSupported(isXiaomi: Boolean, protocolVersion: Int): Boolean =
        isXiaomi && protocolVersion >= 1

    /**
     * 探测当前应用是否被授予焦点通知（超级岛）展示权限。
     *
     * 官方 `hasFocusPermission` 的语义：没有焦点通知功能的 ROM（MIUI、OS1 之前）恒为 false，
     * OS1 及以上则按用户开关给值。所以**只有 [isFocusSupported] 为 true 时**，false 才代表「用户关掉了」。
     */
    fun canShowFocus(context: Context): Boolean {
        if (!isXiaomiRom()) return true
        return try {
            val uri = Uri.parse("content://miui.statusbar.notification.public")
            val bundle = Bundle().apply { putString("package", context.packageName) }
            val result = context.contentResolver.call(uri, "canShowFocus", null, bundle)
            result?.getBoolean("canShowFocus", false) ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 打开系统应用通知设置页，便于用户开启“焦点通知 / 灵动通知”开关
     */
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, LiveProgressNotifier.CHANNEL_ID_LIVE_PROGRESS)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                // Ignore fallback failure
            }
        }
    }

    /**
     * 将符合小米 HyperOS 焦点通知规范的 JSON 注入到 NotificationCompat.Builder 的 extras 中
     */
    fun attachFocusParams(
        builder: NotificationCompat.Builder,
        state: TrackingSessionState,
        nextLineName: String? = null
    ) {
        val payload = buildJsonPayload(state, nextLineName)
        if (payload.isNotEmpty()) {
            builder.extras.putString(EXTRA_KEY, payload)
        }
    }

    /**
     * 结束一个已经展示在小米焦点通知 / 超级岛里的实时追踪。
     *
     * MIUI 的焦点通知和通知栏里的普通通知不是同一层。只调用
     * [NotificationManagerCompat.cancel] 会留下屏幕上的焦点通知，必须按小米协议
     * 再发送一次 `param_v2.cancel=true` 才能真正收起它。
     */
    fun cancelFocusNotification(context: Context, notificationId: Int) {
        if (!isXiaomiRom()) return
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            val cancellation = NotificationCompat.Builder(context, LiveProgressNotifier.CHANNEL_ID_LIVE_PROGRESS)
                .setSmallIcon(com.trackr.R.drawable.ic_stat_trackr)
                .setAutoCancel(true)
                .setOngoing(false)
                .apply { extras.putString(EXTRA_KEY, buildCancelPayload()) }
                .build()
            // 小米状态栏服务在 notify 时读取 cancel 参数；随后撤掉普通通知兜底。
            manager.notify(notificationId, cancellation)
            manager.cancel(notificationId)
        }
    }

    /** 小米焦点通知结束协议的最小 payload。 */
    fun buildCancelPayload(): String =
        JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("protocol", 1)
                put("cancel", true)
            })
        }.toString()

    /**
     * 构建紧凑的 param_v2 JSON 结构，并严格限制在 3072 字节以内
     */
    fun buildJsonPayload(state: TrackingSessionState, nextLineName: String? = null): String {
        val isArrived = ArrivalRules.isSessionArrived(state)

        val capsuleText: String
        val title: String
        val content: String
        val subTitle: String
        if (state.legPhase == LegPhase.SINGLE) {
            capsuleText = when {
                isArrived -> "已到站"
                state.stopsRemaining == 0 -> "即将进站"
                state.stopsRemaining > 0 -> "剩${state.stopsRemaining}站"
                else -> "追踪中"
            }

            title = if (state.endStopName.isNotEmpty()) {
                "${state.lineName} 开往 ${state.endStopName}"
            } else {
                "${state.lineName} 实时在途追踪"
            }

            val etaFormatted = RouteProjectionMath.formatEtaText(state.etaSeconds, state.isBusArrivedAtStop)
            content = when {
                isArrived -> "目标站 ${state.targetStopName}：车辆已到达！"
                state.stopsRemaining == 0 -> "目标站 ${state.targetStopName}：即将进站（$etaFormatted）"
                state.stopsRemaining > 0 -> "目标站 ${state.targetStopName}：剩 ${state.stopsRemaining} 站（$etaFormatted）"
                else -> "目标站 ${state.targetStopName}：${state.statusSummary}"
            }
            subTitle = "候车追踪 · 第${state.targetOrder}站"
        } else {
            // 分组换乘两阶段：与系统通知共用同一套文案
            val texts = notifierTexts.buildTexts(state, nextLineName)
            capsuleText = texts.shortCriticalText
            title = texts.title
            content = texts.content
            subTitle = texts.subText
        }

        val (rangeStart, rangeEnd) = notifierTexts.progressRangeOf(state)
        val (_, maxProgress) = notifierTexts.calculateSpanAndMaxProgress(rangeStart, rangeEnd)
        val currentProgress = notifierTexts.calculateCurrentProgress(state, isArrived)

        val baseInfo = JSONObject().apply {
            put("title", title)
            put("content", content)
            put("subTitle", subTitle)
        }

        val capsule = JSONObject().apply {
            put("text", capsuleText)
            put("status", if (isArrived) 1 else 0)
        }

        val progress = JSONObject().apply {
            put("max", maxProgress)
            put("current", currentProgress)
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("type", "bus")
            put("updatable", true)
            put("baseInfo", baseInfo)
            put("capsule", capsule)
            put("progress", progress)
        }

        val root = JSONObject().apply {
            put("param_v2", paramV2)
        }

        val jsonString = root.toString()
        val byteCount = jsonString.toByteArray(Charsets.UTF_8).size
        return if (byteCount <= MAX_JSON_BYTES) {
            jsonString
        } else {
            // 超出限制时进行轻量降级
            JSONObject().apply {
                put("param_v2", JSONObject().apply {
                    put("protocol", 1)
                    put("type", "bus")
                    put("baseInfo", JSONObject().apply {
                        put("title", state.lineName)
                        put("content", capsuleText)
                    })
                    put("capsule", JSONObject().apply {
                        put("text", capsuleText)
                    })
                })
            }.toString()
        }
    }
}
