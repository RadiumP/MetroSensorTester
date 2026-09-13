package com.metrobooming.sensortester

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Battery bookkeeping for a recording session.
 *
 * Important caveat before reading any of these numbers: Android exposes no
 * supported way for an app to measure its OWN draw. Everything here is
 * device-wide, so a figure like sessionUsedMah covers the screen, the radios
 * and every other app as well. It is still the number worth having, because
 * during a ride this app is normally the only meaningful load and the point
 * of the measurement is comparing configurations of it (mic on or off, GPS on
 * or off) under otherwise similar conditions. Comparisons are only fair
 * between rides with similar screen-on time, so appInForeground and screenOn
 * are already in the CSV next to these columns.
 *
 * The charge counter is the honest measurement and the percentage is not:
 * BATTERY_PROPERTY_CAPACITY moves in whole percent, so a 15-minute ride often
 * shows a drop of 1 or even 0, whereas CHARGE_COUNTER is reported in
 * microamp-hours and resolves far smaller changes. Prefer sessionUsedMah;
 * treat sessionDrainPercentPerHour as a sanity check.
 *
 * While the phone is charging (the 2026-09-12 rides were, off a power bank)
 * consumption cannot be measured at all: the counter climbs instead of
 * falling. charging is logged so those stretches can be excluded rather than
 * silently averaged in.
 */
class BatteryCollector(private val context: Context) {
    data class BatterySnapshot(
        val percent: Int?,
        val chargeCounterUah: Long?,
        // Instantaneous current. The sign convention is not consistent across
        // manufacturers (most report discharge as negative, some as
        // positive), so compare magnitudes and lean on the charge counter
        // delta for direction.
        val currentNowUa: Long?,
        val temperatureC: Double?,
        val charging: Boolean?,
        val plugged: String,
        // Session deltas, null until a baseline is captured at start, or
        // whenever the device does not implement the charge counter.
        val sessionUsedMah: Double?,
        val sessionDrainPercentPerHour: Double?,
        val sessionElapsedMs: Long,
    )

    companion object {
        // Unsupported properties come back as the type's MIN_VALUE on most
        // devices, and 0 on a few; neither is a plausible reading for any
        // property here, so both are treated as "not available".
        private fun sane(value: Long): Long? =
            if (value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong() || value == 0L) null else value
    }

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private var baselineChargeUah: Long? = null
    private var baselinePercent: Int? = null
    private var baselineAt: Long? = null

    /** Captures the reference point every session delta is measured against. */
    fun startSession() {
        baselineAt = SystemClock.elapsedRealtime()
        baselineChargeUah = readChargeCounterUah()
        baselinePercent = readPercent()
    }

    fun stopSession() {
        baselineAt = null
        baselineChargeUah = null
        baselinePercent = null
    }

    private fun readPercent(): Int? {
        val manager = batteryManager ?: return null
        val value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (value in 0..100) value else null
    }

    private fun readChargeCounterUah(): Long? {
        val manager = batteryManager ?: return null
        return sane(manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
    }

    private fun readCurrentNowUa(): Long? {
        val manager = batteryManager ?: return null
        return sane(manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
    }

    fun takeSnapshot(): BatterySnapshot {
        val percent = readPercent()
        val charge = readChargeCounterUah()
        val now = SystemClock.elapsedRealtime()
        val elapsed = baselineAt?.let { (now - it).coerceAtLeast(0L) } ?: 0L

        // Sticky broadcast, so this returns the last known battery state
        // immediately without ever registering a real receiver.
        val status: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: RuntimeException) {
            null
        }
        val temperature = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10.0 }
        val pluggedRaw = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugged = when (pluggedRaw) {
            0 -> "未接电源"
            BatteryManager.BATTERY_PLUGGED_AC -> "充电器"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
            else -> "其他"
        }
        val statusRaw = status?.getIntExtra(BatteryManager.EXTRA_STATUS, Int.MIN_VALUE)
        val charging = when (statusRaw) {
            null, Int.MIN_VALUE -> pluggedRaw != 0
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }

        // A positive value means charge was consumed; a negative one means the
        // battery gained charge, which is what a ride spent plugged in looks
        // like.
        val usedMah = if (baselineChargeUah != null && charge != null) {
            (baselineChargeUah!! - charge) / 1000.0
        } else {
            null
        }
        val drainPerHour = if (
            baselinePercent != null && percent != null && elapsed >= 60_000L
        ) {
            (baselinePercent!! - percent) * 3_600_000.0 / elapsed
        } else {
            null
        }

        return BatterySnapshot(
            percent = percent,
            chargeCounterUah = charge,
            currentNowUa = readCurrentNowUa(),
            temperatureC = temperature,
            charging = charging,
            plugged = plugged,
            sessionUsedMah = usedMah,
            sessionDrainPercentPerHour = drainPerHour,
            sessionElapsedMs = elapsed,
        )
    }
}
