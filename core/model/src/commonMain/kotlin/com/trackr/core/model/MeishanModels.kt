package com.trackr.core.model

import kotlinx.serialization.Serializable

/**
 * 眉山公交官方后台的区域（doc/眉山公交数据链路实测与重写契约 §3.1）。
 * `l_id` 只在区域内部唯一，缓存 / 收藏 / 订阅都必须带上 areaId。
 */
@Serializable
enum class MeishanArea(val areaId: Int, val displayName: String) {
    /** 眉山（东坡、彭山共用），约 110 条线路 */
    MEISHAN(1, "眉山"),

    /** 仁寿，约 25 条线路，`l_id` 使用 10000+ 系列 */
    RENSHOU(2, "仁寿");

    companion object {
        fun fromAreaId(areaId: Int): MeishanArea = entries.firstOrNull { it.areaId == areaId } ?: MEISHAN

        /** 按城市名取区域；不是眉山 / 仁寿返回 null */
        fun forCityName(cityName: String): MeishanArea? = when (cityName) {
            "眉山" -> MEISHAN
            "仁寿" -> RENSHOU
            else -> null
        }
    }
}

/** 当前城市是否属于眉山官方源的服务范围（眉山 / 仁寿） */
val TransitCity.isMeishanLocal: Boolean
    get() = MeishanArea.forCityName(cityName) != null

/**
 * 眉山官方 `/line/list` 的线路记录（契约 §5.1）。
 *
 * 注意（契约 §3.2 / §3.3）：每行是一个线路记录而非方向，方向由详情接口的 `state` 参数选择；
 * `name` 不唯一（如 76路/77路/82路 各有两条记录），展示时要靠起终点文本区分；
 * 起终点和时间字段都可能缺失，一律可空，不可强解析。
 */
@Serializable
data class MeishanLine(
    /** 线路主键（与 [MeishanArea.areaId] 联合唯一） */
    val lId: Int,
    /** 展示名称，可能重复 */
    val name: String,
    /** 方向 0 起终点描述（冒号分隔），可缺失 */
    val forwardText: String? = null,
    /** 方向 1 起终点描述，可缺失 */
    val backwardText: String? = null,
    /** 线路副本标识，不区分方向；区域间可能数值碰撞，不能单独当键 */
    val copyId: Int = 0,
    /** 方向 0 首班 "HH:mm"，可能缺失 */
    val startTime: String? = null,
    /** 方向 0 末班 "HH:mm"，可能缺失 */
    val endTime: String? = null,
    /** 方向 1 首班 "HH:mm"，可缺失或为 "00:00" */
    val backwardStartTime: String? = null,
    /** 方向 1 末班 "HH:mm"，可缺失或为 "00:00" */
    val backwardEndTime: String? = null,
    val area: MeishanArea = MeishanArea.MEISHAN
) {
    /** 列表展示用：优先方向 0 起终点，缺失时回退方向 1 */
    val primaryText: String
        get() = forwardText?.takeIf { it.isNotBlank() } ?: backwardText.orEmpty()
}
