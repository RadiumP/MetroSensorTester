package com.metrobooming.sensortester

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/**
 * Continuous GPS fixes feeding the optional "位准" (ground-truth) signal in
 * InferenceEngine -- see GPS_CALIBRATION_WINDOW_MS's doc there for the
 * calibrate-then-decide design. Deliberately separate from
 * SensorCollector's existing one-shot getLastKnownLocation() call (used only
 * to fetch magnetic declination once): that call never subscribes to
 * updates and has nothing to do with this continuous GPS_PROVIDER stream.
 *
 * ACCESS_COARSE_LOCATION alone only unlocks NETWORK_PROVIDER, whose accuracy
 * (typically tens to hundreds of meters) is too poor to ever pass
 * InferenceEngine's calibration test -- ACCESS_FINE_LOCATION is requested so
 * GPS_PROVIDER is usable, but network updates are still subscribed to as a
 * harmless second source (the accuracy gate downstream simply rejects them
 * when they're not good enough).
 */
class GpsCollector(private val context: Context) {
    data class GpsSnapshot(
        val speedMps: Double?,
        val accuracyM: Double?,
        val fixAgeMs: Long?,
    )

    companion object {
        // A fix this old is treated as no fix at all -- e.g. the phone just
        // entered a tunnel and the last known speed/accuracy no longer
        // describes "now". Deliberately shorter than
        // InferenceEngine.GPS_CALIBRATION_WINDOW_MS so a dead GPS shows up
        // as "no samples" during calibration rather than one single stale
        // lucky fix from the first second.
        private const val FIX_STALE_MS = 5_000L
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var latestLocation: Location? = null
    private var latestLocationAt: Long? = null
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation = location
            latestLocationAt = SystemClock.elapsedRealtime()
        }

        @Deprecated("Deprecated in platform API, still required to override pre-Q")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun start() {
        if (listening) return
        val manager = locationManager ?: return
        val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return
        try {
            if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            listening = true
        } catch (_: SecurityException) {
            // Permission revoked between the check above and the call, or
            // the OEM location stack refuses it outright -- treat this
            // exactly like "no GPS available", which the calibration
            // accuracy test in InferenceEngine already handles by giving up
            // on GPS for the ride.
        }
    }

    fun stop() {
        if (!listening) return
        locationManager?.removeUpdates(listener)
        listening = false
        latestLocation = null
        latestLocationAt = null
    }

    fun takeSnapshot(now: Long): GpsSnapshot {
        val location = latestLocation
        val fixAt = latestLocationAt
        if (location == null || fixAt == null) {
            return GpsSnapshot(speedMps = null, accuracyM = null, fixAgeMs = null)
        }
        val age = (now - fixAt).coerceAtLeast(0L)
        if (age > FIX_STALE_MS) {
            return GpsSnapshot(speedMps = null, accuracyM = null, fixAgeMs = age)
        }
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        val speed = if (location.hasSpeed()) location.speed.toDouble() else null
        return GpsSnapshot(speedMps = speed, accuracyM = accuracy, fixAgeMs = age)
    }
}
