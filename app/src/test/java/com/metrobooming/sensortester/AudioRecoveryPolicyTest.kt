package com.metrobooming.sensortester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecoveryPolicyTest {
    @Test
    fun frameworkSilencingBlocksRepeatedZeroRestarts() {
        assertFalse(
            AudioRecoveryPolicy.shouldRestart(
                recorderMissing = false,
                zeroAbnormal = true,
                zeroDurationMs = 60_000L,
                clientSilenced = true,
                elapsedSinceRestartMs = 60_000L,
            )
        )
    }

    @Test
    fun unsilencedZeroPcmRestartsAfterConfirmationAndCooldown() {
        assertTrue(
            AudioRecoveryPolicy.shouldRestart(
                recorderMissing = false,
                zeroAbnormal = true,
                zeroDurationMs = 5_000L,
                clientSilenced = false,
                elapsedSinceRestartMs = 15_000L,
            )
        )
        assertFalse(
            AudioRecoveryPolicy.shouldRestart(
                recorderMissing = false,
                zeroAbnormal = true,
                zeroDurationMs = 4_999L,
                clientSilenced = false,
                elapsedSinceRestartMs = 15_000L,
            )
        )
    }

    @Test
    fun unavailableRecorderCanStillRecoverAfterCooldown() {
        assertTrue(
            AudioRecoveryPolicy.shouldRestart(
                recorderMissing = true,
                zeroAbnormal = false,
                zeroDurationMs = 0L,
                clientSilenced = true,
                elapsedSinceRestartMs = 15_000L,
            )
        )
    }

    @Test
    fun zeroFailureFallsBackFromUnprocessedToMic() {
        assertEquals(
            AudioSourceMode.MIC,
            AudioRecoveryPolicy.sourceAfterZeroFailure(
                AudioSourceMode.UNPROCESSED,
                clientSilenced = false,
            )
        )
        assertEquals(
            AudioSourceMode.UNPROCESSED,
            AudioRecoveryPolicy.sourceAfterZeroFailure(
                AudioSourceMode.UNPROCESSED,
                clientSilenced = true,
            )
        )
    }
}
