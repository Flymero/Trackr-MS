package com.trackr.core.model

/**
 * 线路显示名：`name` 为空时退回线路号 `lineNo`。
 *
 * 数据源偶尔只给线路号不给线路名（车来了部分线路 `name` 是空串，只有 `lineNo`），
 * 按名字画线路号徽标时就会画成一个空白的圆角块（issue #20）。
 */
val BusLineDirection.displayLineName: String
    get() = name.trim().ifBlank { lineNo.trim() }

/**
 * 收藏卡的线路显示名。
 *
 * 收藏只存了收藏那一刻拿到的名字：数据源给了空名字、或者是更早的版本存的，名字就是空的，
 * 首页「收藏」、编辑收藏页、桌面小组件里都只剩一个空白徽标（issue #20）。
 * 名字和线路号都为空时返回空串，调用方可以再用实时数据里的名字兜底（见 `BusHomeMapper`）。
 */
val FavoriteStationLine.displayLineName: String
    get() = lineName.trim().ifBlank { lineNo.trim() }
