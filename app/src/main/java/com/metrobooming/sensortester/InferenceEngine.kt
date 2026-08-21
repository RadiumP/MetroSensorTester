package com.metrobooming.sensortester

data class InferenceResult(
    val trainState: TrainState,
    val rawTrainState: TrainState,
    val playerState: PlayerState,
    val playerActive: Boolean,
    val micLevelRatio: Double,
    val micAboveMovingThreshold: Boolean,
    val micBelowStopThreshold: Boolean,
    val stopCandidateElapsedMs: Long,
    val movingCandidateElapsedMs: Long,
    val fixedStopThreshold: Double,
    val fixedMovingThreshold: Double,
    val stopConfirmationMs: Long,
    val movingConfirmationMs: Long,
    val dynamicStopThreshold: Double?,
    val dynamicMovingThreshold: Double?,
    val effectiveStopThreshold: Double,
    val effectiveMovingThreshold: Double,
    val thresholdMode: ThresholdMode,
    val micP25: Double?,
    val micP70: Double?,
    val validMicSampleCount: Int,
    val reason: String,
) {
    val state: String get() = combinedLabel(trainState, playerActive)
    val rawState: String get() = combinedLabel(rawTrainState, playerActive)

    companion object {
        private const val STOPPED_PLAYER_ACTIVE_LABEL = "停站但玩家活动"

        fun combinedLabel(trainState: TrainState, playerActive: Boolean): String =
            if (trainState == TrainState.STOPPED && playerActive) {
                STOPPED_PLAYER_ACTIVE_LABEL
            } else {
                trainState.label
            }
    }
}

class InferenceEngine(private val config: TuningConfig = TuningConfig()) {

    private val classifier = TrainStateClassifier(config)
    private val thresholds = DynamicThresholdTracker(config)

    fun reset() {
        classifier.reset()
        thresholds.reset()
    }

    fun update(
        micRms: Double,
        micValid: Boolean,
        accelRms: Double,
        gyroDegreesRms: Double,
        now: Long,
    ): InferenceResult {
        val micUsable = micValid && micRms >= config.micNonZeroFloor
        if (micUsable) thresholds.prepare(now)

        val effectiveStopThreshold = thresholds.stopThreshold ?: config.fixedStopThreshold
        val effectiveMovingThreshold = thresholds.movingThreshold ?: config.fixedMovingThreshold
        val thresholdMode = if (
            thresholds.stopThreshold != null && thresholds.movingThreshold != null
        ) {
            ThresholdMode.DYNAMIC
        } else {
            ThresholdMode.FIXED
        }
        val playerActive = accelRms >= config.playerAccelRmsThreshold ||
            gyroDegreesRms >= config.playerGyroRmsThresholdDegS
        val playerState = if (playerActive) PlayerState.ACTIVE else PlayerState.STILL
        val micAboveMovingThreshold = micUsable && micRms >= effectiveMovingThreshold
        val micBelowStopThreshold = micUsable && micRms <= effectiveStopThreshold
        val micLevelRatio = if (micUsable) {
            micRms / ((effectiveStopThreshold + effectiveMovingThreshold) / 2.0)
        } else {
            0.0
        }

        val outcome = if (!micUsable) {
            classifier.onMicUnavailable()
            null
        } else {
            classifier.classify(now, micBelowStopThreshold, micAboveMovingThreshold).also {
                if (it.stable != TrainState.CALIBRATING && micRms >= config.micNonZeroFloor) {
                    thresholds.record(now, micRms, it.stable)
                }
            }
        }

        val reason = when {
            outcome == null ->
                if (micValid) "mic-zero-hold-state" else "mic-invalid-hold-state"

            outcome.stable == TrainState.STOPPED && playerActive ->
                "${outcome.reason};player-active"

            else -> outcome.reason
        }

        return InferenceResult(
            trainState = classifier.current,
            rawTrainState = outcome?.raw ?: classifier.current,
            playerState = playerState,
            playerActive = playerActive,
            micLevelRatio = micLevelRatio,
            micAboveMovingThreshold = micAboveMovingThreshold,
            micBelowStopThreshold = micBelowStopThreshold,
            stopCandidateElapsedMs = outcome?.stopCandidateElapsedMs ?: 0L,
            movingCandidateElapsedMs = outcome?.movingCandidateElapsedMs ?: 0L,
            fixedStopThreshold = config.fixedStopThreshold,
            fixedMovingThreshold = config.fixedMovingThreshold,
            stopConfirmationMs = config.stopConfirmationMs,
            movingConfirmationMs = config.movingConfirmationMs,
            dynamicStopThreshold = thresholds.stopThreshold,
            dynamicMovingThreshold = thresholds.movingThreshold,
            effectiveStopThreshold = effectiveStopThreshold,
            effectiveMovingThreshold = effectiveMovingThreshold,
            thresholdMode = thresholdMode,
            micP25 = thresholds.lastP25,
            micP70 = thresholds.lastP70,
            validMicSampleCount = thresholds.sampleCount,
            reason = reason,
        )
    }
}
