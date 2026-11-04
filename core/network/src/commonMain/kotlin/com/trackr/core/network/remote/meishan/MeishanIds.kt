package com.trackr.core.network.remote.meishan

/**
 * 眉山官方线路的 lineId 编解码（对齐 [com.trackr.core.network.remote.mygolbs.MygolbsIds] 的前缀约定）。
 *
 * 官方后台线路要 `areaId + l_id + state` 三个字段才能定位一个方向（doc/契约 §3.1、§3.2），
 * 三段打包进 lineId 字符串，跨收藏 / 返回栈 / 详情请求不丢信息。
 */
object MeishanIds {

    const val PREFIX = "ms:"

    /** 解码结果：[areaId] + [lId] + [state] 三元组 */
    data class Ref(val areaId: Int, val lId: Int, val state: Int)

    fun encode(areaId: Int, lId: Int, state: Int): String = "$PREFIX${areaId}_${lId}_$state"

    fun isMeishanId(id: String?): Boolean = id?.startsWith(PREFIX) == true

    fun parse(id: String?): Ref? {
        if (!isMeishanId(id)) return null
        val parts = id!!.removePrefix(PREFIX).split("_")
        if (parts.size != 3) return null
        val areaId = parts[0].toIntOrNull() ?: return null
        val lId = parts[1].toIntOrNull() ?: return null
        val state = parts[2].toIntOrNull() ?: return null
        return Ref(areaId, lId, state)
    }
}
