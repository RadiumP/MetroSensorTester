package com.metrobooming.sensortester

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.metrobooming.sensortester.action.START"
        const val ACTION_STOP = "com.metrobooming.sensortester.action.STOP"

        private const val TICK_MS = 250L
        private const val NOTIFICATION_UPDATE_MS = 5_000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = AudioCollector(this)

    private lateinit var sensors: SensorCollector
    private lateinit var powerManager: PowerManager
    private lateinit var notifications: RecordingNotifications
    private lateinit var wakeLocks: WakeLockOwner
    private lateinit var session: RecordingSession

    private var foregroundServiceActive = false
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        sensors = SensorCollector(this)
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        notifications = RecordingNotifications(this, MainActivity::class.java)
        wakeLocks = WakeLockOwner(this)
        session = RecordingSession(
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
        )
        notifications.createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        session.setAppInForeground(false)
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> {
                ensureForeground()
                beginRecording()
            }
        }
        return if (session.isRecording) START_STICKY else START_NOT_STICKY
    }

    fun getStatus(): RecordingStatus = session.latest

    fun capabilities(): List<SensorCapability> = sensors.capabilities()

    fun setAppInForeground(inForeground: Boolean) = session.setAppInForeground(inForeground)

    fun setMark(mark: String) = session.setMark(mark)

    fun buildCsv(): String = session.buildCsv()

    fun stopRecording() {
        if (session.isRecording) {
            handler.removeCallbacks(tick)
            sensors.stop()
            audio.stop()
            wakeLocks.release()
        }
        foregroundServiceActive = false
        session.updateRuntimeFlags(foregroundServiceActive = false)
        session.stopTracking()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (session.isRecording) {
            handler.removeCallbacks(tick)
            sensors.stop()
            audio.stop()
        }
        wakeLocks.release()
        super.onDestroy()
    }

    private fun beginRecording() {
        if (session.isRecording) return
        sensors.start()
        audio.start()
        wakeLocks.acquire()
        lastNotificationAt = 0L
        session.updateRuntimeFlags(
            screenOn = powerManager.isInteractive,
            wakeLockHeld = wakeLocks.isHeld,
            foregroundServiceActive = foregroundServiceActive,
        )
        session.start()
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!session.isRecording) return
            val now = System.currentTimeMillis()
            val sensor = sensors.takeSnapshot()
            val mic = audio.takeSnapshot(now)
            audio.restartIfNeeded(now)
            session.updateRuntimeFlags(
                screenOn = powerManager.isInteractive,
                wakeLockHeld = wakeLocks.isHeld,
                foregroundServiceActive = foregroundServiceActive,
            )
            val status = session.tick(sensor, mic)
            if (now - lastNotificationAt >= NOTIFICATION_UPDATE_MS) {
                notifications.update(
                    status.inference?.trainState?.label.orEmpty(),
                    status.inference?.playerState?.label.orEmpty(),
                )
                lastNotificationAt = now
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun ensureForeground() {
        if (foregroundServiceActive) return
        val notification = notifications.build("正在准备采集")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RecordingNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(RecordingNotifications.NOTIFICATION_ID, notification)
        }
        foregroundServiceActive = true
    }
}
