package com.trackr.core.data.local

import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * 仓库下面的「存储层」（第 9 步）。
 *
 * 仓库的业务逻辑（加收藏放最前、搜索历史去重 20 条、清理假数据、默认分组、删分组后路线变不分组……）只有一份，
 * 存储有两套实现，由运行时开关 USE_NEW_STORE 选：
 * - 新：Room（列表类）+ DataStore（设置、高德 Key），本模块 `Room*Store` / `DataStore*Store`；
 * - 旧：原来的 SharedPreferences + JSON，在 :androidApp 的 `SharedPrefs*Store`，开关关掉时用（风险 R1）。
 *
 * 所有方法都是挂起函数；仓库在构造时读一次（`load*`），之后每次改动整份写回。
 * 第 14 步：收藏的车、路线、收藏分组、合一索引合到 [FavoritesStore]（之前分在 TransitLocalStore 和 RouteGroupStore）。
 */

/** 首页设置 + 搜索历史一次读出的快照。字段为 null 表示没存过，由仓库套默认值。收藏的车第 14 步挪到 [FavoritesStore] */
data class TransitLocalSnapshot(
    /** 当前城市（TransitCity 的 JSON）；为 null 表示从没选过城市 */
    val selectedCityJson: String? = null,
    /** 数据源（`EngineMode` 的名字）；旧的聚合配置在读出时已换成主数据源 */
    val engineMode: String? = null,
    val transitCodeProvider: String? = null,
    /** 首页「乘车码」悬浮按钮显不显示；没存过为 null，按显示 */
    val showTransitCodeButton: Boolean? = null,
    /** 上车后自动结束追踪（issue #32）；没存过为 null，按开 */
    val autoStopAfterBoarding: Boolean? = null,
    val nearbyStopsCount: Int? = null,
    val liveNotificationEnabled: Boolean? = null,
    val showOnLockScreen: Boolean? = null,
    val alertThresholdStops: Int? = null,
    /** 界面模式（`UiMode` 的名字）；没存过为 null，按标准版 */
    val uiMode: String? = null,
    /** 走向条样式（`RailMode` 的名字）；没存过为 null，按横排 */
    val railMode: String? = null,
    /** 深浅色偏好（`ThemeMode` 的名字）；没存过为 null，按跟随系统 */
    val themeMode: String? = null,
    val themeColorsJson: String? = null,
    val fontScalePromptDismissed: Boolean? = null,
    val favorites: List<FavoriteStationLine> = emptyList(),
    val searchHistory: List<String> = emptyList()
)

/** TransitRepository 的存储：设置（旧 `trackr_prefs` / 新 `trackr_settings`）、搜索历史 */
interface TransitLocalStore {
    suspend fun load(): TransitLocalSnapshot
    suspend fun saveSelectedCityJson(json: String)
    suspend fun saveEngineMode(name: String)
    suspend fun saveTransitCodeProvider(id: String)
    suspend fun saveShowTransitCodeButton(enabled: Boolean)
    suspend fun saveAutoStopAfterBoarding(enabled: Boolean)
    suspend fun saveNearbyStopsCount(count: Int)
    suspend fun saveLiveNotificationEnabled(enabled: Boolean)
    suspend fun saveShowOnLockScreen(enabled: Boolean)
    suspend fun saveAlertThresholdStops(stops: Int)
    suspend fun saveUiMode(name: String)
    suspend fun saveRailMode(name: String)
    suspend fun saveThemeMode(name: String)
    suspend fun saveThemeColors(json: String)
    suspend fun saveFontScalePromptDismissed(dismissed: Boolean)
    suspend fun saveSearchHistory(list: List<String>)
    suspend fun clearSearchHistory()

    /** 收藏页一次性数据修复的版本号（旧 `favorites_group_migration_version`）；没记过为 0 */
    suspend fun favoritesGroupMigrationVersion(): Int
    suspend fun saveFavoritesGroupMigrationVersion(version: Int)
}

/**
 * 收藏合一一次读出的快照（第 14 步）。
 *
 * @param stops 收藏的车，按存的顺序
 * @param routes 路线，按存的顺序；从没存过时为 null。旧实现里 JSON 损坏时先备份原文再给空列表
 * @param groups 收藏分组；没存过或损坏时为 null
 * @param items 合一索引（顺序 + 分组）；没存过时为 null（仓库按车和路线现有的顺序推出来，见 FavoriteItemOrder）
 * @param stations 收藏的站（issue #47），按存的顺序
 * @param groupsInitialized 收藏分组建过没有（写过默认分组，或者用户自己建过 / 删过分组）。为 true 时 [groups] 空就是
 *   「用户把分组删光了」，仓库不再补默认分组（见 [com.trackr.core.data.repository.DefaultFavoritesRepository.create]）
 */
