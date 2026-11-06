package com.trackr.core.domain.platform

import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.AppUpdateInfo
import com.trackr.core.model.AppUpdateInstallState
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.UpdateCheckResult
import com.trackr.core.model.UserFix
import kotlinx.coroutines.flow.Flow
import kotlin.time.TimeSource

/*
 * 平台能力接口（重构计划 §2.1、§2.8）。
 *
 * commonMain 里的 ViewModel / 引擎只认这些接口；实现放平台层：
 * Android 在 :androidApp（以后是各模块的 androidMain），iOS 在 iosMain。
 * 测试用 :core:testing 里的 Fake。
 *
 * 墙钟直接用标准库的 `kotlin.time.Clock`（它本身就是接口，有 `Clock.System`），这里不再重复定义；
 * 定位年龄这类要用单调时钟的地方用 [MonotonicClock]。
 */

/**
 * 单调时钟（毫秒），不受用户改系统时间影响；进程重启后归零，所以读数不能落盘。
 * Android：`SystemClock.elapsedRealtime()`，和 `Location.elapsedRealtimeNanos` 同一个时基，[UserFix.elapsedMillis] 用它。
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

/**
 * 进程内的单调时钟，起点是第一次读它的时刻（标准库 `TimeSource.Monotonic`）。
 * 只用来算差值，比如缓存过期；[UserFix] 的定位时刻要和平台定位同一时基，不能用它。
 */
val ProcessMonotonicClock: MonotonicClock = object : MonotonicClock {
    private val start = TimeSource.Monotonic.markNow()
    override fun elapsedMillis(): Long = start.elapsedNow().inWholeMilliseconds
}

/**
 * 追踪的控制入口（Android：会话写进 `TrackingRepository` 后启动前台服务；iOS：App 在前台时轮询）。
 * 追踪状态本身只从 `TrackingRepository.sessions` 读，这里只发命令。
 *
 * 可以同时追多项（第 36 步）：[start] 只加一份，不影响其他项；带 `sessionId` 的命令只作用于那一份，
 * 不传（null）时作用于主会话（还没拆开的旧调用）。
 */
interface TrackingController {

    /**
     * 进程重建后接续仍在有效期内的中断会话；无法启动服务时保留会话。
     *
     * @return 只有「会话已读回、确有中断会话、且服务已启动」才返回 true。
     *   会话还没从盘里读回来时返回 false，接续在后台等读盘完成后再发生——调用方不要把这个返回值
     *   当成「没有要接续的会话」。
     */
    fun resumeInterrupted(): Boolean = false

    /**
     * 开始追踪：往会话列表里加一份，正在追的其他项不受影响（同一个目标会替换掉原来那份）。
     * @return 是否启动成功；后台不允许启动前台服务时返回 false，这一份会话不保留，由调用方降级（如改发通知）
     */
    fun start(target: TrackingTarget): Boolean

    /** 结束追踪；[sessionId] 为 null 时结束全部 */
    fun stopTracking(sessionId: String? = null)

    /** 手动「我已上车」（只在路线、一起等的等车阶段有效） */
    fun confirmBoarded(sessionId: String? = null)

    /** 一起等「上了哪辆？」：选了 [busId]，null =「还没上」（第 15 步；弹层在第 21 步） */
    fun chooseBoardedBus(busId: String?, sessionId: String? = null)

    /** 一起等里去掉一条线（首页那一张卡点「结束」）：只停这一条 */
    fun removeWatchLine(sessionId: String, lineId: String)

    /** 手动切到下一程（只在路线追踪时有效） */
    fun switchToNextLeg(sessionId: String? = null)

    /** 切换提前几站提醒（1 -> 2 -> 3 -> 1） */
    fun cycleThreshold(sessionId: String? = null)

    /** 把带精度和定位时刻的手机定位喂给追踪（下车站辅助判定用） */
    fun updateUserFix(fix: UserFix)
}

/**
 * 朗读（长辈版「读给我听」，重构计划 §8.6）。Android：TextToSpeech；iOS：AVSpeechSynthesizer。
 * 朗读引擎第一次用时才初始化，所以能不能读要等 [speak] 返回才知道。
 */
interface Announcer {

    /**
     * 朗读一段文字；正在读的会被打断。
     * @return 开始读了为 true；读不了（没有朗读引擎、引擎不支持中文、初始化超时）为 false，调用方据此提示用户
     */
    suspend fun speak(text: String): Boolean

    /** 停止朗读 */
    fun stop()
}

/** 没有朗读能力（iOS 还没做、测试里不关心朗读时的默认值）：什么都不读，[speak] 总是 false */
object NoAnnouncer : Announcer {
    override suspend fun speak(text: String): Boolean = false
    override fun stop() = Unit
}

/** 打开乘车码。Android：Intent 打开支付宝 / 云闪付；iOS：URL Scheme */
interface TransitCodeLauncher {

    /** 打开指定渠道的乘车码；打不开（没装、[TransitCodeProvider.ASK_ALWAYS]）返回 false */
    fun open(provider: TransitCodeProvider): Boolean

