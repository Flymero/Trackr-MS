package com.trackr.di

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.trackr.BuildConfig
import com.trackr.core.data.local.LocalMigrationFlags
import com.trackr.core.data.local.StoreMigrationFlags
import com.trackr.core.data.repository.AmapKeyDefaults
import com.trackr.core.data.repository.UserLocationStore
import com.trackr.core.domain.platform.Announcer
import com.trackr.core.domain.platform.AppSigningInfo
import com.trackr.core.domain.platform.AppVersionInfo
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.model.TransitCodeProvider
import com.trackr.data.repository.ITransitRepository
import com.trackr.platform.speech.AndroidTtsAnnouncer
import com.trackr.service.ArrivalAlertNotifier
import com.trackr.service.LiveProgressNotifier
import com.trackr.service.vendor.LiveUpdatePromotion
import com.trackr.service.vendor.OppoFluidCloudAdapter
import com.trackr.service.vendor.XiaomiFocusAdapter
import com.trackr.ui.platform.LiveUpdateAccess
import com.trackr.ui.platform.LiveUpdateSettings
import com.trackr.ui.platform.LiveUpdateStatus
import com.trackr.ui.platform.NotificationHealth
import com.trackr.ui.platform.NotificationHealthAccess
import com.trackr.ui.platform.NotificationHealthIssue
import com.trackr.util.SignatureUtils
import com.trackr.util.TransitCodeHelper
import kotlinx.coroutines.flow.StateFlow
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * 第 17 步：功能模块看不到 `:androidApp`，页面和 ViewModel 要用的 Android 能力经接口注入，
 * 这里给出 Android 实现（都原样转调原来的工具对象）。
 */
val platformBridgeModule: Module = module {
    single<TransitCodeLauncher> { AndroidTransitCodeLauncher(androidContext()) }
    single<AppSigningInfo> { AndroidAppSigningInfo(androidContext()) }
    // 「读给我听」（第 33 步）：进程级一个，第一次读才建引擎；Koin 关闭时释放
    single<Announcer> { AndroidTtsAnnouncer(androidContext(), get(AppScopeQualifier)) } onClose {
        (it as? AndroidTtsAnnouncer)?.shutdown()
    }
    single<LiveUpdateSettings> { AndroidLiveUpdateSettings }
    // 「我的」页「状态栏胶囊」那一行（第 25 步）
    single<LiveUpdateAccess> { AndroidLiveUpdateAccess(androidContext(), get()) }
    single<NotificationHealthAccess> { AndroidNotificationHealthAccess(androidContext()) }
    single<UserLocationStore> { TransitUserLocationStore(get()) }
    // 收藏分组一次性数据修复的版本号（原来在 viewModelModule 里给 FavoritesGroupViewModel 现建）
    single<LocalMigrationFlags> { StoreMigrationFlags(get()) }
    factory { androidAppVersion(androidContext()) }
    // 构建时注入的高德 Key（和 StorageBackend 里给 AmapKeyRepository 的是同一组值）；「更多」页 Key 对话框判断「已配置」用
    single { AmapKeyDefaults(androidKey = BuildConfig.AMAP_ANDROID_KEY, webKey = BuildConfig.AMAP_WEB_KEY, hasAndroidMapSdk = true) }
}

/** 乘车码：转调 [TransitCodeHelper]（Intent 都带 NEW_TASK，用 applicationContext 打开和原来一样） */
class AndroidTransitCodeLauncher(context: Context) : TransitCodeLauncher {
    private val appContext: Context = context.applicationContext ?: context

    override fun open(provider: TransitCodeProvider): Boolean =
        TransitCodeHelper.openTransitCode(appContext, provider)

    override fun isInstalled(provider: TransitCodeProvider): Boolean = when (provider) {
        TransitCodeProvider.ALIPAY -> TransitCodeHelper.isAlipayInstalled(appContext)
        TransitCodeProvider.UNIONPAY -> TransitCodeHelper.isUnionPayInstalled(appContext)
        TransitCodeProvider.ASK_ALWAYS -> false
    }
}

/** 包名 + 签名 SHA-1：转调 [SignatureUtils]（取不到时为空串，和原来一样） */
class AndroidAppSigningInfo(context: Context) : AppSigningInfo {
    private val appContext: Context = context.applicationContext ?: context

    override val appId: String get() = SignatureUtils.getPackageName(appContext)

    override fun certificateSha1(): String = SignatureUtils.getSigningCertificateSha1(appContext)
}

