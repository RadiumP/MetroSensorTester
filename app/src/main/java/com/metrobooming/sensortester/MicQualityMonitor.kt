package com.metrobooming.sensortester

data class MicQualityResult(
    val valid: Boolean,
    val quality: String,
    val zeroDurationMs: Long,
)

class MicQualityMonitor {
    companion object {
        const val QUALITY_NORMAL = "正常"
        const val QUALITY_ZERO_ABNORMAL = "全零异常"
        const val QUALITY_RECOVERING = "恢复中"

        private const val ZERO_ABNORMAL_MS = 2_000L
        private const val NORMAL_RECOVERY_MS = 1_000L
    }

    private var quality = QUALITY_RECOVERING
    private var zeroSince: Long? = null
    private var recoverySince: Long? = null

    fun reset(): MicQualityResult {
        quality = QUALITY_RECOVERING
        zeroSince = null
        recoverySince = null
        return MicQualityResult(false, quality, 0L)
    }

    fun update(rms: Double, hasSamples: Boolean, now: Long): MicQualityResult {
        val nonZero = hasSamples && rms >= InferenceEngine.MIC_NONZERO_FLOOR
        var valid = false
        var zeroDurationMs = 0L

        if (nonZero) {
            zeroSince = null
            if (quality == QUALITY_NORMAL) {
                recoverySince = null
                valid = true
            } else {
                val recoveryStart = recoverySince ?: now.also { recoverySince = it }
                if (now - recoveryStart >= NORMAL_RECOVERY_MS) {
                    quality = QUALITY_NORMAL
                    recoverySince = null
                    valid = true
                } else {
                    quality = QUALITY_RECOVERING
                }
            }
        } else if (hasSamples) {
            recoverySince = null
            val zeroStart = zeroSince ?: now.also { zeroSince = it }
            zeroDurationMs = (now - zeroStart).coerceAtLeast(0L)
            if (zeroDurationMs >= ZERO_ABNORMAL_MS) {
                quality = QUALITY_ZERO_ABNORMAL
                valid = false
            } else {
                valid = quality == QUALITY_NORMAL
            }
        } else {
            recoverySince = null
            valid = false
            if (quality != QUALITY_ZERO_ABNORMAL) quality = QUALITY_RECOVERING
        }

        return MicQualityResult(valid, quality, zeroDurationMs)
    }
}
