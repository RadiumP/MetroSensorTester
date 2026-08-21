package com.metrobooming.sensortester

data class InferenceResult(
    val state: String,
    val rawState: String,
    val trainState: String,
    val rawTrainState: String,
    val playerState: String,
    val playerActive: Boolean,
    val micLevelRatio: Double,
    val micAboveMovingThreshold: Boolean,
    val micBelowStopThreshold: Boolean,
    val stopCandidateElapsedMs: Long,
    val movingCandidateElapsedMs: Long,
    val fixedStopThreshold: Double,
    val fixedMovingThreshold: Double,
    val dynamicStopThreshold: Double?,
    val dynamicMovingThreshold: Double?,
    val effectiveStopThreshold: Double,
    val effectiveMovingThreshold: Double,
    val thresholdMode: String,
    val micP25: Double?,
    val micP70: Double?,
    val validMicSampleCount: Int,
    val reason: String,
)

class InferenceEngine {
    companion object {
        const val MIC_NONZERO_FLOOR = 0.000001
        const val MIC_STOP_RMS_THRESHOLD = 0.0014
        const val MIC_MOVING_RMS_THRESHOLD = 0.0018
        const val STOP_CONFIRMATION_MS = 3_000L
        const val MOVING_CONFIRMATION_MS = 1_750L

        const val PLAYER_ACCEL_RMS_THRESHOLD = 0.40
        const val PLAYER_GYRO_RMS_THRESHOLD_DEG_S = 15.0

        private const val DYNAMIC_HISTORY_MS = 180_000L
        private const val MIN_DYNAMIC_SAMPLES = 240
        private const val MIN_SAMPLES_PER_STATE = 20
        private const val THRESHOLD_UPDATE_INTERVAL_MS = 5_000L
        private const val THRESHOLD_SMOOTHING = 0.08
        private const val MIN_HYSTERESIS_GAP = 0.0002
        private const val MIN_STOP_THRESHOLD = 0.0008
        private const val MAX_STOP_THRESHOLD = 0.0016
        private const val MIN_MOVING_THRESHOLD = 0.0016
        private const val MAX_MOVING_THRESHOLD = 0.0030

        private const val STATE_CALIBRATING = "校准中"
        private const val STATE_MOVING = "运行"
        private const val STATE_STOPPED = "停站"
        private const val STATE_STOPPED_PLAYER_ACTIVE = "停站但玩家活动"
        private const val PLAYER_ACTIVE = "活动"
        private const val PLAYER_STILL = "静止"
    }

    private data class MicPoint(val timestampMs: Long, val rms: Double, val trainState: String)

    private val micHistory = ArrayDeque<MicPoint>()
    private var stableTrainState = STATE_CALIBRATING
    private var stopCandidateSince: Long? = null
    private var movingCandidateSince: Long? = null
    private var dynamicStopThreshold: Double? = null
    private var dynamicMovingThreshold: Double? = null
    private var lastThresholdUpdateAt = 0L
    private var lastP25: Double? = null
    private var lastP70: Double? = null

    fun reset() {
        micHistory.clear()
        stableTrainState = STATE_CALIBRATING
        stopCandidateSince = null
        movingCandidateSince = null
        dynamicStopThreshold = null
        dynamicMovingThreshold = null
        lastThresholdUpdateAt = 0L
        lastP25 = null
        lastP70 = null
    }

