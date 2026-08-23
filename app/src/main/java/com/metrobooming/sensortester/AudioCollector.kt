package com.metrobooming.sensortester

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
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
    val clientSilenced: Boolean?,
    val audioSessionId: Int?,
    val audioSource: String,
    val readCount: Long,
    val samplesRead: Long,
    val zeroSampleRatio: Double?,
    val lastReadResult: Int?,
    val readError: String,
    val activeRecordingConfigCount: Int,
    val routeChangeCount: Int,
    val restartReason: String,
)

class AudioCollector(context: Context) {
    companion object {
        const val QUALITY_NORMAL = MicQualityMonitor.QUALITY_NORMAL
        const val QUALITY_ZERO_ABNORMAL = MicQualityMonitor.QUALITY_ZERO_ABNORMAL
        const val QUALITY_RECOVERING = MicQualityMonitor.QUALITY_RECOVERING

        const val QUALITY_SYSTEM_SILENCED = "系统策略静音"
        private const val SAMPLE_RATE = 16_000
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
    private var sourceMode = AudioSourceMode.UNPROCESSED
    private var latestClientSilenced: Boolean? = null
    private var readCount = 0L
    private var zeroSampleCount = 0L
    private var lastReadResult: Int? = null
    private var readError = "NONE"
    private var routeChangeCount = 0
    private var routedDeviceId: Int? = null
    private var restartReason = "initial-start"

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
            sourceMode = AudioSourceMode.UNPROCESSED
            latestClientSilenced = null
            readCount = 0L
            zeroSampleCount = 0L
            lastReadResult = null
            readError = "NONE"
            routeChangeCount = 0
            routedDeviceId = null
            restartReason = "initial-start"
            deviceName = "正在启动"
        }
        createAndStartRecorder()
    }

    fun takeSnapshot(now: Long): AudioSnapshot = synchronized(lock) {
        val rms = if (sampleCount > 0) sqrt(squareSum / sampleCount) else 0.0
        latestQuality = qualityMonitor.update(rms, sampleCount > 0, now)
        val current = recorder
        val activeConfiguration = activeRecordingConfiguration(current)
        latestClientSilenced = clientSilenced(activeConfiguration)
        val state = audioRecordState()
        val quality = if (latestClientSilenced == true) {
            QUALITY_SYSTEM_SILENCED
        } else {
            latestQuality.quality
        }
        val result = AudioSnapshot(
            active = state == "RECORDING",
            valid = latestQuality.valid,
            rms = rms,
            peak = peak,
            inputDevice = deviceName,
            zeroDurationMs = latestQuality.zeroDurationMs,
            restartCount = restartCount,
            quality = quality,
            audioRecordState = state,
            clientSilenced = latestClientSilenced,
            audioSessionId = current?.audioSessionId,
            audioSource = current?.let { audioSourceName(it.audioSource) } ?: sourceMode.name,
            readCount = readCount,
            samplesRead = sampleCount,
            zeroSampleRatio = if (sampleCount > 0) zeroSampleCount.toDouble() / sampleCount else null,
            lastReadResult = lastReadResult,
            readError = readError,
            activeRecordingConfigCount = activeRecordingConfigCount(),
            routeChangeCount = routeChangeCount,
            restartReason = restartReason,
        )
        squareSum = 0.0
        sampleCount = 0L
        peak = 0.0
        readCount = 0L
        zeroSampleCount = 0L
        lastReadResult = null
        readError = "NONE"
        result
    }

    @SuppressLint("MissingPermission")
    fun restartIfNeeded(now: Long): Boolean {
        val shouldRestart = synchronized(lock) {
            collecting && AudioRecoveryPolicy.shouldRestart(
                recorderMissing = recorder == null,
                zeroAbnormal = latestQuality.quality == QUALITY_ZERO_ABNORMAL,
                zeroDurationMs = latestQuality.zeroDurationMs,
                clientSilenced = latestClientSilenced,
                elapsedSinceRestartMs = now - lastRestartAt,
            )
        }
        if (!shouldRestart) return false

        return synchronized(lifecycleLock) {
            if (!collecting) return@synchronized false
            synchronized(lock) {
                val zeroFailure = recorder != null && latestQuality.quality == QUALITY_ZERO_ABNORMAL
                if (zeroFailure) {
                    sourceMode = AudioRecoveryPolicy.sourceAfterZeroFailure(
                        sourceMode,
                        latestClientSilenced,
                    )
                }
                restartCount++
                lastRestartAt = now
                restartReason = if (zeroFailure) {
                    "zero-pcm-${sourceMode.name.lowercase()}"
                } else {
                    "recorder-unavailable"
                }
                squareSum = 0.0
                sampleCount = 0L
                peak = 0.0
                readCount = 0L
                zeroSampleCount = 0L
                lastReadResult = null
                readError = "NONE"
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
            readCount = 0L
            zeroSampleCount = 0L
            lastReadResult = null
            readError = "NONE"
            latestClientSilenced = null
            routedDeviceId = null
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

        val requestedSource = sourceMode.toAudioSource()
        val audioRecord = createRecorder(requestedSource, minimum) ?: run {
            if (sourceMode == AudioSourceMode.UNPROCESSED) {
                sourceMode = AudioSourceMode.MIC
                createRecorder(MediaRecorder.AudioSource.MIC, minimum)
            } else {
                null
            }
        } ?: return false
        return try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.release()
                false
            } else {
                recorder = audioRecord
                synchronized(lock) {
                    updateRoutedDeviceLocked(audioRecord)
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
            } catch (error: Exception) {
                synchronized(lock) {
                    readCount++
                    lastReadResult = null
                    readError = "EXCEPTION:${error.javaClass.simpleName}"
                }
                if (recorder === audioRecord) recorder = null
                stopAndRelease(audioRecord)
                break
            }
            synchronized(lock) {
                readCount++
                lastReadResult = count
                if (count <= 0) readError = readResultName(count)
            }
            if (count <= 0) {
                if (
                    count == AudioRecord.ERROR_DEAD_OBJECT ||
                    count == AudioRecord.ERROR_INVALID_OPERATION ||
                    count == AudioRecord.ERROR_BAD_VALUE ||
                    count == AudioRecord.ERROR
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
                    if (buffer[i].toInt() == 0) zeroSampleCount++
                }
                updateRoutedDeviceLocked(audioRecord)
            }
        }
    }


    private fun activeRecordingConfiguration(
        audioRecord: AudioRecord?,
    ): AudioRecordingConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            audioRecord?.activeRecordingConfiguration
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun clientSilenced(configuration: AudioRecordingConfiguration?): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return configuration?.isClientSilenced
    }

    private fun activeRecordingConfigCount(): Int = try {
        audioManager.activeRecordingConfigurations.size
    } catch (_: SecurityException) {
        -1
    }

    private fun updateRoutedDeviceLocked(audioRecord: AudioRecord) {
        val routed = audioRecord.routedDevice
        val nextId = routed?.id
        if (
            routedDeviceId != null &&
            nextId != null &&
            routedDeviceId != nextId
        ) {
            routeChangeCount++
        }
        routedDeviceId = nextId
        deviceName = routed?.productName?.toString()
            ?.let { "$it (${deviceTypeName(routed.type)})" }
            ?: "系统默认输入"
    }

    private fun AudioSourceMode.toAudioSource(): Int = when (this) {
        AudioSourceMode.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        AudioSourceMode.MIC -> MediaRecorder.AudioSource.MIC
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> AudioSourceMode.UNPROCESSED.name
        MediaRecorder.AudioSource.MIC -> AudioSourceMode.MIC.name
        else -> "SOURCE_$source"
    }

    private fun readResultName(result: Int): String = when (result) {
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR -> "ERROR"
        0 -> "NO_DATA"
        else -> "CODE_$result"
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
