package com.metrobooming.sensortester

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

data class RuntimeFlags(
    val screenOn: Boolean = true,
    val wakeLockHeld: Boolean = false,
    val foregroundServiceActive: Boolean = false,
)

class RecordingSession(
    private val inference: InferenceEngine = InferenceEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceModel: String = "",
    private val androidVersion: String = "",
) {
    private val rowLock = Any()
    private val rows = mutableListOf<List<Any?>>()

    private var startedAt = 0L
    private var currentMark = ""
    private var segmentId = 0
    private var flags = RuntimeFlags()

    @Volatile
    private var appInForeground = false

    @Volatile
    var latest: RecordingStatus = RecordingStatus()
        private set

    val isRecording: Boolean get() = latest.recording

    fun stopTracking() {
        latest = latest.copy(
            recording = false,
            wakeLockHeld = false,
            foregroundServiceActive = false,
        )
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        latest = latest.copy(appInForeground = inForeground)
    }

    fun updateRuntimeFlags(
        screenOn: Boolean = flags.screenOn,
        wakeLockHeld: Boolean = flags.wakeLockHeld,
        foregroundServiceActive: Boolean = flags.foregroundServiceActive,
    ) {
        flags = RuntimeFlags(screenOn, wakeLockHeld, foregroundServiceActive)
    }

    fun setMark(mark: String) {
        if (mark != currentMark) segmentId++
        currentMark = mark
        latest = latest.copy(currentMark = currentMark, segmentId = segmentId)
    }

    fun start() {
        synchronized(rowLock) { rows.clear() }
        inference.reset()
        currentMark = ""
        segmentId = 0
        startedAt = clock()
        latest = RecordingStatus(
            recording = true,
            startedAt = startedAt,
            appInForeground = appInForeground,
            screenOn = flags.screenOn,
            wakeLockHeld = flags.wakeLockHeld,
            foregroundServiceActive = flags.foregroundServiceActive,
        )
    }

    fun tick(sensor: SensorSnapshot, audio: AudioSnapshot): RecordingStatus {
        val now = clock()
        val result = inference.update(
            micRms = audio.rms,
            micValid = audio.valid,
            accelRms = sensor.accelRms,
            gyroDegreesRms = sensor.gyroRms * 180.0 / PI,
            now = now,
        )
        val row = CsvSchema.buildRow(
            CsvRowContext(
                nowMs = now,
                startedAtMs = startedAt,
                sensor = sensor,
                audio = audio,
                inference = result,
                mark = currentMark,
                segmentId = segmentId,
                appInForeground = appInForeground,
                screenOn = flags.screenOn,
                wakeLockHeld = flags.wakeLockHeld,
                foregroundServiceActive = flags.foregroundServiceActive,
                deviceModel = deviceModel,
                androidVersion = androidVersion,
            ),
        )
        val rowCount = synchronized(rowLock) {
            rows += row
            rows.size
        }
        return RecordingStatus(
            recording = true,
            startedAt = startedAt,
            rowCount = rowCount,
            currentMark = currentMark,
            segmentId = segmentId,
            sensor = sensor,
            audio = audio,
            inference = result,
            appInForeground = appInForeground,
            screenOn = flags.screenOn,
            wakeLockHeld = flags.wakeLockHeld,
            foregroundServiceActive = flags.foregroundServiceActive,
        ).also { latest = it }
    }

    fun buildCsv(): String = synchronized(rowLock) {
        buildString {
            appendLine(CsvSchema.headers.joinToString(","))
            rows.forEach { row -> appendLine(row.joinToString(",") { CsvSchema.escape(it) }) }
        }
    }
}
