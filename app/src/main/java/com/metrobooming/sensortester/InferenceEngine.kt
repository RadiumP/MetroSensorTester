package com.metrobooming.sensortester

data class InferenceResult(
    val state: String,
    val rawState: String,
    val trainState: String,
    val rawTrainState: String,
    val playerActive: Boolean,
    val micLevelRatio: Double,
    val micAboveMovingThreshold: Boolean,
    val micBelowStopThreshold: Boolean,
    val stopCandidateElapsedMs: Long,
    val reason: String,
)

class InferenceEngine {
    companion object {
        // Thresholds are normalized PCM16 RMS values produced directly by AudioRecord.
        // They were selected from the manually labelled Android recordings in this project.
        const val MIC_STOP_RMS_THRESHOLD = 0.0014
        const val MIC_MOVING_RMS_THRESHOLD = 0.0030
        const val STOP_CONFIRMATION_MS = 3_000L

        const val PLAYER_ACCEL_RMS_THRESHOLD = 0.40
        const val PLAYER_GYRO_RMS_THRESHOLD_DEG_S = 15.0

        private const val STATE_CALIBRATING = "校准中"
        private const val STATE_MOVING = "运行"
        private const val STATE_STOPPED = "停站"
        private const val STATE_STOPPED_PLAYER_ACTIVE = "停站但玩家活动"
    }

    private var stableTrainState = STATE_CALIBRATING
    private var stopCandidateSince: Long? = null

    fun reset() {
        stableTrainState = STATE_CALIBRATING
        stopCandidateSince = null
    }

    fun update(
        micRms: Double,
        micValid: Boolean,
        accelRms: Double,
        gyroDegreesRms: Double,
        now: Long,
    ): InferenceResult {
        val playerActive = accelRms >= PLAYER_ACCEL_RMS_THRESHOLD ||
            gyroDegreesRms >= PLAYER_GYRO_RMS_THRESHOLD_DEG_S
        val micAboveMovingThreshold = micValid && micRms >= MIC_MOVING_RMS_THRESHOLD
        val micBelowStopThreshold = micValid && micRms <= MIC_STOP_RMS_THRESHOLD
        val micLevelRatio = if (micValid) {
            micRms / ((MIC_STOP_RMS_THRESHOLD + MIC_MOVING_RMS_THRESHOLD) / 2.0)
        } else {
            0.0
        }

        if (!micValid) {
            stopCandidateSince = null
            val heldState = combinedState(stableTrainState, playerActive)
            return InferenceResult(
                state = heldState,
                rawState = heldState,
                trainState = stableTrainState,
                rawTrainState = stableTrainState,
                playerActive = playerActive,
                micLevelRatio = micLevelRatio,
                micAboveMovingThreshold = false,
                micBelowStopThreshold = false,
                stopCandidateElapsedMs = 0L,
                reason = "mic-unavailable-hold-state",
            )
        }

        val rawTrainState = when {
            micAboveMovingThreshold -> STATE_MOVING
            micBelowStopThreshold -> STATE_STOPPED
            stopCandidateSince != null -> STATE_STOPPED
            stableTrainState == STATE_MOVING -> STATE_MOVING
            stableTrainState == STATE_STOPPED -> STATE_STOPPED
            micRms >= (MIC_STOP_RMS_THRESHOLD + MIC_MOVING_RMS_THRESHOLD) / 2.0 -> STATE_MOVING
            else -> STATE_STOPPED
        }

        var reason: String
        var stopCandidateElapsedMs = 0L
        if (rawTrainState == STATE_MOVING) {
            stableTrainState = STATE_MOVING
            stopCandidateSince = null
            reason = if (micAboveMovingThreshold) {
                "mic-above-moving-threshold"
            } else {
                "mic-hysteresis-hold-moving"
            }
        } else {
            if (stableTrainState == STATE_STOPPED) {
                stopCandidateSince = null
                reason = if (micBelowStopThreshold) {
                    "mic-below-stop-threshold"
                } else {
                    "mic-hysteresis-hold-stopped"
                }
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

        val rawState = combinedState(rawTrainState, playerActive)
        val state = combinedState(stableTrainState, playerActive)
        if (state == STATE_STOPPED_PLAYER_ACTIVE) reason = "stopped-player-active"

        return InferenceResult(
            state = state,
            rawState = rawState,
            trainState = stableTrainState,
            rawTrainState = rawTrainState,
            playerActive = playerActive,
            micLevelRatio = micLevelRatio,
            micAboveMovingThreshold = micAboveMovingThreshold,
            micBelowStopThreshold = micBelowStopThreshold,
            stopCandidateElapsedMs = stopCandidateElapsedMs,
            reason = reason,
        )
    }

    private fun combinedState(trainState: String, playerActive: Boolean): String =
        if (trainState == STATE_STOPPED && playerActive) {
            STATE_STOPPED_PLAYER_ACTIVE
        } else {
            trainState
        }
}
