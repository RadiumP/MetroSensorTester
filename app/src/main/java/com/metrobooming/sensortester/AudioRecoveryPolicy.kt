package com.metrobooming.sensortester

enum class AudioSourceMode {
    UNPROCESSED,
    MIC,
}

object AudioRecoveryPolicy {
    const val RESTART_AFTER_ZERO_MS = 5_000L
    const val MIN_RESTART_INTERVAL_MS = 15_000L

    // How many consecutive zero-PCM failures on UNPROCESSED to tolerate
    // before giving up on it and falling back to MIC. Real-ride field test
    // (2026-09-02): a single zero-PCM event triggered by a brief OS-level
    // audio-focus loss (switching apps while wearing headphones) was enough
    // to permanently switch this device to MIC for the rest of the
    // recording -- even though UNPROCESSED itself wasn't actually broken,
    // it just needed a moment to recover. MIC produces RMS values on a very
    // different (much louder, ~100x in that ride) scale than UNPROCESSED,
    // and nothing re-derives the train-state thresholds for that, so the
    // rest of that ride read as permanently "运行" regardless of the real
    // train state. Requiring a second consecutive failure before escalating
    // gives a transient hiccup one chance to just be a hiccup.
    const val UNPROCESSED_RETRY_ATTEMPTS = 2

    fun shouldRestart(
        recorderMissing: Boolean,
        zeroAbnormal: Boolean,
        zeroDurationMs: Long,
        clientSilenced: Boolean?,
        elapsedSinceRestartMs: Long,
    ): Boolean {
        if (elapsedSinceRestartMs < MIN_RESTART_INTERVAL_MS) return false
        if (recorderMissing) return true
        return zeroAbnormal &&
            zeroDurationMs >= RESTART_AFTER_ZERO_MS &&
            clientSilenced != true
    }

    fun sourceAfterZeroFailure(
        current: AudioSourceMode,
        clientSilenced: Boolean?,
        unprocessedZeroFailureCount: Int,
    ): AudioSourceMode = if (
        current == AudioSourceMode.UNPROCESSED &&
        clientSilenced != true &&
        unprocessedZeroFailureCount >= UNPROCESSED_RETRY_ATTEMPTS
    ) {
        AudioSourceMode.MIC
    } else {
        current
    }
}
