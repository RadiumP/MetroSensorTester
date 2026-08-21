package com.metrobooming.sensortester

data class MicQualityResult(
    val valid: Boolean,
    val quality: MicQuality,
    val zeroDurationMs: Long,
)

class MicQualityMonitor(
    private val nonzeroFloor: Double = TuningConfig().micNonZeroFloor,
) {
    companion object {
        private const val ZERO_ABNORMAL_MS = 2_000L
        private const val NORMAL_RECOVERY_MS = 1_000L
    }

    private var quality = MicQuality.RECOVERING
    private var zeroSince: Long? = null
    private var recoverySince: Long? = null

    fun reset(): MicQualityResult {
        quality = MicQuality.RECOVERING
        zeroSince = null
        recoverySince = null
        return MicQualityResult(false, quality, 0L)
    }

    fun update(rms: Double, hasSamples: Boolean, now: Long): MicQualityResult {
        val nonZero = hasSamples && rms >= nonzeroFloor
        var valid = false
        var zeroDurationMs = 0L

        if (nonZero) {
            zeroSince = null
            if (quality == MicQuality.NORMAL) {
                recoverySince = null
                valid = true
            } else {
                val recoveryStart = recoverySince ?: now.also { recoverySince = it }
                if (now - recoveryStart >= NORMAL_RECOVERY_MS) {
                    quality = MicQuality.NORMAL
                    recoverySince = null
                    valid = true
                } else {
                    quality = MicQuality.RECOVERING
                }
            }
        } else if (hasSamples) {
            recoverySince = null
            val zeroStart = zeroSince ?: now.also { zeroSince = it }
            zeroDurationMs = (now - zeroStart).coerceAtLeast(0L)
            if (zeroDurationMs >= ZERO_ABNORMAL_MS) {
                quality = MicQuality.ZERO_ABNORMAL
                valid = false
            } else {
                valid = quality == MicQuality.NORMAL
            }
        } else {
            recoverySince = null
            valid = false
            if (quality != MicQuality.ZERO_ABNORMAL) quality = MicQuality.RECOVERING
        }

        return MicQualityResult(valid, quality, zeroDurationMs)
    }
}
