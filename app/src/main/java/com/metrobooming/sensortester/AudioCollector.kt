package com.metrobooming.sensortester

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.concurrent.thread
import kotlin.math.sqrt

data class AudioSnapshot(
    val active: Boolean,
    val valid: Boolean,
    val rms: Double,
    val peak: Double,
    val inputDevice: String,
)

class AudioCollector {
    private val sampleRate = 16_000
    private val lock = Any()
    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var squareSum = 0.0
    private var sampleCount = 0L
    private var peak = 0.0
    private var deviceName = "未启动"

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return false
        val audioRecord = createRecorder(MediaRecorder.AudioSource.UNPROCESSED, minimum)
            ?: createRecorder(MediaRecorder.AudioSource.MIC, minimum)
            ?: return false
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }
        recorder = audioRecord
        running = true
        audioRecord.startRecording()
        thread(name = "metro-audio", isDaemon = true) { readLoop(audioRecord) }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(source: Int, minimum: Int): AudioRecord? = try {
        val candidate = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, 4096),
        )
        if (candidate.state == AudioRecord.STATE_INITIALIZED) candidate
        else {
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
        while (running) {
            val count = audioRecord.read(buffer, 0, buffer.size)
            if (count <= 0) continue
            synchronized(lock) {
                for (i in 0 until count) {
                    val normalized = buffer[i] / 32768.0
                    squareSum += normalized * normalized
                    sampleCount++
                    peak = maxOf(peak, kotlin.math.abs(normalized))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val routed = audioRecord.routedDevice
                    deviceName = routed?.productName?.toString()
                        ?.let { "$it (${deviceTypeName(routed.type)})" }
                        ?: "系统默认输入"
                }
            }
        }
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

    fun takeSnapshot(): AudioSnapshot = synchronized(lock) {
        val rms = if (sampleCount > 0) sqrt(squareSum / sampleCount) else 0.0
        val result = AudioSnapshot(
            active = running,
            valid = running && sampleCount > 0,
            rms = rms,
            peak = peak,
            inputDevice = deviceName,
        )
        squareSum = 0.0
        sampleCount = 0
        peak = 0.0
        result
    }

    fun stop() {
        running = false
        val current = recorder
        recorder = null
        try {
            current?.stop()
        } catch (_: Exception) {
        }
        current?.release()
    }
}