    /** 这个渠道的 App 是否装了 */
    fun isInstalled(provider: TransitCodeProvider): Boolean
}

/**
 * 手机定位（WGS-84）。Android：系统 LocationManager；iOS：CLLocationManager。
 * 上车 / 下车判定只认精确定位，模糊定位精度在公里级。
 */
interface LocationSource {

    /** 按档位持续定位；[LocationCadence.Tier.OFF] 时不出数据。收集结束即停止定位 */
    fun fixes(tier: LocationCadence.Tier): Flow<UserFix>

    /** 系统缓存的最近一次定位，没有时为 null（年龄由调用方用 [MonotonicClock] 判断） */
    fun lastKnown(): UserFix?

    /** 是否有精确定位权限 */
    fun hasPrecisePermission(): Boolean

    /** 是否有任一定位权限（精确或模糊）；首页取城市、附近站用模糊定位也行 */
    fun hasAnyPermission(): Boolean = hasPrecisePermission()

    /** 各定位来源（Android：fused / network / gps / passive）缓存的最近定位，各一个；默认只有 [lastKnown] */
    fun lastKnownFixes(): List<UserFix> = listOfNotNull(lastKnown())

    /**
     * 立即在所有可用来源上以最高频率监听（首页取一次定位的窗口用），收集结束即停止。
     * 没有可用来源时直接结束，不出数据。
     */
    fun currentFixes(): Flow<UserFix>
}

/** 语音输入（长辈版搜索用）。Android：RecognizerIntent；iOS：SFSpeechRecognizer */
interface SpeechInput {

    /** 听一句话，返回识别出的文字；用户取消、没听清或没权限时返回 null */
    suspend fun listen(): String?
}

/**
 * 定时提醒的系统闹钟。Android：AlarmManager；iOS：UNCalendarNotificationTrigger。
 * 提醒数据存在仓库里，这里只管排 / 取消闹钟。
 */
interface ReminderAlarms {

    /** 排一个提醒的下一次触发；提醒已关闭时等同 [cancel] */
    fun schedule(reminder: ScheduledReminder)

    /** 取消一个提醒的闹钟 */
    fun cancel(reminderId: String)

    /** 按列表重排：开着的排，关着的取消（开机、改时间、改时区后调用） */
    fun rescheduleAll(reminders: List<ScheduledReminder>)

    /** 能否排精确闹钟（Android 12+ 要用户授权；只有精确闹钟到点才直接开追踪） */
    fun canScheduleExact(): Boolean

    /**
     * 打开系统的「闹钟和提醒」授权页（第 25 步，提醒编辑页「去设置」）。打不开时返回 false。
     * 默认不支持：iOS 没有这个权限。
     */
    fun openExactAlarmSettings(): Boolean = false

    /** 这个提醒上次排的触发时刻（墙钟毫秒）；从没排过或已取消时为 null */
    fun lastScheduledTriggerMillis(reminderId: String): Long?
}

/** 应用内更新检查（只有 Android 有；iOS 走 App Store） */
interface UpdateChecker {

    /**
     * 检查是否有新版本
     * @param currentVersionCode 当前安装版本的 versionCode
     * @param currentVersionName 当前安装版本的 versionName
     */
    suspend fun checkForUpdates(
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult
}

/**
 * 应用内下载并安装新版本（只有 Android 有；iOS 走 App Store）。
 *
 * 下载到应用缓存后调系统安装器，不再跳浏览器。第一次安装要先允许「安装未知应用」，
 * 所以分两步：没有权限时返回 [AppUpdateInstallState.AwaitingPermission]，用户去设置授权回来后
 * 由页面调 [retryPendingInstall] 接着装，不用重新下载。
 */
interface AppUpdateInstaller {

    /**
     * 下载 [updateInfo] 的安装包，校验包名 / 版本 / 签名后调起系统安装器。
     *
     * 下载地址不受信任、网络失败、安装包校验不过时返回 [AppUpdateInstallState.Failed]。
     * 协程被取消时删除下了一半的文件。
     *
     * @param onProgress 下载进度回调，可能在后台线程调用
     */
    suspend fun downloadAndInstall(
        updateInfo: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): AppUpdateInstallState

    /** 从系统设置授权回来后重试上次没装成的包；包不在了返回 [AppUpdateInstallState.Failed] */
    fun retryPendingInstall(): AppUpdateInstallState

    /** 打开系统的「安装未知应用」授权页 */
    fun openInstallPermissionSettings()

    /** 删掉已下载的安装包（取消下载、离开页面时用） */
    fun discardPending()
}

/** 「高德 Key」页显示的应用标识，帮用户在高德控制台绑定 Key */
interface AppSigningInfo {

    /** Android：包名；iOS：Bundle ID */
    val appId: String

    /** Android：当前签名证书的 SHA-1（冒号分隔的大写十六进制）；iOS 没有，为 null */
    fun certificateSha1(): String?
}
