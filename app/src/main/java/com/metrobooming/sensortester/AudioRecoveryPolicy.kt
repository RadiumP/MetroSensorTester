package com.metrobooming.sensortester

enum class AudioSourceMode {
    UNPROCESSED,
    MIC,
}

object AudioRecoveryPolicy {
    const val RESTART_AFTER_ZERO_MS = 5_000L
    const val MIN_RESTART_INTERVAL_MS = 15_000L

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
    ): AudioSourceMode = if (
        current == AudioSourceMode.UNPROCESSED && clientSilenced != true
    ) {
        AudioSourceMode.MIC
    } else {
        current
    }
}
