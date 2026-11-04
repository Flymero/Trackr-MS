package com.trackr.feature.bus.citypicker

import com.trackr.core.model.TransitCity

/**
 * 本 fork 特别适配的城市（眉山本地加强版）。
 *
 * 在城市选择器里享受的特殊展示：
 * 1. 「眉山」作为独立省份分组排在最前（内含眉山 / 仁寿），不再出现在四川里；
 * 2. 城市名旁带五角星标记（特别适配）。
 */
val SPECIAL_ADAPTED_CITY_NAMES: Set<String> = setOf("眉山")

/** 眉山特化的独立省份分组名（排在省份列表最前） */
const val MEISHAN_PROVINCE: String = "眉山"

/** 「眉山」分组里的城市（官方数据源覆盖范围） */
val MEISHAN_CITY_NAMES: List<String> = listOf("眉山", "仁寿")

/** 是否为特别适配城市 */
val TransitCity.isSpeciallyAdapted: Boolean
    get() = cityName in SPECIAL_ADAPTED_CITY_NAMES
