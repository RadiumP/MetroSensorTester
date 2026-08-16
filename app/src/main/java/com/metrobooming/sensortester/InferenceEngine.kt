package com.metrobooming.sensortester

import kotlin.math.max

data class InferenceResult(
    val state: String,
    val rawState: String,
    val intensity: Double,
    val movingGuard: Boolean,
    val stopSignalsLow: Boolean,
    val phoneMotionRejected: Boolean,
    val reason: String,
)

class InferenceEngine {
    private val micHistory = ArrayDeque<Double>()
    private val accelHistory = ArrayDeque<Double>()
    private var smoothed: Double? = null
    private var stableState = "校准中"
    private var candidateState = ""
    private var candidateSince = 0L

    fun reset() {
        micHistory.clear()
        accelHistory.clear()
        smoothed = null
        stableState = "校准中"
        candidateState = ""
        candidateSince = 0L
    }

    fun update(
        micRms: Double,
        micValid: Boolean,
        accelRms: Double,
        gyroDegreesRms: Double,
        now: Long,
        startedAt: Long,
    ): InferenceResult {
        val phoneMotionRejected = gyroDegreesRms > 45.0
        if (micValid) push(micHistory, micRms)
        if (!phoneMotionRejected) push(accelHistory, accelRms)

        if (now - startedAt < 10_000L || micHistory.size < 40 || accelHistory.size < 40) {
            return InferenceResult(
                "校准中", "校准中", 0.0, false, false,
                phoneMotionRejected, "calibrating"
            )
        }

        val accelForInference = if (phoneMotionRejected) percentile(accelHistory, 0.5) else accelRms
        val sound = normalize(micRms, percentile(micHistory, 0.2), percentile(micHistory, 0.8))
        val vibration = normalize(
            accelForInference,
            percentile(accelHistory, 0.2),
            percentile(accelHistory, 0.8),
        )
        val rawIntensity = 0.72 * sound + 0.28 * vibration
        smoothed = smoothed?.let { 0.4 * rawIntensity + 0.6 * it } ?: rawIntensity
        val intensity = smoothed ?: rawIntensity

        val movingGuard = micRms > 0.060 || accelForInference > 0.40
        val stopSignalsLow = micRms < 0.055 && accelForInference < 0.35
        val stopCandidate = !movingGuard && stopSignalsLow && intensity < 0.30
        val movingCandidate = movingGuard || intensity >= 0.43

        val raw: String
        val reason: String
        if (stableState == "停站") {
            raw = if (movingCandidate) "运行" else "停站"
            reason = when {
                movingGuard -> "absolute-moving-guard"
                movingCandidate -> "exit-stop-intensity"
                else -> "hold-stop"
            }
        } else {
            raw = if (stopCandidate) "停站" else "运行"
            reason = when {
                movingGuard -> "absolute-moving-guard"
                stopCandidate -> "enter-stop-confirmed"
                else -> "hold-moving"
            }
        }

        if (raw != candidateState) {
            candidateState = raw
            candidateSince = now
        }
        val holdMs = if (raw == "停站") 8_000L else 2_500L
        if (now - candidateSince >= holdMs) stableState = raw

        return InferenceResult(
            stableState, raw, intensity, movingGuard, stopSignalsLow,
            phoneMotionRejected, reason
        )
    }

    private fun push(queue: ArrayDeque<Double>, value: Double) {
        queue.addLast(value)
        if (queue.size > 480) queue.removeFirst()
    }

    private fun percentile(queue: ArrayDeque<Double>, p: Double): Double {
        if (queue.isEmpty()) return 0.0
        val sorted = queue.sorted()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun normalize(value: Double, low: Double, high: Double): Double {
        return ((value - low) / max(high - low, 0.0001)).coerceIn(0.0, 1.0)
    }
}
