package com.trackr.core.data.legacy

/**
 * 旧版 SharedPreferences 的文件名和键名（重构计划 §2.4「旧数据迁移」表）。
 * 旧文件一个都不删、一个 key 都不改，保留一个发版周期；回退旧版本仍能读到。
 */
object LegacyPrefs {
    /** 设置、收藏的车、搜索历史（0a4fdab 之前还有高德 Key） */
    const val TRANSIT = "trackr_prefs"
    const val ROUTE_GROUPS = "trackr_route_groups"
    const val REMINDERS = "trackr_scheduled_reminders"
    const val AMAP_KEYS = "trackr_amap_keys"
    const val REMINDER_SCHEDULE_STATE = "trackr_reminder_schedule_state"

    /** 迁移要看的全部旧文件 */
    val ALL_FILES = listOf(TRANSIT, ROUTE_GROUPS, REMINDERS, AMAP_KEYS, REMINDER_SCHEDULE_STATE)

    // trackr_prefs
    const val KEY_FAVORITES = "favorites_json"
    const val KEY_SEARCH_HISTORY = "search_history_json"
    const val KEY_SELECTED_CITY = "selected_city_json"
    const val KEY_ENGINE_MODE = "engine_mode"
    /** 旧的聚合优先级：只在导入时用来定主数据源（D1），不再导进新存储 */
    const val KEY_AGGREGATED_PRIORITY = "aggregated_source_priority"
    const val KEY_TRANSIT_CODE_PROVIDER = "preferred_transit_code_provider"
    const val KEY_NEARBY_STOPS_COUNT = "nearby_stops_count"
    const val KEY_LIVE_NOTIFICATION = "is_live_notification_enabled"
    const val KEY_SHOW_ON_LOCK_SCREEN = "show_on_lock_screen"
    const val KEY_ALERT_THRESHOLD_STOPS = "alert_threshold_stops"
    const val KEY_UI_MODE = "ui_mode"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_THEME_COLORS = "theme_colors"
    const val KEY_FONT_SCALE_PROMPT_DISMISSED = "font_scale_prompt_dismissed"
    const val KEY_FAVORITES_GROUP_MIGRATION_VERSION = "favorites_group_migration_version"

    // trackr_route_groups
    const val KEY_GROUPS = "groups_list_json"
    const val KEY_FAVORITE_GROUPS = "favorite_groups_list_json"

    /** 收藏合一索引（第 14 步起，旧存储模式下才写）：`List<FavoriteItemRecord>` 的 JSON */
    const val KEY_FAVORITE_ITEMS = "favorite_items_json"

    // trackr_scheduled_reminders
    const val KEY_REMINDERS = "reminders_list_json"

    // trackr_reminder_schedule_state
    const val KEY_PREFIX_NEXT_TRIGGER = "next_trigger_"

    // trackr_amap_keys（trackr_prefs 里同名的两个 key 是 0a4fdab 之前的位置）
    const val KEY_AMAP_ANDROID = "custom_amap_android_key"
    const val KEY_AMAP_WEB = "custom_amap_web_key"
    const val KEY_AMAP_MIGRATED_FROM_TRACKR_PREFS = "migrated_from_trackr_prefs"

    /** 148d67c 起 JSON 解析失败时的原文备份：`<key>_corrupt_backup_<时间戳>` */
    const val CORRUPT_BACKUP_MARK = "_corrupt_backup_"

    /** 损坏原文的副本放在 `files/legacy_backup/<文件>_<key>.json` */
    const val BACKUP_DIR = "legacy_backup"
}
