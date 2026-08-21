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
        engine.update(0.0022, true, 0.1, 1.0, 0L)
        assertEquals("运行", engine.update(0.0022, true, 0.1, 1.0, 1_750L).trainState)

        engine.update(0.0010, true, 0.1, 1.0, 2_000L)
        assertEquals(
            "运行",
            engine.update(0.0010, true, 0.1, 1.0, 4_999L).trainState,
        )
        assertEquals(
            "停站",
            engine.update(0.0010, true, 0.1, 1.0, 5_000L).trainState,
        )
    }

    @Test
    fun movingRequiresConfirmationAndAmbiguousBandResetsCandidate() {
        val engine = InferenceEngine()
        engine.update(0.0010, true, 0.1, 1.0, 0L)
        assertEquals("停站", engine.update(0.0010, true, 0.1, 1.0, 3_000L).trainState)

        engine.update(0.0022, true, 0.1, 1.0, 4_000L)
        assertEquals(
            "停站",
            engine.update(0.0016, true, 0.1, 1.0, 5_000L).trainState,
        )
        engine.update(0.0022, true, 0.1, 1.0, 5_500L)
        assertEquals(
            "停站",
            engine.update(0.0022, true, 0.1, 1.0, 7_249L).trainState,
        )
        assertEquals(
            "运行",
            engine.update(0.0022, true, 0.1, 1.0, 7_250L).trainState,
        )
    }

    @Test
    fun invalidMicrophoneHoldsTrainStateAndDoesNotUpdateHistory() {
        val engine = InferenceEngine()
        engine.update(0.0022, true, 0.1, 1.0, 0L)
        engine.update(0.0022, true, 0.1, 1.0, 1_750L)
        repeat(12) { index ->
            engine.update(0.0022, true, 0.1, 1.0, 2_000L + index * 250L)
        }
        val before = engine.update(0.0022, true, 0.1, 1.0, 5_000L)

        val duringInitialZero = engine.update(0.0, true, 0.1, 1.0, 10_000L)
        assertEquals("运行", duringInitialZero.trainState)
        assertEquals(before.validMicSampleCount, duringInitialZero.validMicSampleCount)

        val duringZero = engine.update(0.0, false, 0.1, 1.0, 30_000L)
        assertEquals("运行", duringZero.trainState)
        assertEquals(before.validMicSampleCount, duringZero.validMicSampleCount)
        assertEquals(before.effectiveStopThreshold, duringZero.effectiveStopThreshold, 0.0)
        assertEquals(before.effectiveMovingThreshold, duringZero.effectiveMovingThreshold, 0.0)

        val muchLater = engine.update(0.0, false, 0.1, 1.0, 300_000L)
        assertEquals("运行", muchLater.trainState)
        assertEquals(before.validMicSampleCount, muchLater.validMicSampleCount)
    }

    @Test
    fun reportsStoppedWhilePlayerIsActive() {
        val engine = InferenceEngine()
        engine.update(0.0010, true, 0.1, 1.0, 0L)
        val result = engine.update(0.0010, true, 0.6, 1.0, 3_000L)

        assertEquals("停站", result.trainState)
        assertEquals("活动", result.playerState)
        assertTrue(result.playerActive)
        assertEquals("停站但玩家活动", result.state)
    }

    @Test
    fun dynamicThresholdsUseMixedValidHistoryAndFreezeOnSingleState() {
        val engine = InferenceEngine()
        var now = 0L
        engine.update(0.0024, true, 0.1, 1.0, now)
        now += InferenceEngine.MOVING_CONFIRMATION_MS
        engine.update(0.0024, true, 0.1, 1.0, now)

        repeat(180) { index ->
            now += 250L
            val rms = 0.0020 + (index % 5) * 0.0001
            engine.update(rms, true, 0.1, 1.0, now)
        }
        now += 250L
        engine.update(0.0010, true, 0.1, 1.0, now)
        now += InferenceEngine.STOP_CONFIRMATION_MS
        engine.update(0.0010, true, 0.1, 1.0, now)

        var dynamicResult: InferenceResult? = null
        repeat(100) { index ->
            now += 250L
            val rms = 0.0009 + (index % 4) * 0.00005
            dynamicResult = engine.update(rms, true, 0.1, 1.0, now)
        }
        val dynamic = requireNotNull(dynamicResult)
        assertEquals("动态", dynamic.thresholdMode)
        assertNotNull(dynamic.dynamicStopThreshold)
        assertNotNull(dynamic.dynamicMovingThreshold)
        assertTrue(dynamic.effectiveStopThreshold in 0.0008..0.0016)
        assertTrue(dynamic.effectiveMovingThreshold in 0.0016..0.0030)
        assertTrue(dynamic.effectiveMovingThreshold - dynamic.effectiveStopThreshold >= 0.0002)

        val beforeInvalid = dynamic
        val invalid = engine.update(0.0, false, 0.1, 1.0, now + 60_000L)
        assertEquals(beforeInvalid.validMicSampleCount, invalid.validMicSampleCount)
        assertEquals(beforeInvalid.dynamicStopThreshold, invalid.dynamicStopThreshold)
        assertEquals(beforeInvalid.dynamicMovingThreshold, invalid.dynamicMovingThreshold)

        repeat(800) {
            now += 250L
            engine.update(0.0024, true, 0.1, 1.0, now)
        }
        val frozen = engine.update(0.0024, true, 0.1, 1.0, now + 250L)
        repeat(240) {
            now += 250L
            engine.update(0.0024, true, 0.1, 1.0, now)
        }
        val stillFrozen = engine.update(0.0024, true, 0.1, 1.0, now + 250L)
        assertEquals(frozen.dynamicStopThreshold, stillFrozen.dynamicStopThreshold)
        assertEquals(frozen.dynamicMovingThreshold, stillFrozen.dynamicMovingThreshold)
    }
}
