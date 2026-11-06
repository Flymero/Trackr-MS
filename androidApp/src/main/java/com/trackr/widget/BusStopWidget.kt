package com.trackr.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.trackr.MainActivity
import com.trackr.R
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.displayLineName
import com.trackr.deeplink.DeepLinkIntents
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面「实时公交候车卡」（第 26 步从 RemoteViews 迁 Glance，外观照 `widget_bus_stop.xml`）。
 *
 * 收藏从 [BusStopWidgetUpdater.currentFavorite] 的仓库直接收集，收藏变了界面马上换；
 * 实时结果由 [BusStopWidgetUpdater] 一次取数后写进每个实例的 Glance 状态（[publishEta]）。
 */
class BusStopWidget : GlanceAppWidget(), KoinComponent {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val favorites = runCatching { get<BusStopWidgetUpdater>().favorites }.getOrNull()
        provideContent {
            val favorite = favorites?.collectAsState()?.value?.firstOrNull()
            BusStopWidgetContent(favorite = favorite, eta = currentState<Preferences>().toEta())
        }
    }

    companion object {
        private val KEY_FAVORITE_ID = stringPreferencesKey("favorite_id")
        private val KEY_ETA_TEXT = stringPreferencesKey("eta_text")
        private val KEY_PROGRESS = intPreferencesKey("progress")
        private val KEY_UPDATED_AT = longPreferencesKey("updated_at")

        internal fun Preferences.toEta(): BusStopWidgetEta? {
            val favoriteId = this[KEY_FAVORITE_ID] ?: return null
            return BusStopWidgetEta(
                favoriteId = favoriteId,
                etaText = this[KEY_ETA_TEXT] ?: BusStopWidgetUpdater.ETA_LOADING,
                progressPercent = this[KEY_PROGRESS] ?: 0,
                updatedAtMillis = this[KEY_UPDATED_AT] ?: 0L
            )
        }

        /** 桌面上本小组件的全部实例（按旧 receiver 类名查，升级前加的也算） */
        fun glanceIds(context: Context): List<GlanceId> {
            val appWidgetIds = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, BusStopWidgetProvider::class.java))
                ?: return emptyList()
            val manager = GlanceAppWidgetManager(context)
            return appWidgetIds.map { manager.getGlanceIdBy(it) }
        }

        /** 把同一份结果写进 [ids] 每个实例的状态并重画 */
        suspend fun publishEta(context: Context, ids: List<GlanceId>, eta: BusStopWidgetEta) {
            val widget = BusStopWidget()
            for (id in ids) {
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                    prefs.toMutablePreferences().apply {
                        this[KEY_FAVORITE_ID] = eta.favoriteId
                        this[KEY_ETA_TEXT] = eta.etaText
                        this[KEY_PROGRESS] = eta.progressPercent
                        this[KEY_UPDATED_AT] = eta.updatedAtMillis
                    }
                }
                widget.update(context, id)
            }
        }
    }
}

/** 右上角刷新按钮：排一次刷新（Glance 的动作走不导出的内部 receiver，别的 App 触发不了，S2） */
class RefreshBusStopWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        BusStopWidgetWorker.refreshNow(context)
    }
}

private val TextPrimary = ColorProvider(Color(0xFF1D1B20))
private val TextSecondary = ColorProvider(Color(0xFF49454F))
private val TextTertiary = ColorProvider(Color(0xFF79747E))
private val Accent = ColorProvider(Color(0xFF006688))

/**
 * 小组件界面：没收藏是空状态，有收藏显示线路、方向、候车站、倒计时和进度条。
 * [eta] 不是这条收藏的（收藏刚换、还没刷新）时按基础布局显示「查询中…」。
 */
@Composable
internal fun BusStopWidgetContent(
    favorite: FavoriteStationLine?,
    eta: BusStopWidgetEta?,
    nowMillis: Long = System.currentTimeMillis()
) {
    val context = LocalContext.current
    // 点击主视图：有收藏就打开它的线路详情（trackr://line），没有就只打开应用
    val mainIntent = if (favorite != null) {
        DeepLinkIntents.activityIntent(context, favorite.lineDetailLink())
    } else {
        Intent(context, MainActivity::class.java)
    }.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.bg_widget_card))
            .padding(14.dp)
            .clickable(actionStartActivity(mainIntent))
    ) {
        if (favorite == null) {
            EmptyContent()
        } else {
            val shown = eta?.takeIf { it.favoriteId == favorite.id }
            FavoriteContent(
                favorite = favorite,
                etaText = shown?.etaText ?: BusStopWidgetUpdater.ETA_LOADING,
                progressPercent = shown?.progressPercent ?: 0,
                updatedAtMillis = shown?.updatedAtMillis ?: nowMillis
            )
        }
    }
}

@Composable
private fun EmptyContent() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "车在路上 · 实时候车卡",
            style = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text = "暂无收藏的候车站，点击打开 App 添加",
            style = TextStyle(color = TextTertiary, fontSize = 12.sp)
        )
    }
}

@Composable
private fun FavoriteContent(
    favorite: FavoriteStationLine,
    etaText: String,
    progressPercent: Int,
    updatedAtMillis: Long
) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        // 顶部信息行：线路名、城市/数据源标签、更新时间、刷新按钮
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = favorite.displayLineName,
                style = TextStyle(color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = "${favorite.cityName} · ${favorite.source.displayName}",
                style = TextStyle(color = TextSecondary, fontSize = 11.sp)
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedAtMillis)),
                style = TextStyle(color = TextTertiary, fontSize = 11.sp),
                modifier = GlanceModifier.padding(end = 6.dp)
            )
            Image(
                provider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = "刷新",
                modifier = GlanceModifier
                    .size(26.dp)
                    .padding(4.dp)
                    .clickable(actionRunCallback<RefreshBusStopWidgetAction>())
            )
        }
        // 方向与候车站
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "开往 ${favorite.endStop}",
                style = TextStyle(color = TextSecondary, fontSize = 12.sp),
                maxLines = 1
            )
            Text(text = " · ", style = TextStyle(color = TextTertiary, fontSize = 12.sp))
            Text(
                text = "候车站: ${favorite.targetStopName}",
                style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
        // 倒计时大字
        Text(
            text = etaText,
            style = TextStyle(color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.padding(top = 6.dp)
        )
        // 实时到站进度条
        Spacer(GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = progressPercent.coerceIn(0, 100) / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(5.dp)
        )
    }
}
