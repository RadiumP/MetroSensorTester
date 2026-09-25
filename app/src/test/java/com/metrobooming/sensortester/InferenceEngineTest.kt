package com.metrobooming.sensortester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceEngineTest {
    // The shipped default is currently the magnet-only field-test
    // configuration (see MIC_DECISION_ENABLED_DEFAULT), under which the mic
    // channel cannot move train_state at all. Every test below that predates
    // that switch exercises the mic-primary plus magnet-assist fusion, so it
    // asks for that configuration explicitly instead of relying on the
    // defaults; the magnet-primary tests at the bottom of this file use the
    // defaults.
    private fun legacyFusionEngine() = InferenceEngine(
        micDecisionEnabled = true,
        magnetPrimaryEnabled = false,
        fusionEnabled = false,
    )

    @Test
    fun microphoneQualityDetectsZeroAndRequiresRecovery() {
        val monitor = MicQualityMonitor()

        assertEquals("恢复中", monitor.update(0.0020, true, 0L).quality)
        assertFalse(monitor.update(0.0020, true, 999L).valid)
        assertTrue(monitor.update(0.0020, true, 1_000L).valid)

        assertTrue(monitor.update(0.0, true, 1_250L).valid)
        assertTrue(monitor.update(0.0, true, 3_249L).valid)
        val abnormal = monitor.update(0.0, true, 3_250L)
        assertFalse(abnormal.valid)
        assertEquals("全零异常", abnormal.quality)
        assertEquals(2_000L, abnormal.zeroDurationMs)

        assertEquals("恢复中", monitor.update(0.0020, true, 3_500L).quality)
        assertFalse(monitor.update(0.0020, true, 4_499L).valid)
        assertTrue(monitor.update(0.0020, true, 4_500L).valid)
    }

    @Test
    fun stopRequiresThreeContinuousSeconds() {
        val engine = legacyFusionEngine()
        engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 0L)
        assertEquals("运行", engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 1_750L).trainState)

        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, 2_000L)
        assertEquals(
            "运行",
            engine.update(0.0010, true, 0.0, 0.1, 1.0, null, 4_999L).trainState,
        )
        assertEquals(
            "停站",
            engine.update(0.0010, true, 0.0, 0.1, 1.0, null, 5_000L).trainState,
        )
    }

    @Test
    fun movingRequiresConfirmationAndAmbiguousBandResetsCandidate() {
        val engine = legacyFusionEngine()
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, 0L)
        assertEquals("停站", engine.update(0.0010, true, 0.0, 0.1, 1.0, null, 3_000L).trainState)

        engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 4_000L)
        assertEquals(
            "停站",
            engine.update(0.0016, true, 0.0, 0.1, 1.0, null, 5_000L).trainState,
        )
        engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 5_500L)
        assertEquals(
            "停站",
            engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 7_249L).trainState,
        )
        assertEquals(
            "运行",
            engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 7_250L).trainState,
        )
    }

    @Test
    fun invalidMicrophoneHoldsTrainStateAndDoesNotUpdateHistory() {
        val engine = legacyFusionEngine()
        engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 0L)
        engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 1_750L)
        repeat(12) { index ->
            engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 2_000L + index * 250L)
        }
        val before = engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 5_000L)

        val duringInitialZero = engine.update(0.0, true, 0.0, 0.1, 1.0, null, 10_000L)
        assertEquals("运行", duringInitialZero.trainState)
        assertEquals(before.validMicSampleCount, duringInitialZero.validMicSampleCount)

        val duringZero = engine.update(0.0, false, 0.0, 0.1, 1.0, null, 30_000L)
        assertEquals("运行", duringZero.trainState)
        assertEquals(before.validMicSampleCount, duringZero.validMicSampleCount)
        assertEquals(before.effectiveStopThreshold, duringZero.effectiveStopThreshold, 0.0)
        assertEquals(before.effectiveMovingThreshold, duringZero.effectiveMovingThreshold, 0.0)

        val muchLater = engine.update(0.0, false, 0.0, 0.1, 1.0, null, 300_000L)
        assertEquals("运行", muchLater.trainState)
        assertEquals(before.validMicSampleCount, muchLater.validMicSampleCount)
    }

    @Test
    fun reportsStoppedWhilePlayerIsActive() {
        val engine = legacyFusionEngine()
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, 0L)
        val result = engine.update(0.0010, true, 0.0, 0.6, 1.0, null, 3_000L)

        assertEquals("停站", result.trainState)
        assertEquals("活动", result.playerState)
        assertTrue(result.playerActive)
        assertEquals("停站+人动", result.state)
    }

    @Test
    fun dynamicThresholdsUseMixedValidHistoryAndFreezeOnSingleState() {
        val engine = legacyFusionEngine()
        var now = 0L
        engine.update(0.0024, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.MOVING_CONFIRMATION_MS
        engine.update(0.0024, true, 0.0, 0.1, 1.0, null, now)

        repeat(180) { index ->
            now += 250L
            val rms = 0.0020 + (index % 5) * 0.0001
            engine.update(rms, true, 0.0, 0.1, 1.0, null, now)
        }
        now += 250L
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.STOP_CONFIRMATION_MS
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)

        var dynamicResult: InferenceResult? = null
        repeat(100) { index ->
            now += 250L
            val rms = 0.0009 + (index % 4) * 0.00005
            dynamicResult = engine.update(rms, true, 0.0, 0.1, 1.0, null, now)
        }
        val dynamic = requireNotNull(dynamicResult)
        assertEquals("动态", dynamic.thresholdMode)
        assertNotNull(dynamic.dynamicStopThreshold)
        assertNotNull(dynamic.dynamicMovingThreshold)
        assertTrue(dynamic.effectiveStopThreshold in 0.0008..0.0016)
        assertTrue(dynamic.effectiveMovingThreshold in 0.0016..0.0030)
        assertTrue(dynamic.effectiveMovingThreshold - dynamic.effectiveStopThreshold >= 0.0002)

        val beforeInvalid = dynamic
        val invalid = engine.update(0.0, false, 0.0, 0.1, 1.0, null, now + 60_000L)
        assertEquals(beforeInvalid.validMicSampleCount, invalid.validMicSampleCount)
        assertEquals(beforeInvalid.dynamicStopThreshold, invalid.dynamicStopThreshold)
        assertEquals(beforeInvalid.dynamicMovingThreshold, invalid.dynamicMovingThreshold)

        repeat(800) {
            now += 250L
            engine.update(0.0024, true, 0.0, 0.1, 1.0, null, now)
        }
        val frozen = engine.update(0.0024, true, 0.0, 0.1, 1.0, null, now + 250L)
        repeat(240) {
            now += 250L
            engine.update(0.0024, true, 0.0, 0.1, 1.0, null, now)
        }
        val stillFrozen = engine.update(0.0024, true, 0.0, 0.1, 1.0, null, now + 250L)
        assertEquals(frozen.dynamicStopThreshold, stillFrozen.dynamicStopThreshold)
        assertEquals(frozen.dynamicMovingThreshold, stillFrozen.dynamicMovingThreshold)
    }

    @Test
    fun dynamicMovingThresholdStaysAnchoredToMovingSamplesUnderSkewedHistory() {
        // Regression test: a long station dwell can fill the 3-minute history
        // with far more "停站" samples than "运行" samples, some of them loud
        // (crowded platform, announcements) but still genuinely stationary. If
        // thresholds were derived from one combined-history percentile, this
        // stopped-heavy skew would drag the moving threshold down toward
        // ordinary stop noise, making loud-but-stopped samples misread as
        // moving. Per-state percentiles must keep the moving threshold
        // anchored to the (small) population of genuinely moving samples.
        val engine = legacyFusionEngine()
        var now = 0L

        engine.update(0.0009, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.STOP_CONFIRMATION_MS
        engine.update(0.0009, true, 0.0, 0.1, 1.0, null, now)

        // 700 "停站" samples: mostly quiet, ~10% loud platform noise — but
        // always below the fixed moving threshold (0.0018) so the state never
        // flips while this history is being built.
        repeat(700) { index ->
            now += 250L
            val rms = if (index % 10 == 0) 0.0017 else 0.0009
            engine.update(rms, true, 0.0, 0.1, 1.0, null, now)
        }

        // A short, tightly-clustered burst of genuine moving noise: just
        // enough samples (>= MIN_SAMPLES_PER_STATE) to unlock dynamic mode.
        now += 250L
        engine.update(0.0019, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.MOVING_CONFIRMATION_MS
        var result: InferenceResult? = null
        repeat(24) {
            now += 250L
            result = engine.update(0.0019, true, 0.0, 0.1, 1.0, null, now)
        }

        val dynamic = requireNotNull(result)
        assertEquals("动态", dynamic.thresholdMode)
        // The 700 quiet/loud-but-stopped samples outnumber the 25 moving
        // samples roughly 28:1. A combined-history percentile would have
        // computed the moving split point from the stopped population and
        // clamped it down to MIN_MOVING_THRESHOLD (0.0016) — below the fixed
        // default. The per-state P25-of-moving-only calculation must instead
        // stay at or above the genuine moving noise level.
        assertTrue(dynamic.effectiveMovingThreshold >= InferenceEngine.MIC_MOVING_RMS_THRESHOLD)
    }

    @Test
    fun flagsChimeCandidateOnlyForSharpPeakRelativeToRms() {
        val engine = legacyFusionEngine()

        // Ordinary cabin noise: peak/rms crest factor around 3-4x, as seen in
        // real ride recordings. Should never flag as a chime candidate no
        // matter how loud it gets.
        val ordinaryLoud = engine.update(0.0030, true, 0.0030 * 4.0, 0.1, 1.0, null, 0L)
        assertFalse(ordinaryLoud.micChimeCandidate)

        // A brief, sharp tone (the fixed station announcement chime) barely
        // moves the windowed RMS average but spikes the peak — crest factor
        // far above ordinary cabin noise.
        val chime = engine.update(0.0012, true, 0.0012 * 12.0, 0.1, 1.0, null, 250L)
        assertTrue(chime.micChimeCandidate)
        assertEquals(12.0, chime.micCrestFactor, 0.001)

        // A high crest factor on a near-silent tick shouldn't count — too
        // quiet to plausibly be the chime, more likely mic noise floor.
        val quietSpike = engine.update(0.0001, true, 0.0001 * 12.0, 0.1, 1.0, null, 500L)
        assertFalse(quietSpike.micChimeCandidate)
    }

    @Test
    fun computesMagnetMagnitudeJitterAsAbsoluteChangeSinceLastSample() {
        val engine = legacyFusionEngine()

        // No prior sample to compare against yet.
        val first = engine.update(0.0022, true, 0.0, 0.1, 1.0, 45.0, 0L)
        assertNull(first.magnetMagnitudeJitter)

        // Field test found this reads much higher while the train is moving
        // (likely traction-motor EMI) than while stopped.
        val jump = engine.update(0.0022, true, 0.0, 0.1, 1.0, 48.5, 250L)
        assertEquals(3.5, requireNotNull(jump.magnetMagnitudeJitter), 0.0001)

        // A missing magnetometer sample shouldn't crash or fabricate a
        // value, and the next real sample should compare against the last
        // known reading rather than the gap.
        val missing = engine.update(0.0022, true, 0.0, 0.1, 1.0, null, 500L)
        assertNull(missing.magnetMagnitudeJitter)

        val resumed = engine.update(0.0022, true, 0.0, 0.1, 1.0, 47.0, 750L)
        assertEquals(1.5, requireNotNull(resumed.magnetMagnitudeJitter), 0.0001)
    }

    @Test
    fun agreeingMagnetShortensStopConfirmation() {
        val engine = legacyFusionEngine()
        var now = 0L
        var magnet = 45.0

        // Get into a stable "运行" state first so there's something to
        // confirm a stop transition away from.
        engine.update(0.0024, true, 0.0, 0.1, 1.0, magnet, now)
        now += InferenceEngine.MOVING_CONFIRMATION_MS
        engine.update(0.0024, true, 0.0, 0.1, 1.0, magnet, now)

        // Mic drops below the stop threshold at t=2000ms. Magnet jitter
        // stays small and constant (well inside the "停站" band) the whole
        // time, so once the smoothing window fills (a few ticks in) it
        // should read as "agree" and shorten the normal 3000ms stop
        // confirmation to 1800ms (3000 * 0.6).
        now += 250L
        var result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now) // t=2000, elapsed=0

        repeat(7) {
            now += 250L
            magnet += 0.4
            result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)
        }
        // t=3750, elapsed=1750 — still short of the assisted 1800ms.
        assertEquals(3_750L, now)
        assertEquals("运行", result.trainState)

        now += 250L
        magnet += 0.4
        result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)
        // t=4000, elapsed=2000 — past the assisted 1800ms requirement
        // (would have needed to wait until t=5000 without the assist).
        assertEquals("agree", result.magnetAssistNote)
        assertEquals("停站", result.trainState)
    }

    @Test
    fun disagreeingMagnetLengthensMovingConfirmation() {
        val engine = legacyFusionEngine()
        var now = 0L
        var magnet = 45.0

        // Get into a stable "停站" state, keeping magnet jitter small and
        // constant throughout (so its "停站"-band reading is already
        // established once the moving candidate starts). Confirmed at
        // t=2000 once the agreeing magnet signal kicks in (same mechanism
        // as the test above, incidental here).
        var result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)
        repeat(8) {
            now += 250L
            magnet += 0.4
            result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)
        }
        assertEquals(2_000L, now)
        assertEquals("停站", result.trainState)

        // Mic jumps above the moving threshold, but magnet jitter keeps
        // reading small/constant (still "停站"), disagreeing with the new
        // moving candidate. That should lengthen the normal 1750ms moving
        // confirmation to 2450ms (1750 * 1.4).
        now += 250L
        magnet += 0.4
        result = engine.update(0.0022, true, 0.0, 0.1, 1.0, magnet, now) // t=2250, elapsed=0

        repeat(9) {
            now += 250L
            magnet += 0.4
            result = engine.update(0.0022, true, 0.0, 0.1, 1.0, magnet, now)
        }
        // t=4500, elapsed=2250 — would already be confirmed without the
        // assist (past the fixed 1750ms), but the disagreeing magnet
        // reading should still be holding it back.
        assertEquals(4_500L, now)
        assertEquals("disagree", result.magnetAssistNote)
        assertEquals("停站", result.trainState)

        now += 250L
        magnet += 0.4
        result = engine.update(0.0022, true, 0.0, 0.1, 1.0, magnet, now)
        // t=4750, elapsed=2500 — past the assisted 2450ms requirement.
        assertEquals("运行", result.trainState)
    }

    @Test
    fun magnetIndependentlyTriggersWhenMicNeverBuildsCandidate() {
        // Real-ride field test (2026-09-02): an at-grade light-rail ride's
        // genuine moving noise almost never crossed the mic moving
        // threshold, so mic itself never even attempted a moving candidate
        // — the train stayed misread as "停站" for the whole ride. This
        // covers the deadlock-breaker: a rolling-window majority of magnet
        // candidate reads forcing the transition on its own.
        val engine = legacyFusionEngine()
        var now = 0L

        // Get to a stable "停站" state first, with no magnet signal
        // involved yet.
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.STOP_CONFIRMATION_MS
        var result = engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)
        assertEquals("停站", result.trainState)

        // Mic stays confidently below the stop threshold from here on (mic
        // itself never attempts a moving candidate), but magnet jitter
        // climbs to a clear, sustained "运行" reading.
        var magnet = 45.0
        now += 250L
        result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now) // first magnet sample, no jitter yet

        repeat(22) {
            now += 250L
            magnet += 2.5
            result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)
        }
        // t=8750: 19 "运行" candidate votes have accumulated in the trailing
        // 20s window — one short of the 20-sample minimum, so no trigger
        // yet.
        assertEquals(8_750L, now)
        assertEquals("运行", result.magnetCandidateState)
        assertEquals("停站", result.trainState)

        now += 250L
        magnet += 2.5
        result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)
        // t=9000: the 20th vote lands, all of them "运行" (100% >= the 75%
        // majority requirement) — magnet forces the transition even though
        // mic never budged from "below stop threshold".
        assertEquals("trigger", result.magnetAssistNote)
        assertEquals("运行", result.trainState)
    }

    @Test
    fun magnetIndependentTriggerToleratesIntermittentDisagreement() {
        // Real-ride field test (2026-09-05): within a mis-held "停站"
        // stretch, magnet candidate reads oscillated rather than holding an
        // unbroken run — genuine signal, but no continuous streak reached
        // even 5 seconds (see MAGNET_INDEPENDENT_WINDOW_MS's doc). The
        // rolling-window majority vote tolerates that: a supermajority (not
        // unanimity) of recent candidate reads is enough, so a handful of
        // "停站" reads mixed into a mostly-"运行" stretch still triggers.
        val engine = legacyFusionEngine()
        var now = 0L
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.STOP_CONFIRMATION_MS
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)

        var magnet = 45.0
        now += 250L
        var result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)

        // 12 ticks of a large jump (reads as "运行" once the smoothing
        // window fills), then 10 ticks of a tiny jump (the window fills
        // with enough of them to briefly flip the smoothed reading to
        // "停站" for a couple of ticks), then back to large jumps. The
        // trigger itself fires on the 23rd tick (index 22) the instant the
        // rolling-window vote tally clears the 75% majority bar — one tick
        // later the state has already flipped to "运行" and a fresh
        // mic-driven stop candidate starts agreeing with the (still
        // "停站"-reading) magnet, so this stops right at the trigger tick
        // rather than one past it.
        repeat(23) { index ->
            now += 250L
            magnet += if (index % 22 < 12) 3.0 else 0.1
            result = engine.update(0.0010, true, 0.0, 0.1, 1.0, magnet, now)
        }

        assertEquals("trigger", result.magnetAssistNote)
        assertEquals("运行", result.trainState)
    }

    @Test
    fun gpsFallsBackToMicWhenCalibrationAccuracyIsPoor() {
        // 2026-09-06 request: GPS is an opt-in per-ride signal, gated by a
        // one-time accuracy test at the start of the ride (see
        // GPS_CALIBRATION_WINDOW_MS's doc). Poor accuracy throughout the
        // window (e.g. no real fix, or a degraded one) must permanently give
        // up on GPS for the ride and leave mic/magnet driving train_state
        // exactly as if GPS didn't exist.
        val engine = legacyFusionEngine()
        var now = 0L

        // 5 samples of a 120m-accuracy fix spread across the 20s
        // calibration window — comfortably past GPS_CALIBRATION_MIN_SAMPLES
        // but 0% of them clear the 20m accuracy gate.
        var result = engine.update(0.0022, true, 0.0, 0.1, 1.0, null, now, null, 120.0)
        repeat(4) {
            now += 5_000L
            result = engine.update(0.0022, true, 0.0, 0.1, 1.0, null, now, null, 120.0)
        }
        assertEquals(20_000L, now)
        assertEquals(InferenceEngine.GPS_STATUS_UNUSABLE, result.gpsCalibrationStatus)

        // From here on mic alone drives train_state, with its normal timing.
        now += 250L
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.STOP_CONFIRMATION_MS
        result = engine.update(0.0010, true, 0.0, 0.1, 1.0, null, now)
        assertEquals("停站", result.trainState)
        assertTrue(result.reason.startsWith("mic-"))
    }

    @Test
    fun gpsBecomesAuthoritativeAndOverridesDisagreeingMic() {
        // Mirror of the test above with an accurate fix throughout the
        // calibration window: GPS speed should become the sole authority
        // for train_state, with its own (shorter) confirmation timing,
        // overriding whatever a strongly-disagreeing mic reading would have
        // decided on its own.
        val engine = legacyFusionEngine()
        var now = 0L
        val goodAccuracy = 5.0
        // Strictly between the mic stop/moving thresholds during
        // calibration, so mic itself never settles on a state — keeps
        // stableTrainState at "校准中" until GPS takes over, isolating the
        // GPS state machine's own confirmation timing from mic's (a mic
        // reading confidently inside either band would otherwise confirm
        // its own state well before the 20s calibration window elapses,
        // since STOP_CONFIRMATION_MS/MOVING_CONFIRMATION_MS are both under
        // 20s).
        val ambiguousMicRms = 0.0016
        // Once GPS is authoritative, hold mic deep in "stopped" territory
        // for the rest of the test — if GPS ever stopped being
        // authoritative this would pull train_state back to "停站", so a
        // final "运行" result can only mean GPS won.
        val quietMicRms = 0.0005

        var result = engine.update(ambiguousMicRms, true, 0.0, 0.1, 1.0, null, now, 0.0, goodAccuracy)
        repeat(4) {
            now += 5_000L
            result = engine.update(ambiguousMicRms, true, 0.0, 0.1, 1.0, null, now, 0.0, goodAccuracy)
        }
        assertEquals(20_000L, now)
        assertEquals(InferenceEngine.GPS_STATUS_USABLE, result.gpsCalibrationStatus)
        assertEquals("校准中", result.trainState)
        assertEquals("gps-stop-confirming", result.reason)

        // GPS just became authoritative on that last tick and immediately
        // started a "停站" candidate (speed 0.0 the whole window). 8 more
        // ticks (2000ms) confirm it.
        repeat(8) {
            now += 250L
            result = engine.update(quietMicRms, true, 0.0, 0.1, 1.0, null, now, 0.0, goodAccuracy)
        }
        assertEquals(22_000L, now)
        assertEquals("停站", result.trainState)
        assertEquals("gps-stop-confirmed", result.reason)

        // GPS speed now says moving; mic RMS is unchanged (still deep in
        // "stopped" territory) — GPS should win once its own 1500ms
        // confirmation elapses.
        repeat(7) {
            now += 250L
            result = engine.update(quietMicRms, true, 0.0, 0.1, 1.0, null, now, 3.0, goodAccuracy)
        }
        assertEquals(23_750L, now)
        assertEquals("运行", result.trainState)
        assertEquals("gps-moving-confirmed", result.reason)
    }

    // Alternating the raw magnitude between 0 and amp makes every tick's
    // |magnitude - previous| equal amp exactly, so the smoothed (median)
    // jitter settles on amp once the smoothing window has refilled. Returns
    // the last result so a test can assert on the tick it stopped at.
    private fun feedMagnet(
        engine: InferenceEngine,
        amp: Double,
        ticks: Int,
        startNow: Long,
        micRms: Double,
        flipState: BooleanArray,
    ): Pair<InferenceResult, Long> {
        var now = startNow
        var result: InferenceResult? = null
        repeat(ticks) {
            flipState[0] = !flipState[0]
            val magnitude = if (flipState[0]) amp else 0.0
            result = engine.update(
                micRms = micRms,
                micValid = true,
                micPeak = micRms * 3.0,
                accelRms = 0.0,
                gyroDegreesRms = 0.0,
                magnetMagnitude = magnitude,
                now = now,
                magnetAccuracy = 3,
            )
            now += 250L
        }
        return result!! to now
    }

    @Test
    fun magnetIsRejectedWhenItsJitterNeverReachesTheMovingThreshold() {
        // Reproduces the 2026-09-06 ride, whose smoothed jitter never once
        // crossed MAGNET_MOVING_JITTER_THRESHOLD: the channel could only ever
        // have emitted "停站", so it must be given up on rather than trusted.
        val engine = magnetPrimaryEngine()
        val flip = BooleanArray(1)

        val (midway, midwayNow) = feedMagnet(engine, 0.5, 400, 0L, 0.0020, flip)
        assertEquals(InferenceEngine.MAGNET_STATUS_CALIBRATING, midway.magnetCalibrationStatus)
        assertEquals("校准中", midway.trainState)

        // Past MAGNET_CALIBRATION_MAX_WINDOW_MS the verdict is locked in.
        val (after, _) = feedMagnet(engine, 0.5, 400, midwayNow, 0.0020, flip)
        assertEquals(InferenceEngine.MAGNET_STATUS_UNUSABLE, after.magnetCalibrationStatus)
        assertEquals(0.5, after.magnetCalibrationHighJitter!!, 1e-9)
        // Mic decisions are off in this configuration, so with magnet given
        // up on there is nothing left that may move the state.
        assertEquals("校准中", after.trainState)
        assertEquals("magnet-unusable-mic-disabled-hold-state", after.reason)
    }

    @Test
    fun magnetBecomesPrimaryOnceItsJitterStraddlesItsOwnBand() {
        val engine = magnetPrimaryEngine()
        val flip = BooleanArray(1)
        // Deep in mic "stopped" territory for the whole test, so any state
        // change below can only have come from the magnet channel.
        val quietMicRms = 0.0005

        val (calibrating, afterLowNow) = feedMagnet(engine, 0.5, 40, 0L, quietMicRms, flip)
        assertEquals(InferenceEngine.MAGNET_STATUS_CALIBRATING, calibrating.magnetCalibrationStatus)
        assertEquals("停站", calibrating.magnetCandidateState)
        assertEquals("校准中", calibrating.trainState)
        assertNull(calibrating.magnetCalibrationHighJitter)

        // Now the other side of the band shows up, which is what the test is
        // actually waiting for; the channel passes and immediately starts
        // confirming its own "运行" candidate.
        val (usable, afterHighNow) = feedMagnet(engine, 3.0, 40, afterLowNow, quietMicRms, flip)
        assertEquals(InferenceEngine.MAGNET_STATUS_USABLE, usable.magnetCalibrationStatus)
        assertEquals(0.5, usable.magnetCalibrationLowJitter!!, 1e-9)
        assertEquals(3.0, usable.magnetCalibrationHighJitter!!, 1e-9)
        assertEquals("primary", usable.magnetAssistNote)
        assertEquals("运行", usable.trainState)
        assertEquals("magnet-moving-hold", usable.reason)

        // Dropping back to low jitter has to survive both the smoothing
        // window and MAGNET_STOP_CONFIRMATION_MS before the state follows.
        val (stillMoving, _) = feedMagnet(engine, 0.5, 24, afterHighNow, quietMicRms, flip)
        assertEquals("运行", stillMoving.trainState)
        assertEquals("magnet-stop-confirming", stillMoving.reason)

        val (stopped, _) = feedMagnet(engine, 0.5, 40, afterHighNow + 24 * 250L, quietMicRms, flip)
        assertEquals("停站", stopped.trainState)
        assertEquals("magnet-stop-hold", stopped.reason)
    }

    @Test
    fun magnetPrimaryLeavesTheStateAloneWhileItsSignalIsMissing() {
        val engine = magnetPrimaryEngine()
        val flip = BooleanArray(1)
        val (usable, now) = feedMagnet(engine, 3.0, 40, 0L, 0.0020, flip)
        // A magnetometer that only ever reads high jitter never shows the
        // stopped side of the band, so it cannot pass its own test either.
        assertEquals(InferenceEngine.MAGNET_STATUS_CALIBRATING, usable.magnetCalibrationStatus)

        // No magnetometer sample at all: the smoothing window drains and the
        // channel reports no reading rather than guessing one.
        var result = usable
        var t = now
        repeat(40) {
            result = engine.update(
                micRms = 0.0020, micValid = true, micPeak = 0.006,
                accelRms = 0.0, gyroDegreesRms = 0.0,
                magnetMagnitude = null, now = t, magnetAccuracy = null,
            )
            t += 250L
        }
        assertNull(result.magnetJitterSmoothed)
        assertNull(result.magnetCandidateState)
        assertEquals("校准中", result.trainState)
    }

    @Test
    fun pressureChannelIsComputedAndLoggedWithoutAffectingTheDecision() {
        val engine = magnetPrimaryEngine()
        var now = 0L
        var result: InferenceResult? = null

        // Alternating the reading by 0.02 hPa makes every tick's delta exactly
        // 0.02, so the windowed median settles there once the window fills.
        var high = false
        repeat(20) {
            high = !high
            result = engine.update(
                micRms = 0.0020, micValid = true, micPeak = 0.006,
                accelRms = 0.0, gyroDegreesRms = 0.0, magnetMagnitude = null,
                now = now, pressureHpa = if (high) 1013.02 else 1013.00,
            )
            now += 250L
        }
        assertEquals(0.02, result!!.pressureChangeRate!!, 1e-9)
        assertEquals(0.02, result!!.pressureChangeRateSmoothed!!, 1e-9)

        // A steady barometer reads a change rate of zero rather than null:
        // "not moving the air" is a real measurement, unlike "no barometer".
        repeat(20) {
            result = engine.update(
                micRms = 0.0020, micValid = true, micPeak = 0.006,
                accelRms = 0.0, gyroDegreesRms = 0.0, magnetMagnitude = null,
                now = now, pressureHpa = 1013.00,
            )
            now += 250L
        }
        assertEquals(0.0, result!!.pressureChangeRateSmoothed!!, 1e-9)

        // No barometer at all leaves both fields null, and either way the
        // pressure channel must not have moved the state: with no magnetometer
        // samples the magnet channel can never pass its own test, and the mic
        // channel is switched off in this configuration, so nothing is left
        // that may decide.
        repeat(8) {
            result = engine.update(
                micRms = 0.0020, micValid = true, micPeak = 0.006,
                accelRms = 0.0, gyroDegreesRms = 0.0, magnetMagnitude = null,
                now = now, pressureHpa = null,
            )
            now += 250L
        }
        assertNull(result!!.pressureChangeRate)
        assertEquals("校准中", result!!.trainState)
    }

    @Test
    fun magnetThresholdsAreReportedForCsvInspection() {
        val engine = magnetPrimaryEngine()
        val result = engine.update(
            micRms = 0.0020, micValid = true, micPeak = 0.006,
            accelRms = 0.0, gyroDegreesRms = 0.0, magnetMagnitude = 30.0, now = 0L,
        )
        // Nothing has been learned yet, so the channel reports the fixed
        // fallbacks and says so.
        assertNull(result.magnetDynamicStopThreshold)
        assertNull(result.magnetDynamicMovingThreshold)
        assertEquals(InferenceEngine.MAGNET_STOP_JITTER_THRESHOLD, result.magnetEffectiveStopThreshold, 1e-9)
        assertEquals(InferenceEngine.MAGNET_MOVING_JITTER_THRESHOLD, result.magnetEffectiveMovingThreshold, 1e-9)
        assertEquals("固定", result.magnetThresholdMode)
    }

    // The legacy single-channel engines are no longer the default, so the
    // tests covering them ask for their own configuration explicitly.
    private fun magnetPrimaryEngine() = InferenceEngine(
        micDecisionEnabled = false,
        magnetPrimaryEnabled = true,
        fusionEnabled = false,
    )

    @Test
    fun fusionNeedsBothPrimaryChannelsBeforeItWillDecide() {
        val engine = InferenceEngine()
        var result: InferenceResult? = null
        var now = 0L
        // Magnet only: the barometer never reports, so the fusion falls back
        // to whatever it can get and, with a single channel, still has to
        // fill its rank window before it says anything at all.
        var flip = false
        repeat(10) {
            flip = !flip
            result = engine.update(
                micRms = 0.0, micValid = false, micPeak = 0.0,
                accelRms = 0.0, gyroDegreesRms = 0.0,
                magnetMagnitude = if (flip) 3.0 else 0.0, now = now,
            )
            now += 250L
        }
        assertNull(result!!.fusionScore)
        assertEquals(0, result!!.fusionChannelCount)
        assertEquals("校准中", result!!.trainState)
    }

    @Test
    fun fusionRanksAreScaleFreeAcrossWildlyDifferentChannelMagnitudes() {
        // The whole point of ranking instead of thresholding: two rides whose
        // raw levels differ by orders of magnitude must produce the same
        // decision, because only the position within recent history counts.
        fun ride(scale: Double): InferenceResult {
            val engine = InferenceEngine()
            var now = 0L
            var result: InferenceResult? = null
            var flip = false
            // A long stretch of high readings, then a sustained quiet spell.
            repeat(400) {
                flip = !flip
                result = engine.update(
                    micRms = 0.0020, micValid = true, micPeak = 0.006,
                    accelRms = 0.0, gyroDegreesRms = 0.0,
                    magnetMagnitude = if (flip) 4.0 * scale else 0.0,
                    now = now, pressureHpa = 1013.0 + (if (flip) 0.04 * scale else 0.0),
                )
                now += 250L
            }
            repeat(120) {
                result = engine.update(
                    micRms = 0.0020, micValid = true, micPeak = 0.006,
                    accelRms = 0.0, gyroDegreesRms = 0.0,
                    magnetMagnitude = 0.0, now = now, pressureHpa = 1013.0,
                )
                now += 250L
            }
            return result!!
        }

        val small = ride(1.0)
        val large = ride(1000.0)
        assertEquals("停站", small.trainState)
        assertEquals("停站", large.trainState)
        assertEquals(2, small.fusionChannelCount)
        assertEquals(2, large.fusionChannelCount)
        // Same verdict, and the same score, despite a 1000x difference in the
        // underlying readings.
        assertEquals(small.fusionScore!!, large.fusionScore!!, 1e-9)
    }

    @Test
    fun micRankIsStillLoggedEvenThoughItIsNotInTheAverage() {
        val engine = InferenceEngine()
        var now = 0L
        var result: InferenceResult? = null
        var flip = false
        repeat(200) {
            flip = !flip
            result = engine.update(
                micRms = if (flip) 0.0030 else 0.0010, micValid = true, micPeak = 0.009,
                accelRms = 0.0, gyroDegreesRms = 0.0,
                magnetMagnitude = if (flip) 4.0 else 0.0,
                now = now, pressureHpa = 1013.0 + (if (flip) 0.04 else 0.0),
            )
            now += 250L
        }
        assertNotNull(result!!.micRank)
        // Two primaries are reporting, so the average is over those two only.
        assertEquals(2, result!!.fusionChannelCount)
        val expected = (result!!.magnetRank!! + result!!.pressureRank!!) / 2.0
        assertEquals(expected, result!!.fusionScore!!, 1e-9)
    }

    // Ranks compare a reading against its own recent history, so a signal
    // held at a perfectly constant level ranks 0.5 forever and never reaches
    // either decision band. Every fusion test therefore has to vary the level
    // within a phase as well as between phases, which is also what a real
    // ride looks like.
    private class FusionFeed(private val engine: InferenceEngine) {
        var now = 0L
            private set
        var last: InferenceResult? = null
            private set
        private var flip = false
        private var seed = 12345L

        fun run(magBase: Double, pressBase: Double, ticks: Int) {
            repeat(ticks) {
                flip = !flip
                // Spread the values inside a phase so ranks are not all ties.
                // The spread has to be irregular rather than a short repeating
                // pattern: a periodic ripple makes the smoothed value swing
                // across a decision line and reset the confirmation timer
                // every cycle, so the state never settles at all. That is a
                // real engine behaviour worth knowing about, but it is not
                // what these tests are here to exercise.
                seed = (seed * 6364136223846793005L + 1442695040888963407L)
                val unit = ((seed ushr 33).toDouble() / (1L shl 31).toDouble())
                val ripple = 0.85 + 0.3 * unit
                last = engine.update(
                    micRms = 0.0020, micValid = true, micPeak = 0.006,
                    accelRms = 0.0, gyroDegreesRms = 0.0,
                    magnetMagnitude = if (flip) magBase * ripple else 0.0,
                    now = now,
                    pressureHpa = 1013.0 + (if (flip) pressBase * ripple else 0.0),
                )
                now += 250L
            }
        }
    }

    @Test
    fun fusionRevertsQuicklyFromAFalseStopCausedByASlowdown() {
        // The field case this exists for: a hard slowdown that reads as a
        // stop, then line speed again a few seconds later. What matters is
        // not that the false stop is avoided but that it is undone fast,
        // because a game layer can debounce a brief wrong state and cannot do
        // anything about a long one.
        val engine = InferenceEngine()
        val feed = FusionFeed(engine)

        feed.run(0.4, 0.004, 160)
        feed.run(5.0, 0.08, 200)
        assertEquals("运行", feed.last!!.trainState)

        // Ten seconds quiet enough to rank at the bottom of a window that is
        // otherwise all line speed.
        feed.run(1.2, 0.012, 40)
        val afterSlowdown = feed.last!!.trainState

        // Back to line speed. If the slowdown did flip the state, the way
        // back is a reversal and so needs only FUSION_REVERT_CONFIRMATION_MS
        // rather than the full moving confirmation.
        feed.run(5.0, 0.08, 10)
        if (afterSlowdown == "停站") {
            assertEquals("运行", feed.last!!.trainState)
            assertTrue(feed.last!!.reason.startsWith("fusion-moving-revert"))
        }

        // A genuine stop later on is not a reversal and still takes the full
        // confirmation, so it must not be mistaken for one.
        feed.run(5.0, 0.08, 200)
        assertEquals("运行", feed.last!!.trainState)
        feed.run(0.4, 0.004, 120)
        assertEquals("停站", feed.last!!.trainState)
        assertEquals("fusion-stop-hold", feed.last!!.reason)
    }

    @Test
    fun fusionReversalWindowExpiresSoALateSwingIsATransitionNotARevert() {
        val engine = InferenceEngine()
        val feed = FusionFeed(engine)
        feed.run(5.0, 0.08, 200)
        feed.run(0.4, 0.004, 160)
        assertEquals("停站", feed.last!!.trainState)

        // Well past FUSION_REVERT_WINDOW_MS, so going back to line speed is
        // an ordinary transition and is reported as one.
        feed.run(5.0, 0.08, 200)
        assertEquals("运行", feed.last!!.trainState)
        assertFalse(feed.last!!.reason.contains("revert"))
        assertEquals(0L, feed.last!!.fusionLockoutRemainingMs)
    }
}
