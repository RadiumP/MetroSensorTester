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
    // samples and micP70 is the P25 of recent "运行"-only samples — the raw
    // percentiles the dynamic thresholds below are derived from.
    val micP25: Double?,
    val micP70: Double?,
    val validMicSampleCount: Int,
    // Cheap "did we maybe just hear the fixed station announcement chime"
    // signal: a station announcement is loud but brief, so it barely moves
    // the windowed RMS average while still producing a sharp instantaneous
    // peak — i.e. an abnormally high peak/rms ratio for that tick. This is
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
    // shows a bump while the recent baseline itself was quiet — once the
    // train is already loud (moving), both this and the announcement/alarm
    // sound get buried in ambient noise and the ratio stays ~1.0x.
    val micBaselineRms: Double?,
    val micRmsBumpRatio: Double,
    val micRmsBumpCandidate: Boolean,
    // Tick-to-tick change in raw magnetometer field strength (orientation-
    // independent — unlike compass_heading_deg, which also picks up the
    // phone's own rotation when the player is holding/moving it). Field
    // test (2026-08-28) found this reads consistently lower while stopped
    // than while moving (2.3x-4.6x higher moving, across two rides) — a
    // plausible read is electromagnetic interference from the traction
    // motor while it's actually drawing power. Null when no magnetometer
    // sample is available. Logged only; not yet used by the train-state
    // decision.
    val magnetMagnitudeJitter: Double?,
    // Smoothed (median over the last MAGNET_SMOOTHING_WINDOW_MS) version of
    // magnetMagnitudeJitter above. The raw per-tick jitter overlaps too much
    // between "运行"/"停站" to threshold directly — across 11 real rides the
    // moving/stopped medians separated cleanly (1.5x-5x) but the P25-moving
    // vs P75-stopped quartiles overlapped in most of them. The windowed
    // median is far less noisy. Null until enough samples have accumulated
    // in the window.
    val magnetJitterSmoothed: Double?,
    // "运行"/"停站"/null (ambiguous, or not enough data yet) candidate
    // reading from the magnet channel alone, using the same per-state
    // dynamic-threshold technique as the mic channel below. This does NOT
    // independently drive train_state — see magnetAssistNote.
    val magnetCandidateState: String?,
    // Whether/how the magnet candidate above adjusted the mic-driven
    // confirmation timer this tick: "agree" shortens the remaining
    // confirmation time, "disagree" lengthens it, "none" leaves it
    // unchanged (no active mic candidate this tick, or magnet has no
    // opinion). "trigger" is the rare last-resort case: a rolling-window
    // majority of magnet candidate reads disagreed with the held state
    // (see MAGNET_INDEPENDENT_WINDOW_MS's doc) and forced the transition
    // itself, because mic never even attempted a candidate for it. Mic
    // RMS otherwise stays the primary decision signal. "primary" means
    // the magnet channel drove train_state directly this tick, bypassing
    // the mic assist entirely (see MAGNET_PRIMARY_ENABLED_DEFAULT).
    val magnetAssistNote: String,
    // "校准中" / "可用" / "不可用" verdict of the magnet channel's own
    // usability test. See MAGNET_CALIBRATION_MIN_SAMPLES's doc for what
    // the test actually measures and why it is not a fixed-length window
    // like the GPS one.
    val magnetCalibrationStatus: String,
    // The two observed percentiles the usability test above compares
    // against the effective jitter thresholds, echoed for CSV inspection.
    // Null until enough smoothed samples exist to compute them.
    val magnetCalibrationLowJitter: Double?,
    val magnetCalibrationHighJitter: Double?,
    // Confirmation timers of the magnet channel's own candidate state
    // machine. Only ever non-zero while the magnet channel is the
    // authority (magnetAssistNote == "primary").
    val magnetStopCandidateElapsedMs: Long,
    val magnetMovingCandidateElapsedMs: Long,
    // Raw SensorManager accuracy class of the magnetometer (0 unreliable
    // through 3 high), echoed for CSV inspection only. Deliberately NOT
    // part of the usability test: on the 2026-09-06 ride this sat at 3
    // (high) for 90% of the ride while the channel itself was useless,
    // so it predicts nothing about whether the jitter signal actually
    // separates the two train states.
    val magnetAccuracy: Int?,
    // Which jitter thresholds the magnet channel actually used this tick.
    // Previously none of this reached the CSV, which made the 2026-09-12 logs
    // impossible to interpret: there was no way to tell whether a ride had
    // been running on the fixed fallbacks or on the learned per-state ones.
    val magnetDynamicStopThreshold: Double?,
    val magnetDynamicMovingThreshold: Double?,
    val magnetEffectiveStopThreshold: Double,
    val magnetEffectiveMovingThreshold: Double,
    val magnetThresholdMode: String,
    // Barometer channel, logged only: this does NOT feed the train-state
    // decision yet. Tick-to-tick change in cabin air pressure, and the
    // windowed median of it. A train running through a tunnel acts as a
    // piston and stirs the air, so the pressure reading jitters while moving
    // and settles while stopped. Offline check against the two 2026-09-12
    // rides (the only ones with mark-derived ground truth so far) put this
    // ahead of both existing channels: stopped-vs-moving medians separated
    // 2.4x and 7.2x, against 1.5x/2.1x for magnet jitter and 1.5x/1.8x for
    // mic RMS, and a single fixed threshold scored 84.5%/87.2% where magnet
    // scored 75.4%/79.5%. It was also the only channel that held up on the
    // ride whose magnetometer was swamped by a power bank, which is the
    // point of interest: its failure modes do not overlap with the other
    // two. Being logged first rather than wired in because its absolute
    // scale drifts between rides just like the other channels' does (the
    // 2026-09-11 ride sat around 0.0175 while stopped, the 2026-09-12 one
    // around 0.0038), and two ground-truth rides is far too thin to pick a
    // threshold from.
    val pressureChangeRate: Double?,
    val pressureChangeRateSmoothed: Double?,
    // Three-channel fusion (2026-09-12). Each channel's raw scale is useless
    // across rides (the same phone read a stopped-state magnet jitter of 0.71
    // on one ride and 2.14 on another), so no channel is thresholded on its
    // own value any more. Instead each is converted to its percentile rank
    // inside a trailing FUSION_RANK_WINDOW_MS window, which asks only "is
    // this low or high compared with the last minute" and is therefore
    // immune to both between-ride and within-ride scale drift. Null when
    // that channel has no reading, or when its window has not filled to
    // FUSION_RANK_MIN_SAMPLES yet.
    val magnetRank: Double?,
    val micRank: Double?,
    val pressureRank: Double?,
    // Mean of whichever ranks above are available, and how many that was.
    // This is the number the decision is actually made on.
    val fusionScore: Double?,
    val fusionChannelCount: Int,
    val fusionStopCandidateElapsedMs: Long,
    val fusionMovingCandidateElapsedMs: Long,
    // Milliseconds still to run on the post-transition lockout, 0 when it is
    // not in force. Logged so a CSV shows when a transition was held back by
    // it rather than by a lack of evidence.
    val fusionLockoutRemainingMs: Long,
    // Echoes of this tick's GPS input, for CSV inspection/comparison. Null
    // whenever no fix was available this tick (no permission, no provider,
    // underground, or the last fix went stale — see GpsCollector).
    val gpsSpeedMps: Double?,
    val gpsAccuracyM: Double?,
    // "校准中" (still inside the one-time accuracy-test window) / "可用"
    // (test passed — GPS is now authoritative for the rest of the ride) /
    // "不可用" (test failed — permanently ignoring GPS for this ride). See
    // GPS_CALIBRATION_WINDOW_MS's doc for the test itself.
    val gpsCalibrationStatus: String,
    // "运行"/"停站"/null candidate reading from GPS speed alone, using the
    // same fixed-speed-band idea as the mic/magnet channels. Only ever
    // non-null while gpsCalibrationStatus == "可用".
    val gpsCandidateState: String?,
    val gpsStopCandidateElapsedMs: Long,
    val gpsMovingCandidateElapsedMs: Long,
    val reason: String,
)

