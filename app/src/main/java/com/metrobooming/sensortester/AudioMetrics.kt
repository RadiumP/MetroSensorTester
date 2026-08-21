package com.metrobooming.sensortester

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class AudioMetrics {
    data class Window(val rms: Double, val peak: Double, val hasSamples: Boolean)

    private val lock = Any()
    private var squareSum = 0.0
    private var sampleCount = 0L
    private var peak = 0.0

    fun add(buffer: ShortArray, count: Int) = synchronized(lock) {
        for (i in 0 until count) {
            val normalized = buffer[i] / 32768.0
            squareSum += normalized * normalized
            sampleCount++
            peak = max(peak, abs(normalized))
        }
    }

    fun clear() = synchronized(lock) {
        squareSum = 0.0
        sampleCount = 0L
        peak = 0.0
    }

    fun take(): Window = synchronized(lock) {
        val window = Window(
            rms = if (sampleCount > 0) sqrt(squareSum / sampleCount) else 0.0,
            peak = peak,
            hasSamples = sampleCount > 0,
        )
        squareSum = 0.0
        sampleCount = 0L
        peak = 0.0
        window
    }
}
