@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.trackr.app.platform

import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.UserFix
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import platform.CoreLocation.*
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject

/** Core Location objects and their delegates are created and released on the main thread. */
class IosLocationSource : LocationSource {
    private val latest = MutableStateFlow<UserFix?>(null)
    private val authorization = MutableStateFlow(CLLocationManager.authorizationStatus())
    val authorizationChanges = authorization.asStateFlow()
    private var permissionManager: CLLocationManager? = null
    private val permissionDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            authorization.value = manager.authorizationStatus
        }
    }

    fun requestPermission() {
        val manager = permissionManager ?: CLLocationManager().also {
            it.delegate = permissionDelegate
            permissionManager = it
        }
        authorization.value = manager.authorizationStatus
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
            manager.requestWhenInUseAuthorization()
        }
    }

    override fun hasAnyPermission(): Boolean = when (CLLocationManager.authorizationStatus()) {
        kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> true
        else -> false
    }

    override fun hasPrecisePermission() = hasAnyPermission() &&
        permissionManager?.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy

    override fun isLocationEnabled() = CLLocationManager.locationServicesEnabled()
    override fun lastKnown() = latest.value
    override fun currentFixes() = locationFlow(highAccuracy = true)
    override fun fixes(tier: LocationCadence.Tier) =
        if (tier == LocationCadence.Tier.OFF) emptyFlow() else locationFlow(tier.highAccuracy)

    private fun locationFlow(highAccuracy: Boolean) = callbackFlow {
        if (!hasAnyPermission() || !isLocationEnabled()) {
            close()
            return@callbackFlow
        }
        val manager = CLLocationManager()
        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                didUpdateLocations.filterIsInstance<CLLocation>().lastOrNull()?.let { location ->
                    if (location.horizontalAccuracy < 0) return
                    val coordinates = location.coordinate.useContents { latitude to longitude }
                    val ageMillis = (-location.timestamp.timeIntervalSinceNow * 1000).toLong().coerceAtLeast(0)
                    val fix = UserFix(
                        lat = coordinates.first,
                        lng = coordinates.second,
                        accuracyMeters = location.horizontalAccuracy.toFloat(),
                        elapsedMillis = (IosMonotonicClock.elapsedMillis() - ageMillis).coerceAtLeast(0)
                    )
                    latest.value = fix
                    trySend(fix)
                }
            }

            override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                // A temporarily unknown position can recover; the repository bounds each lookup.
                if (!hasAnyPermission()) close()
            }
        }
        manager.delegate = delegate
        manager.desiredAccuracy = if (highAccuracy) kCLLocationAccuracyBest else kCLLocationAccuracyHundredMeters
        manager.distanceFilter = if (highAccuracy) kCLDistanceFilterNone else 50.0
        manager.startUpdatingLocation()
        awaitClose {
            manager.stopUpdatingLocation()
            manager.delegate = null
            // Keep a strong reference to the delegate for the entire collection (Core Location uses weak).
            delegate.hashCode()
        }
    }.flowOn(Dispatchers.Main.immediate)
}
