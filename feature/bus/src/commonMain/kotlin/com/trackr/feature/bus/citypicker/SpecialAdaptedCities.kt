package com.trackr.feature.bus.citypicker

import com.trackr.core.model.TransitCity

/**
 * 本 fork 特别适配的城市（眉山本地加强版）。
 *
 * 在城市选择器里享受的特殊展示：
 * 1. 热门城市行置顶；
 * 2. 复制一份到「直辖市」分组顶部（直辖市为默认选中分组，打开选择器第一眼可见）；
 * 3. 城市名旁带五角星标记。
 */
val SPECIAL_ADAPTED_CITY_NAMES: Set<String> = setOf("眉山")

/** 是否为特别适配城市 */
val TransitCity.isSpeciallyAdapted: Boolean
    get() = cityName in SPECIAL_ADAPTED_CITY_NAMES
