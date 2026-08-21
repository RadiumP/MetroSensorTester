package com.metrobooming.sensortester

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioSnapshot(
    val active: Boolean,
    val valid: Boolean,
    val rms: Double,
    val peak: Double,
    val inputDevice: String,
    val zeroDurationMs: Long,
    val restartCount: Int,
    val quality: String,
    val audioRecordState: String,
)

class AudioCollector {
    companion object {
        const val QUALITY_NORMAL = MicQualityMonitor.QUALITY_NORMAL
        const val QUALITY_ZERO_ABNORMAL = MicQualityMonitor.QUALITY_ZERO_ABNORMAL
        const val QUALITY_RECOVERING = MicQualityMonitor.QUALITY_RECOVERING

        private const val SAMPLE_RATE = 16_000
        private const val RESTART_AFTER_ZERO_MS = 5_000L
        private const val MIN_RESTART_INTERVAL_MS = 15_000L
    }

    private val lock = Any()
    private val lifecycleLock = Any()

    @Volatile
    private var collecting = false

    @Volatile
    private var recorder: AudioRecord? = null

    private var squareSum = 0.0
    private var sampleCount = 0L
    private var peak = 0.0
    private var deviceName = "未启动"
    private val qualityMonitor = MicQualityMonitor()
    private var latestQuality = MicQualityResult(false, QUALITY_RECOVERING, 0L)
    private var restartCount = 0
    private var lastRestartAt = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean = synchronized(lifecycleLock) {
        if (collecting && recorder != null) return true
        collecting = true
        synchronized(lock) {
            squareSum = 0.0
            sampleCount = 0L
            peak = 0.0
            latestQuality = qualityMonitor.reset()
            restartCount = 0
            lastRestartAt = System.currentTimeMillis()
            deviceName = "正在启动"
        }
        createAndStartRecorder()
    }

    fun takeSnapshot(now: Long): AudioSnapshot = synchronized(lock) {
        val rms = if (sampleCount > 0) sqrt(squareSum / sampleCount) else 0.0
        latestQuality = qualityMonitor.update(rms, sampleCount > 0, now)
        val state = audioRecordState()
        val result = AudioSnapshot(
            active = state == "RECORDING",
            valid = latestQuality.valid,
            rms = rms,
            peak = peak,
            inputDevice = deviceName,
            zeroDurationMs = latestQuality.zeroDurationMs,
            restartCount = restartCount,
            quality = latestQuality.quality,
            audioRecordState = state,
        )
        squareSum = 0.0
        sampleCount = 0L
        peak = 0.0
        result
    }

    @SuppressLint("MissingPermission")
    fun restartIfNeeded(now: Long): Boolean {
        val shouldRestart = synchronized(lock) {
            collecting &&
                (
                    recorder == null ||
                        (
                            latestQuality.quality == QUALITY_ZERO_ABNORMAL &&
                                latestQuality.zeroDurationMs >= RESTART_AFTER_ZERO_MS
                            )
                    ) &&
                now - lastRestartAt >= MIN_RESTART_INTERVAL_MS
        }
        if (!shouldRestart) return false

        return synchronized(lifecycleLock) {
            if (!collecting) return@synchronized false
            synchronized(lock) {
                restartCount++
                lastRestartAt = now
                squareSum = 0.0
                sampleCount = 0L
                peak = 0.0
            }
            val old = recorder
            recorder = null
            stopAndRelease(old)
            val restarted = createAndStartRecorder()
            if (!restarted) {
                synchronized(lock) {
                    deviceName = "AudioRecord 重启失败"
                }
            }
            restarted
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        collecting = false
        val current = recorder
        recorder = null
        stopAndRelease(current)
        synchronized(lock) {
            squareSum = 0.0
            sampleCount = 0L
            peak = 0.0
            deviceName = "已停止"
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAndStartRecorder(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return false

        val audioRecord = createRecorder(MediaRecorder.AudioSource.UNPROCESSED, minimum)
            ?: createRecorder(MediaRecorder.AudioSource.MIC, minimum)
            ?: return false
        return try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.release()
                false
            } else {
                recorder = audioRecord
                synchronized(lock) {
                    deviceName = routedDeviceName(audioRecord)
                }
                thread(name = "metro-audio", isDaemon = true) { readLoop(audioRecord) }
                true
            }
        } catch (_: IllegalStateException) {
            audioRecord.release()
            false
        } catch (_: SecurityException) {
            audioRecord.release()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(source: Int, minimum: Int): AudioRecord? = try {
        val candidate = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, 4096),
        )
        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
            candidate
        } else {
            candidate.release()
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readLoop(audioRecord: AudioRecord) {
        val buffer = ShortArray(2048)
        while (collecting && recorder === audioRecord) {
            val count = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (_: Exception) {
                break
            }
            if (count <= 0) {
                if (
                    count == AudioRecord.ERROR_DEAD_OBJECT ||
                    count == AudioRecord.ERROR_INVALID_OPERATION ||
                    count == AudioRecord.ERROR_BAD_VALUE
                ) {
                    if (recorder === audioRecord) {
                        recorder = null
                        stopAndRelease(audioRecord)
                    }
                    break
                }
                continue
            }
            synchronized(lock) {
                for (i in 0 until count) {
                    val normalized = buffer[i] / 32768.0
                    squareSum += normalized * normalized
                    sampleCount++
                    peak = maxOf(peak, abs(normalized))
                }
                deviceName = routedDeviceName(audioRecord)
            }
        }
    }

    private fun routedDeviceName(audioRecord: AudioRecord): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "系统默认输入"
        val routed = audioRecord.routedDevice
        return routed?.productName?.toString()
            ?.let { "$it (${deviceTypeName(routed.type)})" }
            ?: "系统默认输入"
    }

    private fun audioRecordState(): String {
        val current = recorder ?: return if (collecting) "UNAVAILABLE" else "STOPPED"
        return when {
            current.state != AudioRecord.STATE_INITIALIZED -> "UNINITIALIZED"
            current.recordingState == AudioRecord.RECORDSTATE_RECORDING -> "RECORDING"
            current.recordingState == AudioRecord.RECORDSTATE_STOPPED -> "STOPPED"
            else -> "INITIALIZED"
        }
    }

    private fun stopAndRelease(audioRecord: AudioRecord?) {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        else -> "类型$type"
    }
}