    fun update(
        micRms: Double,
        micValid: Boolean,
        accelRms: Double,
        gyroDegreesRms: Double,
        now: Long,
    ): InferenceResult {
        val micUsable = micValid && micRms >= MIC_NONZERO_FLOOR
        if (micUsable) {
            purgeOldMicSamples(now)
            maybeUpdateDynamicThresholds(now)
        }

        val effectiveStopThreshold = dynamicStopThreshold ?: MIC_STOP_RMS_THRESHOLD
        val effectiveMovingThreshold = dynamicMovingThreshold ?: MIC_MOVING_RMS_THRESHOLD
        val thresholdMode = if (
            dynamicStopThreshold != null && dynamicMovingThreshold != null
        ) {
            "动态"
        } else {
            "固定"
        }
        val playerActive = accelRms >= PLAYER_ACCEL_RMS_THRESHOLD ||
            gyroDegreesRms >= PLAYER_GYRO_RMS_THRESHOLD_DEG_S
        val playerState = if (playerActive) PLAYER_ACTIVE else PLAYER_STILL
        val micAboveMovingThreshold = micUsable && micRms >= effectiveMovingThreshold
        val micBelowStopThreshold = micUsable && micRms <= effectiveStopThreshold
        val micLevelRatio = if (micUsable) {
            micRms / ((effectiveStopThreshold + effectiveMovingThreshold) / 2.0)
        } else {
            0.0
        }

        if (!micUsable) {
            stopCandidateSince = null
            movingCandidateSince = null
            return result(
                micRms = micRms,
                playerActive = playerActive,
                playerState = playerState,
                rawTrainState = stableTrainState,
                effectiveStopThreshold = effectiveStopThreshold,
                effectiveMovingThreshold = effectiveMovingThreshold,
                thresholdMode = thresholdMode,
                micLevelRatio = micLevelRatio,
                micAboveMovingThreshold = false,
                micBelowStopThreshold = false,
                stopCandidateElapsedMs = 0L,
                movingCandidateElapsedMs = 0L,
                reason = if (micValid) "mic-zero-hold-state" else "mic-invalid-hold-state",
            )
        }

        val rawTrainState = when {
            micBelowStopThreshold -> STATE_STOPPED
            micAboveMovingThreshold -> STATE_MOVING
            else -> stableTrainState
        }
        var reason: String
        var stopCandidateElapsedMs = 0L
        var movingCandidateElapsedMs = 0L

        when {
            micBelowStopThreshold -> {
                movingCandidateSince = null
                if (stableTrainState == STATE_STOPPED) {
                    stopCandidateSince = null
                    reason = "mic-stop-hold"
                } else {
                    val candidateSince = stopCandidateSince ?: now.also { stopCandidateSince = it }
                    stopCandidateElapsedMs = (now - candidateSince).coerceAtLeast(0L)
                    if (stopCandidateElapsedMs >= STOP_CONFIRMATION_MS) {
                        stableTrainState = STATE_STOPPED
                        stopCandidateSince = null
                        reason = "mic-stop-confirmed"
                    } else {
                        reason = "mic-stop-confirming"
                    }
                }
            }

            micAboveMovingThreshold -> {
                stopCandidateSince = null
                if (stableTrainState == STATE_MOVING) {
                    movingCandidateSince = null
                    reason = "mic-moving-hold"
                } else {
                    val candidateSince = movingCandidateSince ?: now.also { movingCandidateSince = it }
                    movingCandidateElapsedMs = (now - candidateSince).coerceAtLeast(0L)
                    if (movingCandidateElapsedMs >= MOVING_CONFIRMATION_MS) {
                        stableTrainState = STATE_MOVING
                        movingCandidateSince = null
                        reason = "mic-moving-confirmed"
                    } else {
                        reason = "mic-moving-confirming"
                    }
                }
            }

            else -> {
                stopCandidateSince = null
                movingCandidateSince = null
                reason = "mic-ambiguous-hold-state"
            }
        }

        if (
            micRms >= MIC_NONZERO_FLOOR &&
            (stableTrainState == STATE_MOVING || stableTrainState == STATE_STOPPED)
        ) {
            micHistory.addLast(MicPoint(now, micRms, stableTrainState))
        }

        return result(
            micRms = micRms,
            playerActive = playerActive,
            playerState = playerState,
            rawTrainState = rawTrainState,
            effectiveStopThreshold = effectiveStopThreshold,
            effectiveMovingThreshold = effectiveMovingThreshold,
            thresholdMode = thresholdMode,
            micLevelRatio = micLevelRatio,
            micAboveMovingThreshold = micAboveMovingThreshold,
            micBelowStopThreshold = micBelowStopThreshold,
            stopCandidateElapsedMs = stopCandidateElapsedMs,
            movingCandidateElapsedMs = movingCandidateElapsedMs,
            reason = reason,
        )
    }

