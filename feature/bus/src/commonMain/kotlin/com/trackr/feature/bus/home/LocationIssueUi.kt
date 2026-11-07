package com.trackr.feature.bus.home

import com.trackr.core.designsystem.resources.Res as CoreRes
import com.trackr.core.designsystem.resources.action_relocate
import com.trackr.feature.bus.resources.Res as BusRes
import com.trackr.feature.bus.resources.bus_home_location_action_enable
import com.trackr.feature.bus.resources.bus_home_location_action_grant
import com.trackr.feature.bus.resources.bus_home_location_permission_missing
import com.trackr.feature.bus.resources.bus_home_location_service_disabled
import com.trackr.feature.bus.resources.bus_home_location_unavailable
import org.jetbrains.compose.resources.StringResource

/**
 * 定位提示的三种文案 / 三个按钮 / 按钮发什么事件（标准版和长辈版共用）。
 *
 * 文案按原因分开写：以前不管什么原因都是「定位不可用」，用户看不出该去开权限还是开系统定位（issue #34）。
 */
internal val LocationIssue.messageRes: StringResource
    get() = when (this) {
        LocationIssue.PermissionMissing -> BusRes.string.bus_home_location_permission_missing
        LocationIssue.ServiceDisabled -> BusRes.string.bus_home_location_service_disabled
        LocationIssue.Unavailable -> BusRes.string.bus_home_location_unavailable
    }

/** 按钮文案：去授权 / 去开启 / 重新定位 */
internal val LocationIssue.actionRes: StringResource
    get() = when (this) {
        LocationIssue.PermissionMissing -> BusRes.string.bus_home_location_action_grant
        LocationIssue.ServiceDisabled -> BusRes.string.bus_home_location_action_enable
        LocationIssue.Unavailable -> CoreRes.string.action_relocate
    }

/** 按钮点了发什么事件 */
internal val LocationIssue.actionEvent: BusHomeEvent
    get() = when (this) {
        LocationIssue.PermissionMissing -> BusHomeEvent.OpenAppSettings
        LocationIssue.ServiceDisabled -> BusHomeEvent.OpenLocationSettings
        LocationIssue.Unavailable -> BusHomeEvent.RetryLocation
    }