data class FavoritesSnapshot(
    val stops: List<FavoriteStationLine> = emptyList(),
    val routes: List<TransitGroup>? = null,
    val groups: List<FavoriteGroup>? = null,
    val items: List<FavoriteItemRecord>? = null,
    val groupsInitialized: Boolean = false,
    val stations: List<FavoriteStation> = emptyList()
)

/**
 * 一次写入。为 null 的部分不动，给了的整份替换；[itemUpdates] 只改（或补上）列出的那几行索引。
 * 新实现（Room）所有部分在一个事务里写。
 *
 * 仓库保证：[stops]、[routes] 按合一顺序排好；[routes] 里每条的 `customGroupId` 和索引的分组一致。
 */
data class FavoritesWrite(
    val stops: List<FavoriteStationLine>? = null,
    val routes: List<TransitGroup>? = null,
    val groups: List<FavoriteGroup>? = null,
    val items: List<FavoriteItemRecord>? = null,
    val itemUpdates: List<FavoriteItemRecord> = emptyList(),
    /** 收藏的站（issue #47），按合一顺序排好；null = 不动 */
    val stations: List<FavoriteStation>? = null,
    /**
     * 顺手记下「分组建过了」（[FavoritesSnapshot.groupsInitialized]）。仓库每次写入都带上，
     * 所以 [groups] 写成空列表之后，下次启动读回来就知道那是用户删空的，不是没建过。
     */
    val markGroupsInitialized: Boolean = false
)

/** DefaultFavoritesRepository 的存储：收藏的车、路线、收藏的站、收藏分组、合一索引 */
interface FavoritesStore {
    suspend fun load(): FavoritesSnapshot
    suspend fun write(change: FavoritesWrite)
}

/** ScheduledReminderRepository 的存储 */
interface ReminderStore {
    /** 旧实现里 JSON 损坏时先备份原文再返回空列表 */
    suspend fun load(): List<ScheduledReminder>

    /** 整份写回；新实现保留每个提醒已记下的计划触发时刻 */
    suspend fun save(list: List<ScheduledReminder>)
}

/** 每个提醒上次给系统闹钟排的触发时刻（旧 `trackr_reminder_schedule_state`，新 `reminder.lastScheduledTriggerAt`） */
interface ReminderScheduleStateStore {
    suspend fun loadAll(): Map<String, Long>
    suspend fun set(reminderId: String, triggerAtMillis: Long)
    suspend fun remove(reminderId: String)
}

/** 高德 Key 两槽（Android 槽、Web 槽，严格分开、互不回落）。值为 null 表示没填 */
interface AmapKeyStore {
    suspend fun loadIosKey(): String? = null
    suspend fun saveIosKey(key: String?) = Unit
    suspend fun loadAndroidKey(): String?
    suspend fun loadWebKey(): String?
    suspend fun saveAndroidKey(key: String?)
    suspend fun saveWebKey(key: String?)
    suspend fun loadPrivacyConsent(): Boolean? = null
    suspend fun savePrivacyConsent(consented: Boolean) = Unit
}

/**
 * 按调用顺序串行执行写入，不阻塞调用方（给非挂起的 setter 用，效果同 SharedPreferences.apply()）。
 * Mutex 是先到先得的，[CoroutineStart.UNDISPATCHED] 保证在调用线程上就去排队，所以执行顺序 = 调用顺序。
 */
class SerialWriter(private val scope: CoroutineScope) {
    private val mutex = Mutex()

    fun enqueue(block: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                // 一次写失败不影响后面的写入，也不让应用崩（旧版 apply() 失败同样是静默的）
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }

    /** 写入失败时的回调（默认什么都不做；Android 上接日志） */
    var onError: (Exception) -> Unit = {}

    /** 等之前排进来的写入都做完 */
    suspend fun flush() {
        mutex.withLock { }
    }
}

/**
 * 收藏页一次性数据修复（`FavoritesGroupViewModel.runDataMigrationIfNeeded`）记的版本号。
 * 第 9 步从 ViewModel 直接读写 SharedPreferences 改成经这里，存在设置里（旧 `trackr_prefs` / 新 `trackr_settings`）。
 */
interface LocalMigrationFlags {
    suspend fun favoritesGroupMigrationVersion(): Int
    suspend fun setFavoritesGroupMigrationVersion(version: Int)
}

class StoreMigrationFlags(private val store: TransitLocalStore) : LocalMigrationFlags {
    override suspend fun favoritesGroupMigrationVersion(): Int = store.favoritesGroupMigrationVersion()

    override suspend fun setFavoritesGroupMigrationVersion(version: Int) =
        store.saveFavoritesGroupMigrationVersion(version)
}
