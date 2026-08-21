package com.metrobooming.sensortester

class TrainStateClassifier(private val config: TuningConfig) {

    data class Outcome(
        val stable: TrainState,
        val raw: TrainState,
        val reason: String,
        val stopCandidateElapsedMs: Long,
        val movingCandidateElapsedMs: Long,
    )

    private var stable = TrainState.CALIBRATING
    private var stopCandidateSince: Long? = null
    private var movingCandidateSince: Long? = null

    val current: TrainState get() = stable

    fun reset() {
        stable = TrainState.CALIBRATING
        stopCandidateSince = null
        movingCandidateSince = null
    }

    fun onMicUnavailable() {
        stopCandidateSince = null
        movingCandidateSince = null
    }

    fun classify(now: Long, belowStopThreshold: Boolean, aboveMovingThreshold: Boolean): Outcome {
        val raw = when {
            belowStopThreshold -> TrainState.STOPPED
            aboveMovingThreshold -> TrainState.MOVING
            else -> stable
        }
        var reason: String
        var stopCandidateElapsedMs = 0L
        var movingCandidateElapsedMs = 0L

        when {
            belowStopThreshold -> {
                movingCandidateSince = null
                if (stable == TrainState.STOPPED) {
                    stopCandidateSince = null
                    reason = "mic-stop-hold"
                } else {
                    val candidateSince = stopCandidateSince ?: now.also { stopCandidateSince = it }
                    stopCandidateElapsedMs = (now - candidateSince).coerceAtLeast(0L)
                    if (stopCandidateElapsedMs >= config.stopConfirmationMs) {
                        stable = TrainState.STOPPED
                        stopCandidateSince = null
                        reason = "mic-stop-confirmed"
                    } else {
                        reason = "mic-stop-confirming"
                    }
                }
            }

            aboveMovingThreshold -> {
                stopCandidateSince = null
                if (stable == TrainState.MOVING) {
                    movingCandidateSince = null
                    reason = "mic-moving-hold"
                } else {
                    val candidateSince = movingCandidateSince ?: now.also { movingCandidateSince = it }
                    movingCandidateElapsedMs = (now - candidateSince).coerceAtLeast(0L)
                    if (movingCandidateElapsedMs >= config.movingConfirmationMs) {
                        stable = TrainState.MOVING
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

        return Outcome(stable, raw, reason, stopCandidateElapsedMs, movingCandidateElapsedMs)
    }
}
