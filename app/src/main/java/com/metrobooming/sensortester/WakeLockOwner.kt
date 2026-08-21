package com.metrobooming.sensortester

import android.content.Context
import android.os.PowerManager

class WakeLockOwner(private val context: Context) {

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    val isHeld: Boolean get() = wakeLock?.isHeld == true

    @Suppress("WakelockTimeout")
    fun acquire() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${context.packageName}:MetroSensorRecording",
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
    }

    fun release() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
