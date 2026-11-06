package com.trackr.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.displayLineName
import com.trackr.core.navigation.deeplink.DeepLinkUris
import com.trackr.deeplink.DeepLinkIntents
import kotlinx.coroutines.CancellationException

/**
 * 桌面小组件的 receiver。第 26 步迁 Glance：类名、Manifest 里的声明和 `bus_stop_widget_info.xml` 都不变，
 * 升级前已经加到桌面上的小组件继续由它接管（R9）。界面见 [BusStopWidget]，取数见 [BusStopWidgetUpdater]。
 */
class BusStopWidgetProvider : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = BusStopWidget()

    /** 添加 / 系统更新：Glance 先按已存状态画出基础布局，再排一次刷新取数；周期刷新已排上就不动 */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        BusStopWidgetWorker.schedulePeriodic(context)
        BusStopWidgetWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        BusStopWidgetWorker.cancel(context)
    }

    companion object {
        /**
         * 收藏变了：马上按新收藏重画（updateAll），再排一次刷新取数。桌面上没有小组件时什么都不做。
         */
        suspend fun triggerRefresh(context: Context) {
            try {
                if (BusStopWidget.glanceIds(context).isEmpty()) return
                BusStopWidget().updateAll(context)
                BusStopWidgetWorker.refreshNow(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 使用 ShortcutManagerCompat 将前 3 个常用候车站注册为桌面长按图标快捷方式
         */
        fun updateDynamicShortcuts(context: Context, favorites: List<FavoriteStationLine>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
            try {
                val shortcuts = favorites.take(3).mapIndexed { index, fav ->
                    val intent = DeepLinkIntents.activityIntent(context, fav.lineDetailLink()).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val lineName = fav.displayLineName
                    ShortcutInfoCompat.Builder(context, "shortcut_${fav.id}")
                        .setShortLabel("$lineName·${fav.targetStopName}")
                        .setLongLabel("$lineName 开往 ${fav.endStop} (${fav.targetStopName})")
                        .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_dialog_map))
                        .setIntent(intent)
                        .setRank(index)
                        .build()
                }
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/** 收藏的线路详情链接（小组件主视图、动态快捷方式共用；§3.6） */
internal fun FavoriteStationLine.lineDetailLink(): String = DeepLinkUris.line(
    cityId = cityId,
    lineId = lineId,
    source = source,
    stopId = targetStopId,
    targetOrder = targetOrder,
    // 老收藏里可能没存到名字：退回线路号，线路页顶栏才不是空的（issue #20）
    lineName = lineName.ifBlank { lineNo }
)