/** 实时活动的设备判断与设置跳转：转调三个厂商适配对象 */
object AndroidLiveUpdateSettings : LiveUpdateSettings {
    override fun isPromotionSupported(): Boolean = LiveUpdatePromotion.isPromotionSupported()
    override fun canPromote(context: Context): Boolean = LiveUpdatePromotion.canPromote(context)
    override fun openPromotionSettings(context: Context): Boolean = LiveUpdatePromotion.openPromotionSettings(context)
    override fun openAppNotificationSettings(context: Context): Boolean =
        LiveUpdatePromotion.openAppNotificationSettings(context)
    override fun isXiaomiRom(): Boolean = XiaomiFocusAdapter.isXiaomiRom()
    override fun canShowXiaomiFocus(context: Context): Boolean = XiaomiFocusAdapter.canShowFocus(context)
    override fun openXiaomiNotificationSettings(context: Context) = XiaomiFocusAdapter.openNotificationSettings(context)
    override fun isColorOs(): Boolean = OppoFluidCloudAdapter.isColorOs()
    override fun openFluidCloudSettings(context: Context): Boolean = OppoFluidCloudAdapter.openFluidCloudSettings(context)
}

/**
 * 「我的」页「状态栏胶囊」：判断和跳转和第 17 步「更多」页里的写法一样（经 [LiveUpdateSettings]，用 applicationContext；
 * 厂商适配对象打开设置页时都带 NEW_TASK）。
 */
class AndroidLiveUpdateAccess(
    context: Context,
    private val settings: LiveUpdateSettings
) : LiveUpdateAccess {
    private val appContext: Context = context.applicationContext ?: context

    private val supported: Boolean
        get() = settings.isPromotionSupported() || settings.isXiaomiRom() || settings.isColorOs()

    private fun allowed(): Boolean = when {
        settings.isXiaomiRom() -> settings.canShowXiaomiFocus(appContext)
        settings.isPromotionSupported() -> settings.canPromote(appContext)
        else -> false
    }

    override fun status(): LiveUpdateStatus {
        if (!supported) return LiveUpdateStatus(supported = false, allowed = false, systemName = "")
        val name = when {
            settings.isXiaomiRom() -> "小米焦点通知"
            settings.isColorOs() -> "OPPO 流体云"
            else -> "Android 实时活动"
        }
        return LiveUpdateStatus(supported = true, allowed = allowed(), systemName = name)
    }

    override fun openSettings() {
        when {
            !supported -> settings.openAppNotificationSettings(appContext)
            !allowed() -> settings.openPromotionSettings(appContext)
            settings.isXiaomiRom() -> settings.openXiaomiNotificationSettings(appContext)
            settings.isColorOs() -> settings.openFluidCloudSettings(appContext)
            else -> settings.openAppNotificationSettings(appContext)
        }
    }
}

/**
 * “我的”页的通知可靠性体检：只检查系统公开 API 能确认的状态。
 * 尚未创建的渠道视为正常，避免用户第一次追踪前看到误报。
 */
class AndroidNotificationHealthAccess(context: Context) : NotificationHealthAccess {
    private val appContext: Context = context.applicationContext ?: context

    override fun status(): NotificationHealth {
        val notificationsEnabled = runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(true)
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val alertChannel = manager?.getNotificationChannel(ArrivalAlertNotifier.CHANNEL_ID_ALERT)
        val liveChannel = manager?.getNotificationChannel(LiveProgressNotifier.CHANNEL_ID_LIVE_PROGRESS)
        return NotificationHealth(
            notificationsEnabled = notificationsEnabled,
            alertChannelAudible = alertChannel == null ||
                alertChannel.importance >= NotificationManager.IMPORTANCE_HIGH,
            liveChannelEnabled = liveChannel == null ||
                liveChannel.importance != NotificationManager.IMPORTANCE_NONE
        )
    }

    override fun openSettings(issue: NotificationHealthIssue) {
        val channelId = when (issue) {
            NotificationHealthIssue.NOTIFICATIONS -> null
            NotificationHealthIssue.ALERT_CHANNEL -> ArrivalAlertNotifier.CHANNEL_ID_ALERT
            NotificationHealthIssue.LIVE_CHANNEL -> LiveProgressNotifier.CHANNEL_ID_LIVE_PROGRESS
        }
        val opened = if (channelId == null) {
            LiveUpdatePromotion.openAppNotificationSettings(appContext)
        } else {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { appContext.startActivity(intent) }.isSuccess
        }
        if (!opened) LiveUpdatePromotion.openAppNotificationSettings(appContext)
    }
}

/** 首页定位写入的最近坐标：仍存在 [ITransitRepository] 里，这里只是把那两个成员露给功能模块 */
class TransitUserLocationStore(private val repository: ITransitRepository) : UserLocationStore {
    override val userLocation: StateFlow<Pair<Double, Double>?> get() = repository.userLocation

    override fun updateUserLocation(lat: Double, lng: Double) = repository.updateUserLocation(lat, lng)
}

/** 当前安装包的版本号（原 `AboutViewModel.getDefaultAppVersion`，读不到时 1 / 0.0.1） */
fun androidAppVersion(context: Context): AppVersionInfo {
    val (code, name) = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
        val versionName = packageInfo.versionName ?: "0.0.1"
        versionCode to versionName
    }.getOrDefault(1 to "0.0.1")
    return AppVersionInfo(code, name)
}
