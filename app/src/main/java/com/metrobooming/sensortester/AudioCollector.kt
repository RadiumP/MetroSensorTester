package com.metrobooming.sensortester

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread

data class AudioSnapshot(
    val active: Boolean,
    val valid: Boolean,
    val rms: Double,
    val peak: Double,
    val inputDevice: String,
    val zeroDurationMs: Long,
    val restartCount: Int,
    val quality: MicQuality,
    val audioRecordState: String,
)

class AudioCollector(context: Context) {
    companion object {
        private const val TAG = "AudioCollector"
        private const val SAMPLE_RATE = 16_000
        private const val RESTART_AFTER_ZERO_MS = 5_000L
        private const val MIN_RESTART_INTERVAL_MS = 15_000L
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val metrics = AudioMetrics()
    private val qualityMonitor = MicQualityMonitor()

    private val lifecycleLock = Any()

    @Volatile
    private var collecting = false

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var routeName = "未启动"

    @Volatile
    private var latestQuality = MicQualityResult(false, MicQuality.RECOVERING, 0L)

    private var restartCount = 0
    private var lastRestartAt = 0L
    private var audioDeviceCallback: AudioDeviceCallback? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean = synchronized(lifecycleLock) {
        if (collecting && recorder != null) return true
        collecting = true
        metrics.clear()
        latestQuality = qualityMonitor.reset()
        restartCount = 0
        lastRestartAt = System.currentTimeMillis()
        routeName = "正在启动"
        registerRouteListener()
        createAndStartRecorder()
    }

    fun takeSnapshot(now: Long): AudioSnapshot {
        val window = metrics.take()
        latestQuality = qualityMonitor.update(window.rms, window.hasSamples, now)
        val state = audioRecordState()
        return AudioSnapshot(
            active = state == "RECORDING",
            valid = latestQuality.valid,
            rms = window.rms,
            peak = window.peak,
            inputDevice = routeName,
            zeroDurationMs = latestQuality.zeroDurationMs,
            restartCount = restartCount,
            quality = latestQuality.quality,
            audioRecordState = state,
        )
    }

    @SuppressLint("MissingPermission")
    fun restartIfNeeded(now: Long): Boolean {
        if (!restartDue(now)) return false

        return synchronized(lifecycleLock) {
            if (!collecting) return@synchronized false
            restartCount++
            lastRestartAt = now
            metrics.clear()
            val old = recorder
            recorder = null
            stopAndRelease(old)
            val restarted = createAndStartRecorder()
            if (!restarted) {
                routeName = "AudioRecord 重启失败"
                Log.w(TAG, "AudioRecord restart failed")
            }
            restarted
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        collecting = false
        unregisterRouteListener()
        val current = recorder
        recorder = null
        stopAndRelease(current)
        metrics.clear()
        routeName = "已停止"
    }

    private fun restartDue(now: Long): Boolean =
        collecting &&
            (
                recorder == null ||
                    (
                        latestQuality.quality == MicQuality.ZERO_ABNORMAL &&
                            latestQuality.zeroDurationMs >= RESTART_AFTER_ZERO_MS
                        )
                ) &&
            now - lastRestartAt >= MIN_RESTART_INTERVAL_MS

    @SuppressLint("MissingPermission")
    private fun createAndStartRecorder(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            Log.w(TAG, "AudioRecord min buffer size invalid: $minimum")
            return false
        }

        val audioRecord = createRecorder(MediaRecorder.AudioSource.UNPROCESSED, minimum)
            ?: createRecorder(MediaRecorder.AudioSource.MIC, minimum)
            ?: run {
                Log.w(TAG, "AudioRecord creation failed for UNPROCESSED and MIC sources")
                return false
            }
        return try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.release()
                Log.w(TAG, "AudioRecord did not enter recording state")
                false
            } else {
                recorder = audioRecord
                refreshRouteName(audioRecord)
                thread(name = "metro-audio", isDaemon = true) { readLoop(audioRecord) }
                true
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord startRecording failed", e)
            audioRecord.release()
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "AudioRecord startRecording denied", e)
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
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord read threw", e)
                break
            }
            if (count <= 0) {
                if (
                    count == AudioRecord.ERROR_DEAD_OBJECT ||
                    count == AudioRecord.ERROR_INVALID_OPERATION ||
                    count == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.w(TAG, "AudioRecord read failed with code $count")
                    if (recorder === audioRecord) {
                        recorder = null
                        stopAndRelease(audioRecord)
                    }
                    break
                }
                continue
            }
            metrics.add(buffer, count)
        }
    }

    private fun registerRouteListener() {
        unregisterRouteListener()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                recorder?.let(::refreshRouteName)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                recorder?.let(::refreshRouteName)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        audioDeviceCallback = callback
    }

    private fun unregisterRouteListener() {
        val callback = audioDeviceCallback ?: return
        audioManager.unregisterAudioDeviceCallback(callback)
        audioDeviceCallback = null
    }

    private fun refreshRouteName(audioRecord: AudioRecord) {
        routeName = routedDeviceName(audioRecord)
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
