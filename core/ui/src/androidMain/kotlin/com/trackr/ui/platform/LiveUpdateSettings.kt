package com.trackr.ui.platform

import android.content.Context

/**
 * 实时活动（Android 16 Live Update、小米焦点通知、OPPO 流体云）的设备判断和设置页跳转。
 *
 * 第 17 步过渡用：旧页面（线路详情、更多）原来直接调 `:androidApp` 里的厂商适配对象，页面搬进功能模块后看不到它们，
 * 改经这个接口（`:androidApp` 的实现原样转调 `LiveUpdatePromotion` / `XiaomiFocusAdapter` / `OppoFluidCloudAdapter`）。
 * 方法和原来的调用一一对应，参数里的 [Context] 也照旧由页面传入。
 */
interface LiveUpdateSettings {
    /** `LiveUpdatePromotion.isPromotionSupported()` */
    fun isPromotionSupported(): Boolean

    /** `LiveUpdatePromotion.canPromote(context)` */
    fun canPromote(context: Context): Boolean

    /** `LiveUpdatePromotion.openPromotionSettings(context)` */
    fun openPromotionSettings(context: Context): Boolean

    /** `LiveUpdatePromotion.openAppNotificationSettings(context)` */
    fun openAppNotificationSettings(context: Context): Boolean

    /**
     * `XiaomiFocusAdapter.isFocusSupported(context)`：这台机器到底有没有焦点通知 / 超级岛。
     * 「有没有这功能」和「用户开没开」是两件事——MIUI 全系没有，`canShowXiaomiFocus` 恒为 false，不能当没开。
     */
    fun isXiaomiFocusSupported(context: Context): Boolean

    /** `XiaomiFocusAdapter.canShowFocus(context)` */
    fun canShowXiaomiFocus(context: Context): Boolean

    /** `XiaomiFocusAdapter.openNotificationSettings(context)` */
    fun openXiaomiNotificationSettings(context: Context)

    /** `OppoFluidCloudAdapter.isColorOs()` */
    fun isColorOs(): Boolean

    /** `OppoFluidCloudAdapter.openFluidCloudSettings(context)` */
    fun openFluidCloudSettings(context: Context): Boolean
}
