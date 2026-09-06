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
    // Despite the field names (kept for CSV schema stability), these are no
    // longer a combined-history P25/P70: micP25 is the P75 of recent "停站"-only
    // samples and micP70 is the P25 of recent "运行"-only samples -- the raw
    // percentiles the dynamic thresholds below are derived from.
    val micP25: Double?,
    val micP70: Double?,
    val validMicSampleCount: Int,
    // Cheap "did we maybe just hear the fixed station announcement chime"
    // signal: a station announcement is loud but brief, so it barely moves
    // the windowed RMS average while still producing a sharp instantaneous
    // peak -- i.e. an abnormally high peak/rms ratio for that tick. This is
    // logged for field-testing only; it does not currently feed into the
    // train-state decision above.
    val micCrestFactor: Double,
    val micChimeCandidate: Boolean,
    // Alternate cheap "did we maybe just hear a fixed announcement/alarm"
    // signal, aimed at sounds too sustained for the peak/rms crest factor
    // above to catch (a spoken station announcement raises the whole
    // windowed RMS rather than spiking the peak): compares this tick's RMS
    // to the median RMS from a few seconds earlier. Also logged only, not
    // yet used by the train-state decision. Field-test note: this only
    // shows a bump while the recent baseline itself was quiet -- once the
    // train is already loud (moving), both this and the announcement/alarm
    // sound get buried in ambient noise and the ratio stays ~1.0x.
    val micBaselineRms: Double?,
    val micRmsBumpRatio: Double,
    val micRmsBumpCandidate: Boolean,
    // Tick-to-tick change in raw magnetometer field strength (orientation-
    // independent -- unlike compass_heading_deg, which also picks up the
    // phone's own rotation when the player is holding/moving it). Field
    // test (2026-08-28) found this reads consistently lower while stopped
    // than while moving (2.3x-4.6x higher moving, across two rides) -- a
    // plausible read is electromagnetic interference from the traction
    // motor while it's actually drawing power. Null when no magnetometer
    // sample is available. Logged only; not yet used by the train-state
    // decision.
    val magnetMagnitudeJitter: Double?,
    // Smoothed (median over the last MAGNET_SMOOTHING_WINDOW_MS) version of
    // magnetMagnitudeJitter above. The raw per-tick jitter overlaps too much
    // between "运行"/"停站" to threshold directly -- across 11 real rides the
    // moving/stopped medians separated cleanly (1.5x-5x) but the P25-moving
    // vs P75-stopped quartiles overlapped in most of them. The windowed
    // median is far less noisy. Null until enough samples have accumulated
    // in the window.
    val magnetJitterSmoothed: Double?,
    // "运行"/"停站"/null (ambiguous, or not enough data yet) candidate
    // reading from the magnet channel alone, using the same per-state
    // dynamic-threshold technique as the mic channel below. This does NOT
    // independently drive train_state -- see magnetAssistNote.
    val magnetCandidateState: String?,
    // Whether/how the magnet candidate above adjusted the mic-driven
    // confirmation timer this tick: "agree" shortens the remaining
    // confirmation time, "disagree" lengthens it, "none" leaves it
    // unchanged (no active mic candidate this tick, or magnet has no
    // opinion). "trigger" is the rare last-resort case: a rolling-window
    // majority of magnet candidate reads disagreed with the held state
    // (see MAGNET_INDEPENDENT_WINDOW_MS's doc) and forced the transition
    // itself, because mic never even attempted a candidate for it. Mic
    // RMS otherwise stays the primary decision signal.
    val magnetAssistNote: String,
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

        // A tick's peak/rms ratio ("crest factor") is normally ~2.3-4.5 for
        // ordinary train/cabin noise (checked against real ride recordings).
        // The fixed station announcement chime is short enough that it barely
        // raises the windowed RMS average while still spiking the peak, so a
        // much higher ratio is the cheap tell -- no new audio processing
        // needed, mic_peak/mic_rms are already collected every tick.
        const val MIC_CHIME_CREST_FACTOR_THRESHOLD = 8.0
        const val MIC_CHIME_MIN_PEAK = 0.008

        // Baseline-bump detector: compares each tick's RMS against the
        // median RMS from MIC_BUMP_GAP_MS..MIC_BUMP_LOOKBACK_MS ago (the gap
        // excludes the second or so right before "now" so a sound that
        // started slightly early doesn't leak into its own baseline).
        // Real-ride field test (2026-08-28): a quiet-baseline announcement
        // or door-alarm produced a 1.5x-3.3x bump; 1.8x sits below all of
        // those while still comfortably above ordinary tick-to-tick RMS
        // jitter. Not tuned much beyond that -- expect to revisit once more
        // field data comes in.
        const val MIC_BUMP_LOOKBACK_MS = 6_000L
        const val MIC_BUMP_GAP_MS = 1_000L
        const val MIC_BUMP_MIN_BASELINE_SAMPLES = 8
        const val MIC_BUMP_RATIO_THRESHOLD = 1.8

        // Magnetic-field jitter "辅助确认" (assist-confirmation) signal.
        // Mic RMS stays the sole primary decision signal; magnet jitter only
        // speeds up or slows down the mic confirmation timer when it agrees
        // or disagrees with the direction mic is already leaning. It never
        // triggers a state change by itself.
        //
        // A single tick's jitter is too noisy to threshold directly (see
        // magnetJitterSmoothed doc above), so this smooths over a short
        // rolling window first.
        const val MAGNET_SMOOTHING_WINDOW_MS = 4_000L
        const val MAGNET_SMOOTHING_MIN_SAMPLES = 4

        // Fixed fallback thresholds for the smoothed jitter, derived from
        // the aggregate of 11 real rides (stopped medians ranged 0.39-1.6,
        // moving medians ranged 1.9-3.7): comfortably inside both bands with
        // margin on either side for phone-to-phone variation. Used until the
        // dynamic per-state thresholds below have enough history.
        const val MAGNET_STOP_JITTER_THRESHOLD = 1.2
        const val MAGNET_MOVING_JITTER_THRESHOLD = 2.0

        private const val MAGNET_DYNAMIC_HISTORY_MS = 180_000L
        private const val MAGNET_MIN_SAMPLES_PER_STATE = 20
        private const val MAGNET_MIN_STOP_THRESHOLD = 0.4
        private const val MAGNET_MAX_STOP_THRESHOLD = 1.8
        private const val MAGNET_MIN_MOVING_THRESHOLD = 1.5
        private const val MAGNET_MAX_MOVING_THRESHOLD = 4.5
        private const val MAGNET_MIN_HYSTERESIS_GAP = 0.3
        private const val MAGNET_THRESHOLD_SMOOTHING = 0.08
        private const val MAGNET_THRESHOLD_UPDATE_INTERVAL_MS = 5_000L

        // How much the agreeing/disagreeing magnet candidate scales the
        // mic-driven confirmation duration (STOP_CONFIRMATION_MS /
        // MOVING_CONFIRMATION_MS above), with a floor so it can never make a
        // transition near-instant.
        const val MAGNET_ASSIST_AGREE_MULTIPLIER = 0.6
        const val MAGNET_ASSIST_DISAGREE_MULTIPLIER = 1.4
        const val MAGNET_ASSIST_MIN_CONFIRMATION_MS = 500L

        // Deadlock-breaker fallback: on some routes mic RMS never reliably
        // crosses its own threshold in either direction (2026-09-02 field
        // test -- a quiet at-grade light-rail ride spent >97% of the ride
        // stuck showing "停站" because genuine moving noise almost never
        // crossed the moving threshold, so mic itself never even attempted a
        // moving candidate for the magnet assist above to speed up).
        //
        // First cut of this (an unbroken-streak requirement) turned out too
        // strict: real-ride field test (2026-09-05) found a 137-second
        // mis-held "停站" stretch that contained a clean ~20s window where
        // 93-100% of magnet_candidate_state readings said "运行" -- genuine
        // signal -- but no single unbroken run inside it reached even 5
        // seconds, because the smoothed magnet reading itself flickers
        // tick-to-tick near the threshold. A rolling-window majority vote
        // tolerates that flicker while still requiring strong, sustained
        // evidence: count "运行"/"停站" candidate reads over the trailing
        // window and only trigger once enough of them (MIN_SAMPLES) point
        // the same way by a clear supermajority (MAJORITY_RATIO).
        const val MAGNET_INDEPENDENT_WINDOW_MS = 20_000L
        const val MAGNET_INDEPENDENT_MIN_SAMPLES = 20
        const val MAGNET_INDEPENDENT_MAJORITY_RATIO = 0.75

        private const val DYNAMIC_HISTORY_MS = 180_000L
        private const val MIN_DYNAMIC_SAMPLES = 240
        private const val MIN_SAMPLES_PER_STATE = 20
        private const val THRESHOLD_UPDATE_INTERVAL_MS = 5_000L
        private const val THRESHOLD_SMOOTHING = 0.08
        private const val MIN_HYSTERESIS_GAP = 0.0002
        // Real-ride field test (2026-09-02): an at-grade light-rail ride's
        // whole cabin was quiet enough that genuine moving-noise RMS sat
        // around 0.0013 -- below the old MIN_MOVING_THRESHOLD (0.0016), so
        // the dynamic threshold could never track down to it even once
        // enough "运行"-labeled history existed. Lowered both bounds enough
        // to cover that ride while still leaving MIN_HYSTERESIS_GAP of room
        // above the stop side.
        private const val MIN_STOP_THRESHOLD = 0.0005
        private const val MAX_STOP_THRESHOLD = 0.0016
        private const val MIN_MOVING_THRESHOLD = 0.0010
        private const val MAX_MOVING_THRESHOLD = 0.0030

        private const val STATE_CALIBRATING = "校准中"
        private const val STATE_MOVING = "运行"
        private const val STATE_STOPPED = "停站"
        private const val STATE_STOPPED_PLAYER_ACTIVE = "停站但玩家活动"
        private const val PLAYER_ACTIVE = "活动"
        private const val PLAYER_STILL = "静止"
    }

    private data class MicPoint(val timestampMs: Long, val rms: Double, val trainState: String)
    private data class RecentMicSample(val timestampMs: Long, val rms: Double)
    private data class MagnetPoint(val timestampMs: Long, val jitterSmoothed: Double, val trainState: String)
    private data class RecentMagnetSample(val timestampMs: Long, val jitter: Double)
    private data class MagnetCandidateVote(val timestampMs: Long, val state: String)

    private val micHistory = ArrayDeque<MicPoint>()
    // Short rolling window feeding the baseline-bump detector -- separate
    // from micHistory above, which only keeps samples once the train state
    // is stable and is used for the (much longer, 3-minute) dynamic
    // threshold calculation instead.
    private val recentMicSamples = ArrayDeque<RecentMicSample>()
    // Same split for the magnet channel: recentMagnetJitterSamples feeds the
    // short smoothing window, magnetHistory feeds its own 3-minute dynamic
    // per-state threshold.
    private val recentMagnetJitterSamples = ArrayDeque<RecentMagnetSample>()
    private val magnetHistory = ArrayDeque<MagnetPoint>()
    private var stableTrainState = STATE_CALIBRATING
    private var stopCandidateSince: Long? = null
    private var movingCandidateSince: Long? = null
    private var dynamicStopThreshold: Double? = null
    private var dynamicMovingThreshold: Double? = null
    private var lastThresholdUpdateAt = 0L
    private var lastP25: Double? = null
    private var lastP70: Double? = null
    private var lastMagnetMagnitude: Double? = null
    private var dynamicMagnetStopThreshold: Double? = null
    private var dynamicMagnetMovingThreshold: Double? = null
    private var lastMagnetThresholdUpdateAt = 0L
    private val magnetCandidateVotes = ArrayDeque<MagnetCandidateVote>()

    fun reset() {
        micHistory.clear()
        recentMicSamples.clear()
        recentMagnetJitterSamples.clear()
        magnetHistory.clear()
        stableTrainState = STATE_CALIBRATING
        stopCandidateSince = null
        movingCandidateSince = null
        dynamicStopThreshold = null
        dynamicMovingThreshold = null
        lastThresholdUpdateAt = 0L
        lastP25 = null
        lastP70 = null
        lastMagnetMagnitude = null
        dynamicMagnetStopThreshold = null
        dynamicMagnetMovingThreshold = null
        lastMagnetThresholdUpdateAt = 0L
        magnetCandidateVotes.clear()
    }

    fun update(
        micRms: Double,
        micValid: Boolean,
        micPeak: Double,
        accelRms: Double,
        gyroDegreesRms: Double,
        magnetMagnitude: Double?,
        now: Long,
    ): InferenceResult {
        val magnetMagnitudeJitter = if (magnetMagnitude != null && lastMagnetMagnitude != null) {
            kotlin.math.abs(magnetMagnitude - lastMagnetMagnitude!!)
        } else {
            null
        }
        if (magnetMagnitude != null) lastMagnetMagnitude = magnetMagnitude

        while (
            recentMagnetJitterSamples.firstOrNull()?.timestampMs
                ?.let { it < now - MAGNET_SMOOTHING_WINDOW_MS } == true
        ) {
            recentMagnetJitterSamples.removeFirst()
        }
        if (magnetMagnitudeJitter != null) {
            recentMagnetJitterSamples.addLast(RecentMagnetSample(now, magnetMagnitudeJitter))
        }
        val magnetJitterSmoothed = if (recentMagnetJitterSamples.size >= MAGNET_SMOOTHING_MIN_SAMPLES) {
            percentile(recentMagnetJitterSamples.map { it.jitter }.sorted(), 0.5)
        } else {
            null
        }

        // Label this smoothed sample with whichever train state was stable
        // going into this tick (before any transition below) and fold it
        // into the magnet channel's own 3-minute per-state history, same
        // pattern as micHistory below.
        if (
            magnetJitterSmoothed != null &&
            (stableTrainState == STATE_MOVING || stableTrainState == STATE_STOPPED)
        ) {
            val oldestAllowed = now - MAGNET_DYNAMIC_HISTORY_MS
            while (magnetHistory.firstOrNull()?.timestampMs?.let { it < oldestAllowed } == true) {
                magnetHistory.removeFirst()
            }
            magnetHistory.addLast(MagnetPoint(now, magnetJitterSmoothed, stableTrainState))
            maybeUpdateDynamicMagnetThresholds(now)
        }

        val effectiveMagnetStopThreshold = dynamicMagnetStopThreshold ?: MAGNET_STOP_JITTER_THRESHOLD
        val effectiveMagnetMovingThreshold = dynamicMagnetMovingThreshold ?: MAGNET_MOVING_JITTER_THRESHOLD
        val magnetCandidateState = when {
            magnetJitterSmoothed == null -> null
            magnetJitterSmoothed <= effectiveMagnetStopThreshold -> STATE_STOPPED
            magnetJitterSmoothed >= effectiveMagnetMovingThreshold -> STATE_MOVING
            else -> null
        }

        val micUsable = micValid && micRms >= MIC_NONZERO_FLOOR
        if (micUsable) {
            purgeOldMicSamples(now)
            maybeUpdateDynamicThresholds(now)
        }
        val micCrestFactor = if (micUsable) micPeak / micRms else 0.0
        val micChimeCandidate = micUsable &&
            micPeak >= MIC_CHIME_MIN_PEAK &&
            micCrestFactor >= MIC_CHIME_CREST_FACTOR_THRESHOLD

        var micBaselineRms: Double? = null
        var micRmsBumpRatio = 0.0
        var micRmsBumpCandidate = false
        if (micUsable) {
            while (
                recentMicSamples.firstOrNull()?.timestampMs?.let { it < now - MIC_BUMP_LOOKBACK_MS } == true
            ) {
                recentMicSamples.removeFirst()
            }
            val baselineSamples = recentMicSamples
                .filter { it.timestampMs <= now - MIC_BUMP_GAP_MS }
                .map { it.rms }
                .sorted()
            if (baselineSamples.size >= MIC_BUMP_MIN_BASELINE_SAMPLES) {
                val baseline = percentile(baselineSamples, 0.5)
                micBaselineRms = baseline
                micRmsBumpRatio = if (baseline > MIC_NONZERO_FLOOR) micRms / baseline else 0.0
                micRmsBumpCandidate = micRmsBumpRatio >= MIC_BUMP_RATIO_THRESHOLD
            }
            recentMicSamples.addLast(RecentMicSample(now, micRms))
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
                micCrestFactor = micCrestFactor,
                micChimeCandidate = micChimeCandidate,
                micBaselineRms = micBaselineRms,
                micRmsBumpRatio = micRmsBumpRatio,
                micRmsBumpCandidate = micRmsBumpCandidate,
                magnetMagnitudeJitter = magnetMagnitudeJitter,
                magnetJitterSmoothed = magnetJitterSmoothed,
                magnetCandidateState = magnetCandidateState,
                magnetAssistNote = "none",
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
        var magnetAssistNote = "none"

        when {
            micBelowStopThreshold -> {
                movingCandidateSince = null
                if (stableTrainState == STATE_STOPPED) {
                    stopCandidateSince = null
                    reason = "mic-stop-hold"
                } else {
                    val candidateSince = stopCandidateSince ?: now.also { stopCandidateSince = it }
                    stopCandidateElapsedMs = (now - candidateSince).coerceAtLeast(0L)
                    val requiredStopConfirmationMs: Long
                    when (magnetCandidateState) {
                        STATE_STOPPED -> {
                            magnetAssistNote = "agree"
                            requiredStopConfirmationMs = (STOP_CONFIRMATION_MS * MAGNET_ASSIST_AGREE_MULTIPLIER)
                                .toLong()
                                .coerceAtLeast(MAGNET_ASSIST_MIN_CONFIRMATION_MS)
                        }
                        STATE_MOVING -> {
                            magnetAssistNote = "disagree"
                            requiredStopConfirmationMs = (STOP_CONFIRMATION_MS * MAGNET_ASSIST_DISAGREE_MULTIPLIER)
                                .toLong()
                        }
                        else -> {
                            requiredStopConfirmationMs = STOP_CONFIRMATION_MS
                        }
                    }
                    if (stopCandidateElapsedMs >= requiredStopConfirmationMs) {
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
                    val requiredMovingConfirmationMs: Long
                    when (magnetCandidateState) {
                        STATE_MOVING -> {
                            magnetAssistNote = "agree"
                            requiredMovingConfirmationMs =
                                (MOVING_CONFIRMATION_MS * MAGNET_ASSIST_AGREE_MULTIPLIER)
                                    .toLong()
                                    .coerceAtLeast(MAGNET_ASSIST_MIN_CONFIRMATION_MS)
                        }
                        STATE_STOPPED -> {
                            magnetAssistNote = "disagree"
                            requiredMovingConfirmationMs =
                                (MOVING_CONFIRMATION_MS * MAGNET_ASSIST_DISAGREE_MULTIPLIER).toLong()
                        }
                        else -> {
                            requiredMovingConfirmationMs = MOVING_CONFIRMATION_MS
                        }
                    }
                    if (movingCandidateElapsedMs >= requiredMovingConfirmationMs) {
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

        if (magnetCandidateState != null) {
            magnetCandidateVotes.addLast(MagnetCandidateVote(now, magnetCandidateState))
        }
        while (
            magnetCandidateVotes.firstOrNull()?.timestampMs
                ?.let { it < now - MAGNET_INDEPENDENT_WINDOW_MS } == true
        ) {
            magnetCandidateVotes.removeFirst()
        }
        val stoppedVotes = magnetCandidateVotes.count { it.state == STATE_STOPPED }
        val movingVotes = magnetCandidateVotes.count { it.state == STATE_MOVING }
        val totalVotes = stoppedVotes + movingVotes
        if (totalVotes >= MAGNET_INDEPENDENT_MIN_SAMPLES) {
            val majorityState = when {
                movingVotes > stoppedVotes -> STATE_MOVING
                stoppedVotes > movingVotes -> STATE_STOPPED
                else -> null
            }
            val majorityRatio = maxOf(stoppedVotes, movingVotes).toDouble() / totalVotes
            if (
                majorityState != null &&
                majorityState != stableTrainState &&
                majorityRatio >= MAGNET_INDEPENDENT_MAJORITY_RATIO
            ) {
                stableTrainState = majorityState
                stopCandidateSince = null
                movingCandidateSince = null
                magnetAssistNote = "trigger"
                reason = "magnet-independent-trigger"
                magnetCandidateVotes.clear()
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
            micCrestFactor = micCrestFactor,
            micChimeCandidate = micChimeCandidate,
            micBaselineRms = micBaselineRms,
            micRmsBumpRatio = micRmsBumpRatio,
            micRmsBumpCandidate = micRmsBumpCandidate,
            magnetMagnitudeJitter = magnetMagnitudeJitter,
            magnetJitterSmoothed = magnetJitterSmoothed,
            magnetCandidateState = magnetCandidateState,
            magnetAssistNote = magnetAssistNote,
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
        micCrestFactor: Double,
        micChimeCandidate: Boolean,
        micBaselineRms: Double?,
        micRmsBumpRatio: Double,
        micRmsBumpCandidate: Boolean,
        magnetMagnitudeJitter: Double?,
        magnetJitterSmoothed: Double?,
        magnetCandidateState: String?,
        magnetAssistNote: String,
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
            micCrestFactor = micCrestFactor,
            micChimeCandidate = micChimeCandidate,
            micBaselineRms = micBaselineRms,
            micRmsBumpRatio = micRmsBumpRatio,
            micRmsBumpCandidate = micRmsBumpCandidate,
            magnetMagnitudeJitter = magnetMagnitudeJitter,
            magnetJitterSmoothed = magnetJitterSmoothed,
            magnetCandidateState = magnetCandidateState,
            magnetAssistNote = magnetAssistNote,
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

        // Percentiles are computed separately within each train-state label.
        // Sorting the combined history and taking one global P25/P70 (the old
        // approach) let whichever state had more samples in the last 3 minutes
        // dominate the split point: a long station dwell (mostly "停站" samples)
        // could push the "运行" threshold down toward ordinary stop-noise levels,
        // making loud-but-stationary noise (crowded platform, announcements) read
        // as the train moving. Deriving each threshold only from its own label's
        // samples keeps it anchored to that state's actual noise level regardless
        // of how the two populations are split in the window.
        val stoppedRms = micHistory.mapNotNull { if (it.trainState == STATE_STOPPED) it.rms else null }
            .sorted()
        val movingRms = micHistory.mapNotNull { if (it.trainState == STATE_MOVING) it.rms else null }
            .sorted()
        if (stoppedRms.size < MIN_SAMPLES_PER_STATE || movingRms.size < MIN_SAMPLES_PER_STATE) {
            return
        }

        val p75Stopped = percentile(stoppedRms, 0.75)
        val p25Moving = percentile(movingRms, 0.25)
        var targetStop = p75Stopped.coerceIn(MIN_STOP_THRESHOLD, MAX_STOP_THRESHOLD)
        var targetMoving = p25Moving.coerceIn(MIN_MOVING_THRESHOLD, MAX_MOVING_THRESHOLD)

        if (targetMoving - targetStop < MIN_HYSTERESIS_GAP) {
            targetMoving = (targetStop + MIN_HYSTERESIS_GAP)
                .coerceIn(MIN_MOVING_THRESHOLD, MAX_MOVING_THRESHOLD)
            if (targetMoving - targetStop < MIN_HYSTERESIS_GAP) {
                targetStop = (targetMoving - MIN_HYSTERESIS_GAP)
                    .coerceIn(MIN_STOP_THRESHOLD, MAX_STOP_THRESHOLD)
            }
        }

        // On first activation there's nothing to smooth from yet, so jump straight
        // to the computed value instead of easing 8% of the way from the fixed
        // default every 5s (which took over a minute to become meaningfully
        // different from the fixed thresholds it's replacing).
        dynamicStopThreshold = dynamicStopThreshold?.let { smooth(it, targetStop) } ?: targetStop
        dynamicMovingThreshold = dynamicMovingThreshold?.let { smooth(it, targetMoving) } ?: targetMoving
        lastP25 = p75Stopped
        lastP70 = p25Moving
        lastThresholdUpdateAt = now
    }

    private fun maybeUpdateDynamicMagnetThresholds(now: Long) {
        if (lastMagnetThresholdUpdateAt != 0L &&
            now - lastMagnetThresholdUpdateAt < MAGNET_THRESHOLD_UPDATE_INTERVAL_MS
        ) {
            return
        }

        // Same per-state-only percentile rationale as the mic thresholds
        // above: deriving each threshold only from its own label's samples
        // keeps it anchored regardless of how the "运行"/"停站" population is
        // split in the recent window.
        val stoppedJitter = magnetHistory
            .mapNotNull { if (it.trainState == STATE_STOPPED) it.jitterSmoothed else null }
            .sorted()
        val movingJitter = magnetHistory
            .mapNotNull { if (it.trainState == STATE_MOVING) it.jitterSmoothed else null }
            .sorted()
        if (stoppedJitter.size < MAGNET_MIN_SAMPLES_PER_STATE || movingJitter.size < MAGNET_MIN_SAMPLES_PER_STATE) {
            return
        }

        val p75Stopped = percentile(stoppedJitter, 0.75)
        val p25Moving = percentile(movingJitter, 0.25)
        var targetStop = p75Stopped.coerceIn(MAGNET_MIN_STOP_THRESHOLD, MAGNET_MAX_STOP_THRESHOLD)
        var targetMoving = p25Moving.coerceIn(MAGNET_MIN_MOVING_THRESHOLD, MAGNET_MAX_MOVING_THRESHOLD)

        if (targetMoving - targetStop < MAGNET_MIN_HYSTERESIS_GAP) {
            targetMoving = (targetStop + MAGNET_MIN_HYSTERESIS_GAP)
                .coerceIn(MAGNET_MIN_MOVING_THRESHOLD, MAGNET_MAX_MOVING_THRESHOLD)
            if (targetMoving - targetStop < MAGNET_MIN_HYSTERESIS_GAP) {
                targetStop = (targetMoving - MAGNET_MIN_HYSTERESIS_GAP)
                    .coerceIn(MAGNET_MIN_STOP_THRESHOLD, MAGNET_MAX_STOP_THRESHOLD)
            }
        }

        dynamicMagnetStopThreshold = dynamicMagnetStopThreshold?.let { smooth(it, targetStop, MAGNET_THRESHOLD_SMOOTHING) }
            ?: targetStop
        dynamicMagnetMovingThreshold = dynamicMagnetMovingThreshold
            ?.let { smooth(it, targetMoving, MAGNET_THRESHOLD_SMOOTHING) }
            ?: targetMoving
        lastMagnetThresholdUpdateAt = now
    }

    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun smooth(current: Double, target: Double, factor: Double = THRESHOLD_SMOOTHING): Double =
        current + factor * (target - current)

    private fun combinedState(trainState: String, playerActive: Boolean): String =
        if (trainState == STATE_STOPPED && playerActive) {
            STATE_STOPPED_PLAYER_ACTIVE
        } else {
            trainState
        }
}
