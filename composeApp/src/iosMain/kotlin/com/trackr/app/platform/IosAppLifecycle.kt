package com.trackr.app.platform

import platform.Foundation.*
import platform.UIKit.*

/** All UIKit notifications and callbacks are delivered on the main queue. */
class IosAppLifecycle(private val onActive: (Boolean) -> Unit) {
    private val center = NSNotificationCenter.defaultCenter
    private val observers = listOf(
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { onActive(true) },
        center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { onActive(false) },
        center.addObserverForName(UIApplicationSignificantTimeChangeNotification, null, NSOperationQueue.mainQueue) {
            onActive(false)
            onActive(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
        }
    )
    init { onActive(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) }
    fun close() { observers.forEach { center.removeObserver(it) }; onActive(false) }
}
