package com.trackr.app

import com.trackr.ui.screens.routeplan.map.IosRouteMapBridge
import platform.UIKit.UIView

/** SDK construction is deferred until the shared privacy gate and an iOS key are both present. */
fun installIosMap(
    create: (String) -> UIView,
    update: (UIView, String, Double) -> Unit,
    release: (UIView) -> Unit,
    privacy: (Boolean) -> Unit
) {
    IosRouteMapBridge.create = create
    IosRouteMapBridge.update = update
    IosRouteMapBridge.release = release
    IosRouteMapBridge.privacy = privacy
}

internal var publishIosSnapshot: ((String) -> Unit)? = null
internal var iosActivitiesAllowed: (() -> Boolean)? = null
fun installIosSnapshots(publish: (String) -> Unit, activitiesAllowed: () -> Boolean) {
    publishIosSnapshot = publish
    iosActivitiesAllowed = activitiesAllowed
}
