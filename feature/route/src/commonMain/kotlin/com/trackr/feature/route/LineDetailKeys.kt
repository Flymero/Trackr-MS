package com.trackr.feature.route

import com.trackr.core.model.CandidateLine
import com.trackr.core.navigation.ScreenKey
import com.trackr.ui.screens.favoritesgroup.boardStopOrderForDetail

/**
 * 从路线规划 / 路线详情的一条候选线路跳线路详情：带上上车站和下车站名。
 * 高德方案没对齐到实时源时站序是占位的（上车站 = 1），只带站序会落到首站、方向也可能反；
 * 线路页按站名找候车站并判方向。
 */
fun CandidateLine.toLineDetailKey(): ScreenKey.LineDetail = ScreenKey.LineDetail(
    cityId = cityId,
    lineId = lineId,
    targetOrder = boardStopOrderForDetail(),
    stopId = "",
    source = source,
    lineName = lineName,
    boardStopName = startStopName,
    alightStopName = endStopName
)