    private fun result(
        micRms: Double,
        playerActive: Boolean,
        playerState: String,
        rawTrainState: String,
        effectiveStopThreshold: Double,
        effectiveMovingThreshold: Double,
        thresholdMode: String,
        micLevelRatio: Double,
        micAboveMovingThreshold: Boolean,
        micBelowStopThreshold: Boolean,
        stopCandidateElapsedMs: Long,
        movingCandidateElapsedMs: Long,
        reason: String,
    ): InferenceResult {
        val state = combinedState(stableTrainState, playerActive)
        val rawState = combinedState(rawTrainState, playerActive)
        val finalReason = if (state == STATE_STOPPED_PLAYER_ACTIVE) {
            "$reason;player-active"
        } else {
            reason
        }
        return InferenceResult(
            state = state,
            rawState = rawState,
            trainState = stableTrainState,
            rawTrainState = rawTrainState,
            playerState = playerState,
            playerActive = playerActive,
            micLevelRatio = micLevelRatio,
            micAboveMovingThreshold = micAboveMovingThreshold,
            micBelowStopThreshold = micBelowStopThreshold,
            stopCandidateElapsedMs = stopCandidateElapsedMs,
            movingCandidateElapsedMs = movingCandidateElapsedMs,
            fixedStopThreshold = MIC_STOP_RMS_THRESHOLD,
            fixedMovingThreshold = MIC_MOVING_RMS_THRESHOLD,
            dynamicStopThreshold = dynamicStopThreshold,
            dynamicMovingThreshold = dynamicMovingThreshold,
            effectiveStopThreshold = effectiveStopThreshold,
            effectiveMovingThreshold = effectiveMovingThreshold,
            thresholdMode = thresholdMode,
            micP25 = lastP25,
            micP70 = lastP70,
            validMicSampleCount = micHistory.size,
            reason = finalReason,
        )
    }

    private fun purgeOldMicSamples(now: Long) {
        val oldestAllowed = now - DYNAMIC_HISTORY_MS
        while (micHistory.firstOrNull()?.timestampMs?.let { it < oldestAllowed } == true) {
            micHistory.removeFirst()
        }
    }

    private fun maybeUpdateDynamicThresholds(now: Long) {
        if (micHistory.size < MIN_DYNAMIC_SAMPLES) return
        if (lastThresholdUpdateAt != 0L && now - lastThresholdUpdateAt < THRESHOLD_UPDATE_INTERVAL_MS) {
            return
        }

        val movingCount = micHistory.count { it.trainState == STATE_MOVING }
        val stoppedCount = micHistory.count { it.trainState == STATE_STOPPED }
        if (movingCount < MIN_SAMPLES_PER_STATE || stoppedCount < MIN_SAMPLES_PER_STATE) {
            return
        }

        val sorted = micHistory.map { it.rms }.sorted()
        val p25 = percentile(sorted, 0.25)
        val p70 = percentile(sorted, 0.70)
        var targetStop = p25.coerceIn(MIN_STOP_THRESHOLD, MAX_STOP_THRESHOLD)
        var targetMoving = p70.coerceIn(MIN_MOVING_THRESHOLD, MAX_MOVING_THRESHOLD)

        if (targetMoving - targetStop < MIN_HYSTERESIS_GAP) {
            targetMoving = (targetStop + MIN_HYSTERESIS_GAP)
                .coerceIn(MIN_MOVING_THRESHOLD, MAX_MOVING_THRESHOLD)
            if (targetMoving - targetStop < MIN_HYSTERESIS_GAP) {
                targetStop = (targetMoving - MIN_HYSTERESIS_GAP)
                    .coerceIn(MIN_STOP_THRESHOLD, MAX_STOP_THRESHOLD)
            }
        }

        dynamicStopThreshold = smooth(
            dynamicStopThreshold ?: MIC_STOP_RMS_THRESHOLD,
            targetStop,
        )
        dynamicMovingThreshold = smooth(
            dynamicMovingThreshold ?: MIC_MOVING_RMS_THRESHOLD,
            targetMoving,
        )
        lastP25 = p25
        lastP70 = p70
        lastThresholdUpdateAt = now
    }

    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun smooth(current: Double, target: Double): Double =
        current + THRESHOLD_SMOOTHING * (target - current)

    private fun combinedState(trainState: String, playerActive: Boolean): String =
        if (trainState == STATE_STOPPED && playerActive) {
            STATE_STOPPED_PLAYER_ACTIVE
        } else {
            trainState
        }
}
