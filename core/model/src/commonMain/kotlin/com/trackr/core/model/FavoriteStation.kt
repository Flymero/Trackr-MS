package com.trackr.core.model

import kotlinx.serialization.Serializable

/**
 * 收藏的站（issue #47）：只记住这个站，线路每次打开时现查，新开的线会自动出现。
 *
 * 一个站可能有同名的几个站台（「往北」「往南」）。收藏的是整个站，另外记一个**默认站台**：收藏时选中的那个，
 * 从首页点开就停在它上面。[stopId] / [physicalStId] / [namesakeStId] 都是默认站台的，和 `ScreenKey.StopDetail` 的参数一一对应。
 *
 * 掌上公交临时改道时站号会跳（变成 900 多），所以查实时数据时按 [stopName] + 坐标找回站台，不只认 [stopId]。
 *
 * @param id 见 [idOf]：城市 + 数据源 + 站（同名站台合在一起），同一个站再收藏只会得到同一个 id
 * @param platformLabel 默认站台的名字（「往北」「站台 1」）；只有一个站台时为空串
 */
@Serializable
data class FavoriteStation(
    val id: String,
    val cityId: String,
    val cityName: String = "",
    val stopName: String,
    val stopId: String,
    val physicalStId: String = "",
    val namesakeStId: String = "",
    val platformLabel: String = "",
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val source: DataSourceType = DataSourceType.CHELAILE,
    val mygolbsCityCode: String = "",
    val addedAtMillis: Long = defaultNowMillis()
) {
    companion object {
        /**
         * 站收藏的 id：有同名站 id（车来了把同名的几个站台归在一起）就用它，没有就用站名
         * （掌上公交没有同名站 id，站号又会因改道变，站名最稳）。
         */
        fun idOf(cityId: String, source: DataSourceType, stopName: String, namesakeStId: String): String =
            "${cityId}_${source.name}_${namesakeStId.ifBlank { stopName.trim() }}"
    }
}
