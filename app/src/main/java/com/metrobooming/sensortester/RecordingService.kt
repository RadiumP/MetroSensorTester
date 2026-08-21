package com.metrobooming.sensortester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.time.Instant
import java.util.Locale
import kotlin.math.PI

data class RecordingStatus(
    val recording: Boolean = false,
    val startedAt: Long = 0L,
    val rowCount: Int = 0,
    val currentMark: String = "",
    val segmentId: Int = 0,
    val sensor: SensorSnapshot? = null,
    val audio: AudioSnapshot? = null,
    val inference: InferenceResult? = null,
    val appInForeground: Boolean = false,
    val screenOn: Boolean = true,
    val wakeLockHeld: Boolean = false,
    val foregroundServiceActive: Boolean = false,
)

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.metrobooming.sensortester.action.START"
        const val ACTION_STOP = "com.metrobooming.sensortester.action.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "metro_sensor_recording"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 250L
        private const val NOTIFICATION_UPDATE_MS = 5_000L

        val headers = listOf(
            "ts", "elapsed_ms", "accel_rms", "accel_peak",
            "gyro_rms_deg_s", "gyro_peak_deg_s", "gyro_x_rad_s", "gyro_y_rad_s", "gyro_z_rad_s",
            "magnet_active", "magnet_x_ut", "magnet_y_ut", "magnet_z_ut", "magnet_magnitude_ut",
            "pressure_active", "pressure_hpa",
            "mic_active", "mic_valid", "mic_rms", "mic_peak", "mic_input_device",
            "state_schema_version", "raw_state", "state", "raw_train_state", "train_state",
            "player_state", "is_train_moving", "is_player_active",
            "mic_level_ratio", "mic_stop_rms_threshold", "mic_moving_rms_threshold",
            "mic_above_moving_threshold", "mic_below_stop_threshold",
            "stop_confirmation_ms", "stop_candidate_elapsed_ms", "decision_reason",
            "mic_zero_duration_ms", "mic_restart_count", "mic_quality",
            "mic_fixed_stop_threshold", "mic_fixed_moving_threshold",
            "mic_dynamic_stop_threshold", "mic_dynamic_moving_threshold",
            "mic_effective_stop_threshold", "mic_effective_moving_threshold",
            "mic_threshold_mode", "mic_p25", "mic_p70", "mic_valid_history_size",
            "moving_confirmation_ms", "moving_candidate_elapsed_ms",
            "app_in_foreground", "screen_on", "wake_lock_held",
            "audio_record_state", "foreground_service_active",
            "mark", "segment_id", "device_model", "android_version",
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = AudioCollector()
    private val inference = InferenceEngine()
    private val rows = mutableListOf<List<Any?>>()
    private val rowLock = Any()

    private lateinit var sensors: SensorCollector
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var recording = false

    @Volatile
    private var appInForeground = false

    @Volatile
    private var latestStatus = RecordingStatus()

    private var startedAt = 0L
    private var currentMark = ""
    private var segmentId = 0
    private var foregroundServiceActive = false
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        sensors = SensorCollector(this)
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        appInForeground = false
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
        return if (recording) START_STICKY else START_NOT_STICKY
    }

    fun getStatus(): RecordingStatus = latestStatus

    fun capabilities(): List<SensorCapability> = sensors.capabilities()

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        latestStatus = latestStatus.copy(appInForeground = inForeground)
    }

    fun setMark(mark: String) {
        if (mark != currentMark) segmentId++
        currentMark = mark
        latestStatus = latestStatus.copy(currentMark = currentMark, segmentId = segmentId)
    }

    fun buildCsv(): String = synchronized(rowLock) {
        buildString {
            appendLine(headers.joinToString(","))
            rows.forEach { row -> appendLine(row.joinToString(",") { csvEscape(it) }) }
        }
    }

    fun stopRecording() {
        if (recording) {
            recording = false
            handler.removeCallbacks(tick)
            sensors.stop()
            audio.stop()
            releaseWakeLock()
        }
        foregroundServiceActive = false
        latestStatus = latestStatus.copy(
            recording = false,
            wakeLockHeld = false,
            foregroundServiceActive = false,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recording) {
            recording = false
            handler.removeCallbacks(tick)
            sensors.stop()
            audio.stop()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun beginRecording() {
        if (recording) return
        synchronized(rowLock) { rows.clear() }
        inference.reset()
        currentMark = ""
        segmentId = 0
        startedAt = System.currentTimeMillis()
        lastNotificationAt = 0L
        acquireWakeLock()
        sensors.start()
        audio.start()
        recording = true
        latestStatus = RecordingStatus(
            recording = true,
            startedAt = startedAt,
            appInForeground = appInForeground,
            screenOn = powerManager.isInteractive,
            wakeLockHeld = wakeLock?.isHeld == true,
            foregroundServiceActive = foregroundServiceActive,
        )
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val now = System.currentTimeMillis()
            val sensor = sensors.takeSnapshot()
            val mic = audio.takeSnapshot(now)
            audio.restartIfNeeded(now)
            val gyroRmsDeg = sensor.gyroRms * 180.0 / PI
            val gyroPeakDeg = sensor.gyroPeak * 180.0 / PI
            val inferred = inference.update(
                mic.rms,
                mic.valid,
                sensor.accelRms,
                gyroRmsDeg,
                now,
            )
            val screenOn = powerManager.isInteractive
            val wakeLockHeld = wakeLock?.isHeld == true
            val row = listOf(
                Instant.ofEpochMilli(now).toString(), now - startedAt,
                sensor.accelRms, sensor.accelPeak,
                gyroRmsDeg, gyroPeakDeg, sensor.gyroX, sensor.gyroY, sensor.gyroZ,
                if (sensor.magnetMagnitude != null) 1 else 0,
                sensor.magnetX, sensor.magnetY, sensor.magnetZ, sensor.magnetMagnitude,
                if (sensor.pressureHpa != null) 1 else 0, sensor.pressureHpa,
                if (mic.active) 1 else 0, if (mic.valid) 1 else 0,
                mic.rms, mic.peak, mic.inputDevice,
                3, inferred.rawState, inferred.state,
                inferred.rawTrainState, inferred.trainState,
                inferred.playerState,
                if (inferred.trainState == "运行") 1 else 0,
                if (inferred.playerActive) 1 else 0,
                inferred.micLevelRatio,
                InferenceEngine.MIC_STOP_RMS_THRESHOLD,
                InferenceEngine.MIC_MOVING_RMS_THRESHOLD,
                if (inferred.micAboveMovingThreshold) 1 else 0,
                if (inferred.micBelowStopThreshold) 1 else 0,
                InferenceEngine.STOP_CONFIRMATION_MS,
                inferred.stopCandidateElapsedMs, inferred.reason,
                mic.zeroDurationMs, mic.restartCount, mic.quality,
                inferred.fixedStopThreshold, inferred.fixedMovingThreshold,
                inferred.dynamicStopThreshold, inferred.dynamicMovingThreshold,
                inferred.effectiveStopThreshold, inferred.effectiveMovingThreshold,
                inferred.thresholdMode, inferred.micP25, inferred.micP70,
                inferred.validMicSampleCount,
                InferenceEngine.MOVING_CONFIRMATION_MS,
                inferred.movingCandidateElapsedMs,
                if (appInForeground) 1 else 0,
                if (screenOn) 1 else 0,
                if (wakeLockHeld) 1 else 0,
                mic.audioRecordState,
                if (foregroundServiceActive) 1 else 0,
                currentMark, segmentId,
                Build.MODEL, Build.VERSION.RELEASE,
            )
            val rowCount = synchronized(rowLock) {
                rows += row
                rows.size
            }
            latestStatus = RecordingStatus(
                recording = true,
                startedAt = startedAt,
                rowCount = rowCount,
                currentMark = currentMark,
                segmentId = segmentId,
                sensor = sensor,
                audio = mic,
                inference = inferred,
                appInForeground = appInForeground,
                screenOn = screenOn,
                wakeLockHeld = wakeLockHeld,
                foregroundServiceActive = foregroundServiceActive,
            )
            if (now - lastNotificationAt >= NOTIFICATION_UPDATE_MS) {
                updateNotification(inferred.trainState, inferred.playerState)
                lastNotificationAt = now
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun ensureForeground() {
        if (foregroundServiceActive) return
        val notification = buildNotification("正在准备采集")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundServiceActive = true
    }

    private fun updateNotification(trainState: String, playerState: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification("列车：$trainState · 玩家：$playerState"),
        )
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Metro Sensor Tester 正在采集")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "传感器后台采集",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持地铁传感器和麦克风采集运行"
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:MetroSensorRecording",
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun csvEscape(value: Any?): String {
        val string = value?.toString().orEmpty().replace("\"", "\"\"")
        return "\"$string\""
    }
}
