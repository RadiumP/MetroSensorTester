package com.metrobooming.sensortester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceEngineTest {
    @Test
    fun stopRequiresThreeContinuousSeconds() {
        val engine = InferenceEngine()

        assertEquals("运行", engine.update(0.0030, true, 0.1, 1.0, 100L).trainState)
        assertEquals("运行", engine.update(0.0010, true, 0.1, 1.0, 1_000L).trainState)
        assertEquals("运行", engine.update(0.0017, true, 0.1, 1.0, 3_999L).trainState)

        val confirmed = engine.update(0.0017, true, 0.1, 1.0, 4_000L)
        assertEquals("停站", confirmed.trainState)
        assertEquals("停站", confirmed.state)
    }

    @Test
    fun movingSignalCancelsStopCandidate() {
        val engine = InferenceEngine()
        engine.update(0.0030, true, 0.1, 1.0, 100L)
        engine.update(0.0010, true, 0.1, 1.0, 1_000L)
        engine.update(0.0032, true, 0.1, 1.0, 2_000L)

        assertEquals("运行", engine.update(0.0010, true, 0.1, 1.0, 4_500L).trainState)
        assertEquals("停站", engine.update(0.0010, true, 0.1, 1.0, 7_500L).trainState)
    }

    @Test
    fun reportsStoppedWhilePlayerIsActive() {
        val engine = InferenceEngine()
        engine.update(0.0010, true, 0.1, 1.0, 1_000L)
        val result = engine.update(0.0010, true, 0.6, 1.0, 4_000L)

        assertEquals("停站", result.trainState)
        assertTrue(result.playerActive)
        assertEquals("停站但玩家活动", result.state)
        assertEquals("stopped-player-active", result.reason)
    }

    @Test
    fun hysteresisHoldsStableTrainState() {
        val engine = InferenceEngine()
        engine.update(0.0030, true, 0.1, 1.0, 100L)

        val movingBand = engine.update(0.0017, true, 0.1, 1.0, 200L)
        assertEquals("运行", movingBand.trainState)
        assertFalse(movingBand.micAboveMovingThreshold)
        assertFalse(movingBand.micBelowStopThreshold)

        engine.update(0.0010, true, 0.1, 1.0, 1_000L)
        engine.update(0.0010, true, 0.1, 1.0, 4_000L)
        val stoppedBand = engine.update(0.0017, true, 0.1, 1.0, 4_250L)
        assertEquals("停站", stoppedBand.trainState)
    }
}