class InferenceEngine(
    // Constructor-injected rather than read straight off the companion
    // constants so the test suite can still exercise both configurations;
    // production callers use the defaults, which are the field-test setting.
    val micDecisionEnabled: Boolean = MIC_DECISION_ENABLED_DEFAULT,
    val magnetPrimaryEnabled: Boolean = MAGNET_PRIMARY_ENABLED_DEFAULT,
    val fusionEnabled: Boolean = FUSION_ENABLED_DEFAULT,
) {
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
        // much higher ratio is the cheap tell — no new audio processing
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
        // jitter. Not tuned much beyond that — expect to revisit once more
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
        // test — a quiet at-grade light-rail ride spent >97% of the ride
        // stuck showing "停站" because genuine moving noise almost never
        // crossed the moving threshold, so mic itself never even attempted a
        // moving candidate for the magnet assist above to speed up).
        //
        // First cut of this (an unbroken-streak requirement) turned out too
        // strict: real-ride field test (2026-09-05) found a 137-second
        // mis-held "停站" stretch that contained a clean ~20s window where
        // 93-100% of magnet_candidate_state readings said "运行" — genuine
        // signal — but no single unbroken run inside it reached even 5
        // seconds, because the smoothed magnet reading itself flickers
        // tick-to-tick near the threshold. A rolling-window majority vote
        // tolerates that flicker while still requiring strong, sustained
        // evidence: count "运行"/"停站" candidate reads over the trailing
        // window and only trigger once enough of them (MIN_SAMPLES) point
        // the same way by a clear supermajority (MAJORITY_RATIO).
        const val MAGNET_INDEPENDENT_WINDOW_MS = 20_000L
        const val MAGNET_INDEPENDENT_MIN_SAMPLES = 20
        const val MAGNET_INDEPENDENT_MAJORITY_RATIO = 0.75

        // Magnet channel usability test ("精度判断", 2026-09-10 request).
        // Same abandon-or-adopt shape as the GPS calibration below, but the
        // criterion has to be different, for two reasons found in the
        // 2026-09-05/06/08 ride logs:
        //
        // 1. There is no per-sample accuracy number to threshold the way GPS
        //    has meters. The SensorManager accuracy class looks like the
        //    obvious analogue and is not: the 2026-09-06 ride reported
        //    accuracy 3 (high) for 90% of its samples while its jitter
        //    signal was in fact unusable (P90 of the smoothed jitter reached
        //    only 0.85, so it never once crossed MAGNET_MOVING_JITTER_
        //    THRESHOLD; the channel emitted "停站" 2706 times against "运行"
        //    134 for the whole ride). It is still logged, just not tested.
        //
        // 2. What actually matters is whether this phone's jitter signal
        //    straddles the decision band at all, and that cannot be judged
        //    from a fixed window at ride start the way GPS accuracy can: at
        //    t=0 the train is usually still standing in the platform, so a
        //    narrow spread early on means "hasn't moved yet", not "bad
        //    sensor". So the test is open-ended instead of fixed-length: it
        //    passes as soon as the observed distribution has been seen both
        //    below the stop threshold and above the moving threshold, and
        //    only gives up if MAX_WINDOW_MS goes by without that happening.
        //
        // Checked against the three rides above: 09-05 passes at 28.8s,
        // 09-08 at 16.0s, and 09-06 is correctly rejected.
        const val MAGNET_CALIBRATION_MIN_SAMPLES = 60
        const val MAGNET_CALIBRATION_MAX_WINDOW_MS = 180_000L
        const val MAGNET_CALIBRATION_LOW_PERCENTILE = 0.10
        const val MAGNET_CALIBRATION_HIGH_PERCENTILE = 0.90

        // Confirmation windows for the magnet channel's own candidate state
        // machine, used only when it is the authority (see
        // MAGNET_PRIMARY_ENABLED_DEFAULT). The smoothed magnet reading flickers
        // tick-to-tick near the threshold, so these are longer than the mic
        // equivalents. Swept offline over the 09-05/09-08 logs: at
        // 2000/1500 the magnet-only classifier produced 91/105 transitions
        // with 12 segments under 5 seconds in each ride (visible flicker),
        // at 4000/3000 it settles to 61/69 transitions with only 4 and 2
        // such segments, and 5000/4000 removes the rest at the cost of
        // roughly a third of the transitions. 4000/3000 is the starting
        // point; if the field test still shows flicker, 5000/4000 is the
        // next step. Note this stacks on top of MAGNET_SMOOTHING_WINDOW_MS,
        // so the end-to-end reaction time to a real transition is several
        // seconds either way.
        const val MAGNET_STOP_CONFIRMATION_MS = 4_000L
        const val MAGNET_MOVING_CONFIRMATION_MS = 3_000L

        const val MAGNET_STATUS_CALIBRATING = "校准中"
        const val MAGNET_STATUS_USABLE = "可用"
        const val MAGNET_STATUS_UNUSABLE = "不可用"

        // Barometer channel smoothing. Deliberately the same window and
        // minimum-sample count as the magnet channel above, so the CSV column
        // can be compared against magnet_jitter_smoothed directly and the
        // offline replays that picked these numbers stay reproducible.
        // The barometer reports more slowly than the 250ms tick on some
        // devices, so consecutive ticks often read an identical value and the
        // per-tick delta is then legitimately 0; the windowed median absorbs
        // that.
        const val PRESSURE_SMOOTHING_WINDOW_MS = 4_000L
        const val PRESSURE_SMOOTHING_MIN_SAMPLES = 4

        // Mic is smoothed far less than the other two channels: its raw RMS
        // is already a windowed average inside AudioCollector, so it only
        // needs enough median filtering to drop single-tick spikes.
        const val MIC_SMOOTHING_WINDOW_MS = 1_000L

        // Three-channel fusion. Every one of these numbers came out of a
        // leave-one-out sweep over the six mark-labelled rides (2026-08-17
        // through 2026-09-12): each fold picked its hyper-parameters on the
        // other five rides and was scored on the held-out one. All six folds
        // independently converged on exactly this combination, which is the
        // main reason to believe it is a real setting rather than a fit to
        // the sample. Held-out accuracy averaged 91.9% with a worst ride of
        // 83.2%, against 88.2%/73.7% for the best single channel; on the two
        // rides that had actually been failing in the field it went from a
        // measured 66.4% and 79.5% to 83.5% and 83.2%.
        //
        // Note the rank normalisation and the fusion only work as a pair.
        // Rank-normalising a single channel is WORSE than thresholding its
        // raw value (magnet dropped from 88.2% to 78.7%), because the
        // normalisation adds noise of its own. What it buys is that the three
        // channels come out on one common scale, and averaging them then
        // cancels that noise and more. Splitting the pair in either direction
        // loses the gain.
        const val FUSION_RANK_WINDOW_MS = 90_000L
        const val FUSION_RANK_MIN_SAMPLES = 40
        const val FUSION_STOP_SCORE = 0.30
        const val FUSION_MOVING_SCORE = 0.65
        const val FUSION_STOP_CONFIRMATION_MS = 5_000L
        const val FUSION_MOVING_CONFIRMATION_MS = 3_000L

        // After a transition is confirmed, refuse to confirm another one for
        // this long. Aimed at a specific and repeatable field failure: a
        // train slowing hard for a signal or a speed restriction without
        // actually stopping. Both channels genuinely drop together (the
        // 2026-09-19 22:03 ride went from a magnet jitter of 3.57 down to
        // 1.54 and back up to 8.30 inside 30 seconds), so this is real
        // deceleration rather than sensor noise, and the percentile rank has
        // no way to tell "quietest in the last 90 seconds" apart from
        // "actually stopped": after a couple of minutes at line speed, a mere
        // slowdown ranks at the bottom of the window. The resulting false
        // stops lasted 7 to 23 seconds, while genuine segments in the twelve
        // labelled rides never ran shorter than 26 seconds (P10 36s, median
        // 69s), so a lockout in between removes the former without touching
        // the latter.
        //
        // Swept over those twelve rides against a stability-first objective
        // (false flips first, missed transitions weighted heaviest, latency
        // last): false flips fell from 14 to 10 with missed transitions
        // unchanged at 2 and median latency up only 0.2s. Eleven of the
        // twelve leave-one-out folds independently picked this value. Note
        // the sub-26s "true" segments in the data are button mis-presses
        // rather than train behaviour (one ride has 停站 at 9s and 运行 at
        // 11s), which is why suppressing them costs no real transitions.
        //
        // This treats a symptom, not the cause: the rank normalisation's
        // blindness to absolute scale is still there. Lengthening the rank
        // window to 120~400s was tried and made everything worse, and an
        // absolute floor does not separate (real stops median 0.95 against
        // 1.35 for the false ones).
        const val FUSION_STATE_LOCKOUT_MS = 0L

        // Fast reversal. If the score swings back to the state held before
        // the last transition, and does so soon after it, confirm the way
        // back on this much shorter timer: a transition that wants undoing
        // within seconds is itself evidence that it should not have happened.
        //
        // This replaced a 25s lockout that tried to suppress the same false
        // transitions by refusing to confirm anything for a while after one.
        // The lockout did cut their number, but it also blocked the recovery,
        // so each surviving one lasted far longer; measured over the twelve
        // labelled rides, the median false episode ran 42.3s under the
        // lockout against 34.0s with nothing at all. Reversal goes the other
        // way and shortens them to 9.5s, roughly a quarter, which is what
        // matters when the game layer can debounce a brief wrong state on its
        // own but cannot do anything about a long one.
        //
        // Swept with leave-one-out over those rides against "error time the
        // game would actually notice" (episodes longer than its 3s debounce)
        // plus the median false-episode length: 10 of the 12 folds picked
        // exactly these values and all 12 picked this confirmation time.
        //
        // The cost is real and worth knowing: total noticed error time rises
        // about 22s and transition latency about 52s across all twelve rides
        // (roughly 0.7s per transition), because the same mechanism
        // occasionally undoes a CORRECT transition when the score wobbles
        // just after it, and the state then has to settle again.
        const val FUSION_REVERT_CONFIRMATION_MS = 1_500L
        const val FUSION_REVERT_WINDOW_MS = 20_000L

        // Switched on by default (2026-09-12), replacing the magnet-primary
        // field-test configuration below it. Turning this off falls back to
        // whatever MIC_DECISION_ENABLED_DEFAULT / MAGNET_PRIMARY_ENABLED_DEFAULT
        // select, so the previous behaviour stays reachable for comparison.
        // GPS still outranks everything when its own calibration passes.
        const val FUSION_ENABLED_DEFAULT = true

        // Magnet and barometer are the fusion's channels; mic is deliberately
        // NOT one of them, and only steps in when one of those two has no
        // reading at all (a phone with no barometer, say). That is not the
        // obvious choice, so: the first sweep, run against a reference
        // implementation that fed the mic channel its raw RMS, did favour all
        // three. Re-running the same sweep against a reference made bit-exact
        // with this file, mic included, flipped the answer, because the real
        // engine drops mic samples that fail its validity check and the
        // resulting gappy channel drags the average around. Held-out accuracy
        // over the six labelled rides: magnet+barometer 92.7% mean with a
        // worst ride of 85.2%, against 89.7%/75.9% for all three, 87.9%/78.2%
        // for magnet+mic and 86.6%/65.6% for mic+barometer. Five of the six
        // folds also agreed on the constants above for this pairing, where
        // the three-channel folds could not agree, which is the second reason
        // to prefer it.
        //
        // Worth knowing when reading a CSV: mic_rank is still computed and
        // logged every tick even though it is normally not in the average,
        // so a later sweep can revisit this without another field test.
        val FUSION_PRIMARY_CHANNELS = setOf("magnet", "pressure")

        // Defaults for the two field-test switches (2026-09-10): with the
        // mic channel off, a ride isolates what the magnet channel can do on
        // its own. Both flipped back (mic true / magnet false) restores the
        // mic-primary plus magnet-assist fusion exactly; nothing else in
        // this file branches on them. Mic RMS keeps being sampled, logged
        // and folded into its dynamic-threshold history either way, so a
        // magnet-only ride still records everything needed to compare the
        // two channels afterwards.
        const val MIC_DECISION_ENABLED_DEFAULT = false
        const val MAGNET_PRIMARY_ENABLED_DEFAULT = true

        private const val DYNAMIC_HISTORY_MS = 180_000L
        private const val MIN_DYNAMIC_SAMPLES = 240
        private const val MIN_SAMPLES_PER_STATE = 20
        private const val THRESHOLD_UPDATE_INTERVAL_MS = 5_000L
        private const val THRESHOLD_SMOOTHING = 0.08
        private const val MIN_HYSTERESIS_GAP = 0.0002
        // Real-ride field test (2026-09-02): an at-grade light-rail ride's
        // whole cabin was quiet enough that genuine moving-noise RMS sat
        // around 0.0013 — below the old MIN_MOVING_THRESHOLD (0.0016), so
        // the dynamic threshold could never track down to it even once
        // enough "运行"-labeled history existed. Lowered both bounds enough
        // to cover that ride while still leaving MIN_HYSTERESIS_GAP of room
        // above the stop side.
        private const val MIN_STOP_THRESHOLD = 0.0005
        private const val MAX_STOP_THRESHOLD = 0.0016
        private const val MIN_MOVING_THRESHOLD = 0.0010
        private const val MAX_MOVING_THRESHOLD = 0.0030

        // GPS as an optional primary signal ("位准", 2026-09-06 request):
        // unlike the magnet channel above, this is not an assist — once a
        // fix proves accurate enough during a one-time test at the start of
        // the ride, GPS-derived ground speed becomes the SOLE authority for
        // train_state for the rest of that ride, and the mic/magnet fusion
        // above is bypassed entirely (mic/magnet keep being computed and
        // logged for comparison, and mic history keeps accumulating in the
        // background, but neither can move stableTrainState anymore).
        //
        // This is deliberately opt-in per ride rather than a hard
        // requirement: if the window ends without enough accurate fixes —
        // the expected case underground, where GPS often gets no fix at all
        // — GPS is given up on for the rest of that ride and the mic/magnet
        // logic stays authoritative, exactly as if GPS didn't exist. The
        // decision is made once and never re-tried mid-ride, so a route that
        // starts underground (test fails) and later surfaces will not
        // retroactively pick GPS back up — acceptable for now since the
        // routes this matters for (a quiet at-grade light rail) have GPS
        // available from the very start.
        //
        // ACCESS_COARSE_LOCATION alone (already used for the one-shot
        // magnetic-declination fix in SensorCollector) only unlocks
        // NETWORK_PROVIDER, whose accuracy is routinely worse than
        // GPS_ACCURACY_THRESHOLD_M — ACCESS_FINE_LOCATION is required for
        // this to ever pass the test. See GpsCollector for the actual fix
        // subscription.
        const val GPS_CALIBRATION_WINDOW_MS = 20_000L
        const val GPS_CALIBRATION_MIN_SAMPLES = 5
        const val GPS_ACCURACY_THRESHOLD_M = 20.0
        const val GPS_CALIBRATION_MIN_GOOD_RATIO = 0.6

        // First-cut speed thresholds/confirmation windows — not yet
        // validated against real GPS logs the way the mic/magnet numbers
        // above were (no field data with GPS enabled exists yet). GPS speed
        // is a fairly direct measurement of ground speed (unlike the mic RMS
        // proxy above), so a shorter confirmation window than the mic's
        // should still be safe against noise; expect to revisit both once
        // real ride logs come in.
        const val GPS_MOVING_SPEED_MPS = 1.4
        const val GPS_STOP_SPEED_MPS = 0.5
        const val GPS_STOP_CONFIRMATION_MS = 2_000L
        const val GPS_MOVING_CONFIRMATION_MS = 1_500L

        const val GPS_STATUS_CALIBRATING = "校准中"
        const val GPS_STATUS_USABLE = "可用"
        const val GPS_STATUS_UNUSABLE = "不可用"

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
    private data class RecentPressureSample(val timestampMs: Long, val change: Double)
    private data class RecentMicSmoothingSample(val timestampMs: Long, val rms: Double)

    /**
     * Trailing window that reports where a new value sits inside its own
     * recent history, as a 0..1 percentile. Ties are averaged and the new
     * value counts itself, matching the reference implementation the fusion
     * constants were swept with, so the Kotlin engine and the offline replay
     * of the same ride agree.
     */
    private class RankWindow(private val windowMs: Long, private val minSamples: Int) {
        private val timestamps = ArrayDeque<Long>()
        private val values = ArrayDeque<Double>()

        fun clear() {
            timestamps.clear()
            values.clear()
        }

        fun pushAndRank(value: Double, now: Long): Double? {
            while (timestamps.firstOrNull()?.let { it < now - windowMs } == true) {
                timestamps.removeFirst()
                values.removeFirst()
            }
            timestamps.addLast(now)
            values.addLast(value)
            if (values.size < minSamples) return null
            var below = 0
            var equal = 0
            for (v in values) {
                if (v < value) below++ else if (v == value) equal++
            }
            return (below + (equal + 1) / 2.0) / values.size
        }
    }

    private data class FusionOutcome(
        val magnetRank: Double?,
        val micRank: Double?,
        val pressureRank: Double?,
        val score: Double?,
        val channelCount: Int,
        val stopCandidateElapsedMs: Long,
        val movingCandidateElapsedMs: Long,
        val lockoutRemainingMs: Long,
        // Non-null only when the fusion actually owns the decision this tick.
        val reason: String?,
    )
    private data class MagnetCandidateVote(val timestampMs: Long, val state: String)
    private data class MagnetOutcome(
        val calibrationStatus: String,
        val usable: Boolean,
        val lowJitter: Double?,
        val highJitter: Double?,
        val stopCandidateElapsedMs: Long,
        val movingCandidateElapsedMs: Long,
        // Non-null only when the magnet channel is authoritative this tick;
        // this then becomes the tick's overall decision reason and the
        // mic block is bypassed.
        val reason: String?,
    )
    private data class GpsOutcome(
        val calibrationStatus: String,
        val usable: Boolean,
        val candidateState: String?,
        val stopCandidateElapsedMs: Long,
        val movingCandidateElapsedMs: Long,
        // Non-null only when GPS is authoritative this tick (calibration
        // passed); this becomes the tick's overall decision reason and
        // bypasses the mic/magnet block entirely.
        val reason: String?,
    )

    private val micHistory = ArrayDeque<MicPoint>()
    // Short rolling window feeding the baseline-bump detector — separate
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
    private var lastPressureHpa: Double? = null
    private val recentPressureChangeSamples = ArrayDeque<RecentPressureSample>()
    private val recentMicSmoothingSamples = ArrayDeque<RecentMicSmoothingSample>()
    private val magnetRankWindow = RankWindow(FUSION_RANK_WINDOW_MS, FUSION_RANK_MIN_SAMPLES)
    private val micRankWindow = RankWindow(FUSION_RANK_WINDOW_MS, FUSION_RANK_MIN_SAMPLES)
    private val pressureRankWindow = RankWindow(FUSION_RANK_WINDOW_MS, FUSION_RANK_MIN_SAMPLES)
    private var fusionLastTransitionAt: Long? = null
    // State held immediately before the last confirmed transition, so a swing
    // straight back to it can be recognised as a reversal.
    private var fusionPreviousState: String? = null
    private var fusionStopCandidateSince: Long? = null
    private var fusionMovingCandidateSince: Long? = null
    private val magnetCandidateVotes = ArrayDeque<MagnetCandidateVote>()
    // Smoothed-jitter samples feeding the one-time magnet usability test.
    // Bounded by MAGNET_CALIBRATION_MAX_WINDOW_MS worth of ticks and dropped
    // as soon as the verdict is in, so this never grows for a long ride.
    private val magnetCalibrationSamples = mutableListOf<Double>()
    private var magnetFirstSampleAt: Long? = null
    // null = test still running, true/false = decided and locked for the
    // rest of the ride (same one-shot contract as gpsUsable below).
    private var magnetUsable: Boolean? = null
    private var magnetCalibrationLowJitter: Double? = null
    private var magnetCalibrationHighJitter: Double? = null
    private var magnetStopCandidateSince: Long? = null
    private var magnetMovingCandidateSince: Long? = null
    private var gpsFirstUpdateAt: Long? = null
    private val gpsCalibrationAccuracySamples = mutableListOf<Double>()
    // null = calibration window still open (or not yet started); true/false
    // = decided, locked for the rest of the ride (see GPS_CALIBRATION_WINDOW_MS
    // doc above).
    private var gpsUsable: Boolean? = null
    private var gpsStopCandidateSince: Long? = null
    private var gpsMovingCandidateSince: Long? = null

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
        lastPressureHpa = null
        recentPressureChangeSamples.clear()
        recentMicSmoothingSamples.clear()
        magnetRankWindow.clear()
        micRankWindow.clear()
        pressureRankWindow.clear()
        fusionLastTransitionAt = null
        fusionPreviousState = null
        fusionStopCandidateSince = null
        fusionMovingCandidateSince = null
        magnetCalibrationSamples.clear()
        magnetFirstSampleAt = null
        magnetUsable = null
        magnetCalibrationLowJitter = null
        magnetCalibrationHighJitter = null
        magnetStopCandidateSince = null
        magnetMovingCandidateSince = null
        gpsFirstUpdateAt = null
        gpsCalibrationAccuracySamples.clear()
        gpsUsable = null
        gpsStopCandidateSince = null
        gpsMovingCandidateSince = null
    }

    fun update(
        micRms: Double,
        micValid: Boolean,
        micPeak: Double,
        accelRms: Double,
        gyroDegreesRms: Double,
        magnetMagnitude: Double?,
        now: Long,
        gpsSpeedMps: Double? = null,
        gpsAccuracyM: Double? = null,
        magnetAccuracy: Int? = null,
        pressureHpa: Double? = null,
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

        // Barometer channel: same shape as the magnet jitter above, but its
        // output is only carried through to the CSV (see pressureChangeRate's
        // doc on InferenceResult); nothing below branches on it.
        val pressureChangeRate = if (pressureHpa != null && lastPressureHpa != null) {
            kotlin.math.abs(pressureHpa - lastPressureHpa!!)
        } else {
            null
        }
        if (pressureHpa != null) lastPressureHpa = pressureHpa
        while (
            recentPressureChangeSamples.firstOrNull()?.timestampMs
                ?.let { it < now - PRESSURE_SMOOTHING_WINDOW_MS } == true
        ) {
            recentPressureChangeSamples.removeFirst()
        }
        if (pressureChangeRate != null) {
            recentPressureChangeSamples.addLast(RecentPressureSample(now, pressureChangeRate))
        }
        val pressureChangeRateSmoothed =
            if (recentPressureChangeSamples.size >= PRESSURE_SMOOTHING_MIN_SAMPLES) {
                percentile(recentPressureChangeSamples.map { it.change }.sorted(), 0.5)
            } else {
                null
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
        // Short median of the mic level, used only as the fusion channel's
        // input; the mic-primary path below keeps reading the raw RMS so its
        // long-standing threshold numbers stay meaningful.
        while (
            recentMicSmoothingSamples.firstOrNull()?.timestampMs
                ?.let { it < now - MIC_SMOOTHING_WINDOW_MS } == true
        ) {
            recentMicSmoothingSamples.removeFirst()
        }
        if (micUsable) {
            recentMicSmoothingSamples.addLast(RecentMicSmoothingSample(now, micRms))
        }
        val micSmoothed = if (recentMicSmoothingSamples.isEmpty()) {
            null
        } else {
            percentile(recentMicSmoothingSamples.map { it.rms }.sorted(), 0.5)
        }
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

        val gpsOutcome = updateGpsState(gpsSpeedMps, gpsAccuracyM, now)
        // Runs on every tick regardless of who ends up deciding, so the
        // usability test keeps making progress (and the CSV keeps showing
        // its verdict) even on a ride where GPS took over. GPS outranks it
        // when both are usable, so it only ever claims authority once GPS
        // has not.
        val fusionOutcome = updateFusionState(
            magnetJitterSmoothed = magnetJitterSmoothed,
            micSmoothed = micSmoothed,
            pressureChangeRateSmoothed = pressureChangeRateSmoothed,
            authoritative = fusionEnabled && !gpsOutcome.usable,
            now = now,
        )
        val magnetOutcome = updateMagnetState(
            magnetJitterSmoothed = magnetJitterSmoothed,
            candidateState = magnetCandidateState,
            authoritative = magnetPrimaryEnabled && !gpsOutcome.usable && !fusionEnabled,
            effectiveStopThreshold = effectiveMagnetStopThreshold,
            effectiveMovingThreshold = effectiveMagnetMovingThreshold,
            now = now,
        )
        if (gpsOutcome.usable) {
            // GPS is authoritative for the rest of this ride: bypass the
            // mic/magnet-driven state machine entirely for stableTrainState
            // (updateGpsState already applied this tick's GPS decision to
            // it), but keep mic history accumulating in the background and
            // keep all mic/magnet diagnostic fields populated for CSV
            // comparison against the GPS-driven decision.
            stopCandidateSince = null
            movingCandidateSince = null
            if (
                micUsable && micRms >= MIC_NONZERO_FLOOR &&
                (stableTrainState == STATE_MOVING || stableTrainState == STATE_STOPPED)
            ) {
                micHistory.addLast(MicPoint(now, micRms, stableTrainState))
            }
            return result(
                micRms = micRms,
                playerActive = playerActive,
                playerState = playerState,
                rawTrainState = stableTrainState,
                effectiveStopThreshold = effectiveStopThreshold,
                effectiveMovingThreshold = effectiveMovingThreshold,
                thresholdMode = thresholdMode,
                micLevelRatio = micLevelRatio,
                micAboveMovingThreshold = micAboveMovingThreshold,
                micBelowStopThreshold = micBelowStopThreshold,
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
                magnetOutcome = magnetOutcome,
                magnetAccuracy = magnetAccuracy,
                magnetEffectiveStopThreshold = effectiveMagnetStopThreshold,
                magnetEffectiveMovingThreshold = effectiveMagnetMovingThreshold,
                pressureChangeRate = pressureChangeRate,
                pressureChangeRateSmoothed = pressureChangeRateSmoothed,
                fusionOutcome = fusionOutcome,
                gpsSpeedMps = gpsSpeedMps,
                gpsAccuracyM = gpsAccuracyM,
                gpsCalibrationStatus = gpsOutcome.calibrationStatus,
                gpsCandidateState = gpsOutcome.candidateState,
                gpsStopCandidateElapsedMs = gpsOutcome.stopCandidateElapsedMs,
                gpsMovingCandidateElapsedMs = gpsOutcome.movingCandidateElapsedMs,
                reason = gpsOutcome.reason ?: "gps-hold-state",
            )
        }

        if (fusionOutcome.reason != null) {
            // Fusion owns the decision for this ride; it has already applied
            // this tick's verdict to stableTrainState. The single-channel
            // paths below keep being computed and logged for comparison, and
            // mic history keeps accumulating, but none of them may move the
            // state.
            stopCandidateSince = null
            movingCandidateSince = null
            if (
                micUsable && micRms >= MIC_NONZERO_FLOOR &&
                (stableTrainState == STATE_MOVING || stableTrainState == STATE_STOPPED)
            ) {
                micHistory.addLast(MicPoint(now, micRms, stableTrainState))
            }
            return result(
                micRms = micRms,
                playerActive = playerActive,
                playerState = playerState,
                rawTrainState = stableTrainState,
                effectiveStopThreshold = effectiveStopThreshold,
                effectiveMovingThreshold = effectiveMovingThreshold,
                thresholdMode = thresholdMode,
                micLevelRatio = micLevelRatio,
                micAboveMovingThreshold = micAboveMovingThreshold,
                micBelowStopThreshold = micBelowStopThreshold,
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
                magnetOutcome = magnetOutcome,
                magnetAccuracy = magnetAccuracy,
                magnetEffectiveStopThreshold = effectiveMagnetStopThreshold,
                magnetEffectiveMovingThreshold = effectiveMagnetMovingThreshold,
                pressureChangeRate = pressureChangeRate,
                pressureChangeRateSmoothed = pressureChangeRateSmoothed,
                fusionOutcome = fusionOutcome,
                gpsSpeedMps = gpsSpeedMps,
                gpsAccuracyM = gpsAccuracyM,
                gpsCalibrationStatus = gpsOutcome.calibrationStatus,
                gpsCandidateState = gpsOutcome.candidateState,
                gpsStopCandidateElapsedMs = 0L,
                gpsMovingCandidateElapsedMs = 0L,
                reason = fusionOutcome.reason,
            )
        }

        if (magnetOutcome.reason != null) {
            // Magnet is the authority for this ride: it has already applied
            // this tick's decision to stableTrainState. Mic keeps being
            // sampled and folded into its own history for offline
            // comparison, but cannot move the state.
            stopCandidateSince = null
            movingCandidateSince = null
            if (
                micUsable && micRms >= MIC_NONZERO_FLOOR &&
                (stableTrainState == STATE_MOVING || stableTrainState == STATE_STOPPED)
            ) {
                micHistory.addLast(MicPoint(now, micRms, stableTrainState))
            }
            return result(
                micRms = micRms,
                playerActive = playerActive,
                playerState = playerState,
                rawTrainState = magnetCandidateState ?: stableTrainState,
                effectiveStopThreshold = effectiveStopThreshold,
                effectiveMovingThreshold = effectiveMovingThreshold,
                thresholdMode = thresholdMode,
                micLevelRatio = micLevelRatio,
                micAboveMovingThreshold = micAboveMovingThreshold,
                micBelowStopThreshold = micBelowStopThreshold,
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
                magnetAssistNote = "primary",
                magnetOutcome = magnetOutcome,
                magnetAccuracy = magnetAccuracy,
                magnetEffectiveStopThreshold = effectiveMagnetStopThreshold,
                magnetEffectiveMovingThreshold = effectiveMagnetMovingThreshold,
                pressureChangeRate = pressureChangeRate,
                pressureChangeRateSmoothed = pressureChangeRateSmoothed,
                fusionOutcome = fusionOutcome,
                gpsSpeedMps = gpsSpeedMps,
                gpsAccuracyM = gpsAccuracyM,
                gpsCalibrationStatus = gpsOutcome.calibrationStatus,
                gpsCandidateState = gpsOutcome.candidateState,
                gpsStopCandidateElapsedMs = 0L,
                gpsMovingCandidateElapsedMs = 0L,
                reason = magnetOutcome.reason,
            )
        }

        if (!micDecisionEnabled) {
            // Mic decisions are switched off for this build and magnet is
            // not (yet) authoritative, so nothing may move the state this
            // tick. Mic history still accumulates for offline comparison.
            stopCandidateSince = null
            movingCandidateSince = null
            if (
                micUsable && micRms >= MIC_NONZERO_FLOOR &&
                (stableTrainState == STATE_MOVING || stableTrainState == STATE_STOPPED)
            ) {
                micHistory.addLast(MicPoint(now, micRms, stableTrainState))
            }
            return result(
                micRms = micRms,
                playerActive = playerActive,
                playerState = playerState,
                rawTrainState = stableTrainState,
                effectiveStopThreshold = effectiveStopThreshold,
                effectiveMovingThreshold = effectiveMovingThreshold,
                thresholdMode = thresholdMode,
                micLevelRatio = micLevelRatio,
                micAboveMovingThreshold = micAboveMovingThreshold,
                micBelowStopThreshold = micBelowStopThreshold,
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
                magnetOutcome = magnetOutcome,
                magnetAccuracy = magnetAccuracy,
                magnetEffectiveStopThreshold = effectiveMagnetStopThreshold,
                magnetEffectiveMovingThreshold = effectiveMagnetMovingThreshold,
                pressureChangeRate = pressureChangeRate,
                pressureChangeRateSmoothed = pressureChangeRateSmoothed,
                fusionOutcome = fusionOutcome,
                gpsSpeedMps = gpsSpeedMps,
                gpsAccuracyM = gpsAccuracyM,
                gpsCalibrationStatus = gpsOutcome.calibrationStatus,
                gpsCandidateState = gpsOutcome.candidateState,
                gpsStopCandidateElapsedMs = 0L,
                gpsMovingCandidateElapsedMs = 0L,
                reason = if (magnetOutcome.calibrationStatus == MAGNET_STATUS_UNUSABLE) {
                    "magnet-unusable-mic-disabled-hold-state"
                } else {
                    "magnet-calibrating-mic-disabled-hold-state"
                },
            )
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
                magnetOutcome = magnetOutcome,
                magnetAccuracy = magnetAccuracy,
                magnetEffectiveStopThreshold = effectiveMagnetStopThreshold,
                magnetEffectiveMovingThreshold = effectiveMagnetMovingThreshold,
                pressureChangeRate = pressureChangeRate,
                pressureChangeRateSmoothed = pressureChangeRateSmoothed,
                fusionOutcome = fusionOutcome,
                gpsSpeedMps = gpsSpeedMps,
                gpsAccuracyM = gpsAccuracyM,
                gpsCalibrationStatus = gpsOutcome.calibrationStatus,
                gpsCandidateState = gpsOutcome.candidateState,
                gpsStopCandidateElapsedMs = 0L,
                gpsMovingCandidateElapsedMs = 0L,
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
            magnetOutcome = magnetOutcome,
            magnetAccuracy = magnetAccuracy,
            magnetEffectiveStopThreshold = effectiveMagnetStopThreshold,
            magnetEffectiveMovingThreshold = effectiveMagnetMovingThreshold,
            pressureChangeRate = pressureChangeRate,
            pressureChangeRateSmoothed = pressureChangeRateSmoothed,
            fusionOutcome = fusionOutcome,
            gpsSpeedMps = gpsSpeedMps,
            gpsAccuracyM = gpsAccuracyM,
            gpsCalibrationStatus = gpsOutcome.calibrationStatus,
            gpsCandidateState = gpsOutcome.candidateState,
            gpsStopCandidateElapsedMs = 0L,
            gpsMovingCandidateElapsedMs = 0L,
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
        magnetOutcome: MagnetOutcome,
        magnetAccuracy: Int?,
        magnetEffectiveStopThreshold: Double,
        magnetEffectiveMovingThreshold: Double,
        pressureChangeRate: Double?,
        pressureChangeRateSmoothed: Double?,
        fusionOutcome: FusionOutcome,
        gpsSpeedMps: Double?,
        gpsAccuracyM: Double?,
        gpsCalibrationStatus: String,
        gpsCandidateState: String?,
        gpsStopCandidateElapsedMs: Long,
        gpsMovingCandidateElapsedMs: Long,
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
            magnetCalibrationStatus = magnetOutcome.calibrationStatus,
            magnetCalibrationLowJitter = magnetOutcome.lowJitter,
            magnetCalibrationHighJitter = magnetOutcome.highJitter,
            magnetStopCandidateElapsedMs = magnetOutcome.stopCandidateElapsedMs,
            magnetMovingCandidateElapsedMs = magnetOutcome.movingCandidateElapsedMs,
            magnetAccuracy = magnetAccuracy,
            magnetDynamicStopThreshold = dynamicMagnetStopThreshold,
            magnetDynamicMovingThreshold = dynamicMagnetMovingThreshold,
            magnetEffectiveStopThreshold = magnetEffectiveStopThreshold,
            magnetEffectiveMovingThreshold = magnetEffectiveMovingThreshold,
            magnetThresholdMode = if (
                dynamicMagnetStopThreshold != null && dynamicMagnetMovingThreshold != null
            ) {
                "动态"
            } else {
                "固定"
            },
            pressureChangeRate = pressureChangeRate,
            pressureChangeRateSmoothed = pressureChangeRateSmoothed,
            magnetRank = fusionOutcome.magnetRank,
            micRank = fusionOutcome.micRank,
            pressureRank = fusionOutcome.pressureRank,
            fusionScore = fusionOutcome.score,
            fusionChannelCount = fusionOutcome.channelCount,
            fusionStopCandidateElapsedMs = fusionOutcome.stopCandidateElapsedMs,
            fusionMovingCandidateElapsedMs = fusionOutcome.movingCandidateElapsedMs,
            fusionLockoutRemainingMs = fusionOutcome.lockoutRemainingMs,
            gpsSpeedMps = gpsSpeedMps,
            gpsAccuracyM = gpsAccuracyM,
            gpsCalibrationStatus = gpsCalibrationStatus,
            gpsCandidateState = gpsCandidateState,
            gpsStopCandidateElapsedMs = gpsStopCandidateElapsedMs,
            gpsMovingCandidateElapsedMs = gpsMovingCandidateElapsedMs,
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

    // Converts each channel to a percentile rank inside its own trailing
    // window, averages whichever are available, and runs the confirmation
    // state machine on that average. See FUSION_RANK_WINDOW_MS's doc for why
    // the ranks rather than the raw values, and where the constants came
    // from. A low score means "all three channels are quiet relative to
    // their own recent history", which is what a stopped train looks like
    // regardless of how loud or magnetically noisy this particular ride is.
    private fun updateFusionState(
        magnetJitterSmoothed: Double?,
        micSmoothed: Double?,
        pressureChangeRateSmoothed: Double?,
        authoritative: Boolean,
        now: Long,
    ): FusionOutcome {
        val magnetRank = magnetJitterSmoothed?.let { magnetRankWindow.pushAndRank(it, now) }
        val micRank = micSmoothed?.let { micRankWindow.pushAndRank(it, now) }
        val pressureRank = pressureChangeRateSmoothed?.let { pressureRankWindow.pushAndRank(it, now) }

        // Normal case: the two primary channels. Mic joins only to keep the
        // fusion alive when fewer than two primaries are reporting.
        val primary = listOfNotNull(
            magnetRank.takeIf { "magnet" in FUSION_PRIMARY_CHANNELS },
            pressureRank.takeIf { "pressure" in FUSION_PRIMARY_CHANNELS },
        )
        val ranks = if (primary.size >= 2) {
            primary
        } else {
            listOfNotNull(magnetRank, micRank, pressureRank)
        }
        val score = if (ranks.isEmpty()) null else ranks.sum() / ranks.size

        if (!authoritative || score == null) {
            // Either something else owns the decision, or not enough history
            // has accumulated yet. Drop any half-built candidate so a later
            // handover cannot resume from a stale timer.
            fusionStopCandidateSince = null
            fusionMovingCandidateSince = null
            return FusionOutcome(
                magnetRank = magnetRank,
                micRank = micRank,
                pressureRank = pressureRank,
                score = score,
                channelCount = ranks.size,
                stopCandidateElapsedMs = 0L,
                movingCandidateElapsedMs = 0L,
                lockoutRemainingMs = 0L,
                reason = if (authoritative) "fusion-warming-up-hold-state" else null,
            )
        }

        var stopElapsed = 0L
        var movingElapsed = 0L
        val reason: String
        // Any candidate building during the lockout is dropped rather than
        // carried, so a transition cannot fire the instant the lockout ends
        // on the strength of evidence gathered while it was in force.
        val lockedUntil = fusionLastTransitionAt?.plus(FUSION_STATE_LOCKOUT_MS)
        val locked = lockedUntil != null && now < lockedUntil
        val sinceLastTransition = fusionLastTransitionAt?.let { now - it }
        // A reversal outranks the lockout: undoing a transition that should
        // not have happened is the one move worth making while it is in
        // force.
        fun isReversal(target: String) =
            fusionPreviousState == target &&
                sinceLastTransition != null &&
                sinceLastTransition < FUSION_REVERT_WINDOW_MS
        fun requiredMs(target: String, normal: Long) =
            if (isReversal(target)) FUSION_REVERT_CONFIRMATION_MS else normal

        when {
            score <= FUSION_STOP_SCORE -> {
                fusionMovingCandidateSince = null
                if (stableTrainState == STATE_STOPPED) {
                    fusionStopCandidateSince = null
                    reason = "fusion-stop-hold"
                } else if (locked && !isReversal(STATE_STOPPED)) {
                    fusionStopCandidateSince = null
                    reason = "fusion-stop-locked-out"
                } else {
                    val since = fusionStopCandidateSince ?: now.also { fusionStopCandidateSince = it }
                    stopElapsed = (now - since).coerceAtLeast(0L)
                    val reversal = isReversal(STATE_STOPPED)
                    if (stopElapsed >= requiredMs(STATE_STOPPED, FUSION_STOP_CONFIRMATION_MS)) {
                        fusionPreviousState = stableTrainState
                        stableTrainState = STATE_STOPPED
                        fusionStopCandidateSince = null
                        fusionLastTransitionAt = now
                        reason = if (reversal) "fusion-stop-reverted" else "fusion-stop-confirmed"
                    } else {
                        reason = if (reversal) "fusion-stop-reverting" else "fusion-stop-confirming"
                    }
                }
            }
            score >= FUSION_MOVING_SCORE -> {
                fusionStopCandidateSince = null
                if (stableTrainState == STATE_MOVING) {
                    fusionMovingCandidateSince = null
                    reason = "fusion-moving-hold"
                } else if (locked && !isReversal(STATE_MOVING)) {
                    fusionMovingCandidateSince = null
                    reason = "fusion-moving-locked-out"
                } else {
                    val since = fusionMovingCandidateSince ?: now.also { fusionMovingCandidateSince = it }
                    movingElapsed = (now - since).coerceAtLeast(0L)
                    val reversal = isReversal(STATE_MOVING)
                    if (movingElapsed >= requiredMs(STATE_MOVING, FUSION_MOVING_CONFIRMATION_MS)) {
                        fusionPreviousState = stableTrainState
                        stableTrainState = STATE_MOVING
                        fusionMovingCandidateSince = null
                        fusionLastTransitionAt = now
                        reason = if (reversal) "fusion-moving-reverted" else "fusion-moving-confirmed"
                    } else {
                        reason = if (reversal) "fusion-moving-reverting" else "fusion-moving-confirming"
                    }
                }
            }
            else -> {
                fusionStopCandidateSince = null
                fusionMovingCandidateSince = null
                reason = "fusion-ambiguous-hold-state"
            }
        }

        return FusionOutcome(
            magnetRank = magnetRank,
            micRank = micRank,
            pressureRank = pressureRank,
            score = score,
            channelCount = ranks.size,
            stopCandidateElapsedMs = stopElapsed,
            movingCandidateElapsedMs = movingElapsed,
            lockoutRemainingMs = if (locked) (lockedUntil!! - now).coerceAtLeast(0L) else 0L,
            reason = reason,
        )
    }

    // Runs the magnet channel's one-time usability test and, once passed and
    // if it is this ride's authority, its own candidate/confirmation state
    // machine over stableTrainState. See MAGNET_CALIBRATION_MIN_SAMPLES's doc
    // in the companion object for what the test measures and why it is
    // open-ended rather than a fixed window.
    private fun updateMagnetState(
        magnetJitterSmoothed: Double?,
        candidateState: String?,
        authoritative: Boolean,
        effectiveStopThreshold: Double,
        effectiveMovingThreshold: Double,
        now: Long,
    ): MagnetOutcome {
        // Anchored on the first tick rather than the first usable sample, so
        // a phone whose magnetometer never reports at all still reaches the
        // MAX_WINDOW_MS deadline and settles on "不可用" instead of sitting
        // in "校准中" for the whole ride.
        if (magnetFirstSampleAt == null) magnetFirstSampleAt = now

        if (magnetUsable == null) {
            if (magnetJitterSmoothed != null) {
                magnetCalibrationSamples.add(magnetJitterSmoothed)
                if (magnetCalibrationSamples.size >= MAGNET_CALIBRATION_MIN_SAMPLES) {
                    val sorted = magnetCalibrationSamples.sorted()
                    val low = percentile(sorted, MAGNET_CALIBRATION_LOW_PERCENTILE)
                    val high = percentile(sorted, MAGNET_CALIBRATION_HIGH_PERCENTILE)
                    magnetCalibrationLowJitter = low
                    magnetCalibrationHighJitter = high
                    // The channel is only worth trusting if what it has
                    // actually produced so far reaches both sides of its own
                    // decision band; a distribution sitting entirely inside
                    // or entirely below the band can never yield one of the
                    // two verdicts.
                    if (low <= effectiveStopThreshold && high >= effectiveMovingThreshold) {
                        magnetUsable = true
                        magnetCalibrationSamples.clear()
                    }
                }
            }
            if (magnetUsable == null) {
                val elapsed = (now - magnetFirstSampleAt!!).coerceAtLeast(0L)
                if (elapsed < MAGNET_CALIBRATION_MAX_WINDOW_MS) {
                    return MagnetOutcome(
                        calibrationStatus = MAGNET_STATUS_CALIBRATING,
                        usable = false,
                        lowJitter = magnetCalibrationLowJitter,
                        highJitter = magnetCalibrationHighJitter,
                        stopCandidateElapsedMs = 0L,
                        movingCandidateElapsedMs = 0L,
                        reason = null,
                    )
                }
                magnetUsable = false
                magnetCalibrationSamples.clear()
            }
        }

        if (magnetUsable != true) {
            return MagnetOutcome(
                calibrationStatus = MAGNET_STATUS_UNUSABLE,
                usable = false,
                lowJitter = magnetCalibrationLowJitter,
                highJitter = magnetCalibrationHighJitter,
                stopCandidateElapsedMs = 0L,
                movingCandidateElapsedMs = 0L,
                reason = null,
            )
        }

        if (!authoritative) {
            // Trustworthy, but something else is deciding this ride (GPS
            // outranks it, or magnetPrimaryEnabled is off and it is back
            // to being the mic's assist). Drop any half-built candidate so
            // it cannot resume from a stale timer if it takes over later.
            magnetStopCandidateSince = null
            magnetMovingCandidateSince = null
            return MagnetOutcome(
                calibrationStatus = MAGNET_STATUS_USABLE,
                usable = true,
                lowJitter = magnetCalibrationLowJitter,
                highJitter = magnetCalibrationHighJitter,
                stopCandidateElapsedMs = 0L,
                movingCandidateElapsedMs = 0L,
                reason = null,
            )
        }

        if (magnetJitterSmoothed == null) {
            // Momentarily no smoothed reading (sensor gap long enough to
            // empty the smoothing window). Hold the last confirmed state,
            // same as the GPS channel does when a fix drops out.
            magnetStopCandidateSince = null
            magnetMovingCandidateSince = null
            return MagnetOutcome(
                calibrationStatus = MAGNET_STATUS_USABLE,
                usable = true,
                lowJitter = magnetCalibrationLowJitter,
                highJitter = magnetCalibrationHighJitter,
                stopCandidateElapsedMs = 0L,
                movingCandidateElapsedMs = 0L,
                reason = "magnet-signal-lost-hold-state",
            )
        }

        var stopElapsed = 0L
        var movingElapsed = 0L
        val reason: String

        when (candidateState) {
            STATE_STOPPED -> {
                magnetMovingCandidateSince = null
                if (stableTrainState == STATE_STOPPED) {
                    magnetStopCandidateSince = null
                    reason = "magnet-stop-hold"
                } else {
                    val since = magnetStopCandidateSince ?: now.also { magnetStopCandidateSince = it }
                    stopElapsed = (now - since).coerceAtLeast(0L)
                    if (stopElapsed >= MAGNET_STOP_CONFIRMATION_MS) {
                        stableTrainState = STATE_STOPPED
                        magnetStopCandidateSince = null
                        reason = "magnet-stop-confirmed"
                    } else {
                        reason = "magnet-stop-confirming"
                    }
                }
            }
            STATE_MOVING -> {
                magnetStopCandidateSince = null
                if (stableTrainState == STATE_MOVING) {
                    magnetMovingCandidateSince = null
                    reason = "magnet-moving-hold"
                } else {
                    val since = magnetMovingCandidateSince ?: now.also { magnetMovingCandidateSince = it }
                    movingElapsed = (now - since).coerceAtLeast(0L)
                    if (movingElapsed >= MAGNET_MOVING_CONFIRMATION_MS) {
                        stableTrainState = STATE_MOVING
                        magnetMovingCandidateSince = null
                        reason = "magnet-moving-confirmed"
                    } else {
                        reason = "magnet-moving-confirming"
                    }
                }
            }
            else -> {
                magnetStopCandidateSince = null
                magnetMovingCandidateSince = null
                reason = "magnet-ambiguous-hold-state"
            }
        }

        return MagnetOutcome(
            calibrationStatus = MAGNET_STATUS_USABLE,
            usable = true,
            lowJitter = magnetCalibrationLowJitter,
            highJitter = magnetCalibrationHighJitter,
            stopCandidateElapsedMs = stopElapsed,
            movingCandidateElapsedMs = movingElapsed,
            reason = reason,
        )
    }

    // Runs the one-time GPS accuracy calibration test and, once passed, the
    // GPS-speed candidate/confirmation state machine that becomes
    // authoritative over stableTrainState. See GPS_CALIBRATION_WINDOW_MS's
    // doc in the companion object for the overall design.
    private fun updateGpsState(speedMps: Double?, accuracyM: Double?, now: Long): GpsOutcome {
        if (gpsFirstUpdateAt == null) gpsFirstUpdateAt = now
        val elapsedSinceFirstUpdate = now - gpsFirstUpdateAt!!

        if (gpsUsable == null) {
            if (accuracyM != null) gpsCalibrationAccuracySamples.add(accuracyM)
            if (elapsedSinceFirstUpdate >= GPS_CALIBRATION_WINDOW_MS) {
                val total = gpsCalibrationAccuracySamples.size
                val good = gpsCalibrationAccuracySamples.count { it <= GPS_ACCURACY_THRESHOLD_M }
                gpsUsable = total >= GPS_CALIBRATION_MIN_SAMPLES &&
                    good.toDouble() / total >= GPS_CALIBRATION_MIN_GOOD_RATIO
            } else {
                return GpsOutcome(GPS_STATUS_CALIBRATING, false, null, 0L, 0L, null)
            }
        }

        if (gpsUsable != true) {
            return GpsOutcome(GPS_STATUS_UNUSABLE, false, null, 0L, 0L, null)
        }

        if (speedMps == null) {
            // Fix temporarily lost (e.g. a brief tunnel) — hold whatever
            // state GPS last confirmed rather than falling back to mic. If
            // field data shows routes that lose the fix for long stretches
            // mid-ride, this is the place to revisit a mic fallback; not
            // attempted yet since the request this implements was scoped to
            // the start-of-ride calibration decision.
            gpsStopCandidateSince = null
            gpsMovingCandidateSince = null
            return GpsOutcome(GPS_STATUS_USABLE, true, null, 0L, 0L, "gps-fix-lost-hold-state")
        }

        val candidateState = when {
            speedMps <= GPS_STOP_SPEED_MPS -> STATE_STOPPED
            speedMps >= GPS_MOVING_SPEED_MPS -> STATE_MOVING
            else -> null
        }

        var stopElapsed = 0L
        var movingElapsed = 0L
        val reason: String

        when (candidateState) {
            STATE_STOPPED -> {
                gpsMovingCandidateSince = null
                if (stableTrainState == STATE_STOPPED) {
                    gpsStopCandidateSince = null
                    reason = "gps-stop-hold"
                } else {
                    val since = gpsStopCandidateSince ?: now.also { gpsStopCandidateSince = it }
                    stopElapsed = (now - since).coerceAtLeast(0L)
                    if (stopElapsed >= GPS_STOP_CONFIRMATION_MS) {
                        stableTrainState = STATE_STOPPED
                        gpsStopCandidateSince = null
                        reason = "gps-stop-confirmed"
                    } else {
                        reason = "gps-stop-confirming"
                    }
                }
            }
            STATE_MOVING -> {
                gpsStopCandidateSince = null
                if (stableTrainState == STATE_MOVING) {
                    gpsMovingCandidateSince = null
                    reason = "gps-moving-hold"
                } else {
                    val since = gpsMovingCandidateSince ?: now.also { gpsMovingCandidateSince = it }
                    movingElapsed = (now - since).coerceAtLeast(0L)
                    if (movingElapsed >= GPS_MOVING_CONFIRMATION_MS) {
                        stableTrainState = STATE_MOVING
                        gpsMovingCandidateSince = null
                        reason = "gps-moving-confirmed"
                    } else {
                        reason = "gps-moving-confirming"
                    }
                }
            }
            else -> {
                gpsStopCandidateSince = null
                gpsMovingCandidateSince = null
                reason = "gps-ambiguous-hold-state"
            }
        }

        return GpsOutcome(GPS_STATUS_USABLE, true, candidateState, stopElapsed, movingElapsed, reason)
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
