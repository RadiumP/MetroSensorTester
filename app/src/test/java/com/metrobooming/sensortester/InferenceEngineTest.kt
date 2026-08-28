package com.metrobooming.sensortester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceEngineTest {
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
        val engine = InferenceEngine()
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
        val engine = InferenceEngine()
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
        val engine = InferenceEngine()
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
        val engine = InferenceEngine()
        engine.update(0.0010, true, 0.0, 0.1, 1.0, null, 0L)
        val result = engine.update(0.0010, true, 0.0, 0.6, 1.0, null, 3_000L)

        assertEquals("停站", result.trainState)
        assertEquals("活动", result.playerState)
        assertTrue(result.playerActive)
        assertEquals("停站但玩家活动", result.state)
    }

    @Test
    fun dynamicThresholdsUseMixedValidHistoryAndFreezeOnSingleState() {
        val engine = InferenceEngine()
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
        val engine = InferenceEngine()
        var now = 0L

        engine.update(0.0009, true, 0.0, 0.1, 1.0, null, now)
        now += InferenceEngine.STOP_CONFIRMATION_MS
        engine.update(0.0009, true, 0.0, 0.1, 1.0, null, now)

        // 700 "停站" samples: mostly quiet, ~10% loud platform noise -- but
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
        // clamped it down to MIN_MOVING_THRESHOLD (0.0016) -- below the fixed
        // default. The per-state P25-of-moving-only calculation must instead
        // stay at or above the genuine moving noise level.
        assertTrue(dynamic.effectiveMovingThreshold >= InferenceEngine.MIC_MOVING_RMS_THRESHOLD)
    }

    @Test
    fun flagsChimeCandidateOnlyForSharpPeakRelativeToRms() {
        val engine = InferenceEngine()

        // Ordinary cabin noise: peak/rms crest factor around 3-4x, as seen in
        // real ride recordings. Should never flag as a chime candidate no
        // matter how loud it gets.
        val ordinaryLoud = engine.update(0.0030, true, 0.0030 * 4.0, 0.1, 1.0, null, 0L)
        assertFalse(ordinaryLoud.micChimeCandidate)

        // A brief, sharp tone (the fixed station announcement chime) barely
        // moves the windowed RMS average but spikes the peak -- crest factor
        // far above ordinary cabin noise.
        val chime = engine.update(0.0012, true, 0.0012 * 12.0, 0.1, 1.0, null, 250L)
        assertTrue(chime.micChimeCandidate)
        assertEquals(12.0, chime.micCrestFactor, 0.001)

        // A high crest factor on a near-silent tick shouldn't count -- too
        // quiet to plausibly be the chime, more likely mic noise floor.
        val quietSpike = engine.update(0.0001, true, 0.0001 * 12.0, 0.1, 1.0, null, 500L)
        assertFalse(quietSpike.micChimeCandidate)
    }

    @Test
    fun computesMagnetMagnitudeJitterAsAbsoluteChangeSinceLastSample() {
        val engine = InferenceEngine()

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
}
