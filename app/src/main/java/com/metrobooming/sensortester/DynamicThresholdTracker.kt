package com.metrobooming.sensortester

class DynamicThresholdTracker(private val config: TuningConfig) {

    private data class Sample(val timestampMs: Long, val rms: Double, val trainState: TrainState)

    private val history = ArrayDeque<Sample>()
    private var dynamicStopThreshold: Double? = null
    private var dynamicMovingThreshold: Double? = null
    private var lastUpdateAt = 0L
    private var p25: Double? = null
    private var p70: Double? = null

    val stopThreshold: Double? get() = dynamicStopThreshold
    val movingThreshold: Double? get() = dynamicMovingThreshold
    val lastP25: Double? get() = p25
    val lastP70: Double? get() = p70
    val sampleCount: Int get() = history.size

    fun reset() {
        history.clear()
        dynamicStopThreshold = null
        dynamicMovingThreshold = null
        lastUpdateAt = 0L
        p25 = null
        p70 = null
    }

    fun prepare(now: Long) {
        purgeExpired(now)
        maybeUpdateThresholds(now)
    }

    fun record(now: Long, rms: Double, trainState: TrainState) {
        history.addLast(Sample(now, rms, trainState))
    }

    private fun purgeExpired(now: Long) {
        val oldestAllowed = now - config.dynamicHistoryMs
        while (history.firstOrNull()?.timestampMs?.let { it < oldestAllowed } == true) {
            history.removeFirst()
        }
    }

    private fun maybeUpdateThresholds(now: Long) {
        if (history.size < config.minDynamicSamples) return
        if (lastUpdateAt != 0L && now - lastUpdateAt < config.thresholdUpdateIntervalMs) {
            return
        }

        val movingCount = history.count { it.trainState == TrainState.MOVING }
        val stoppedCount = history.count { it.trainState == TrainState.STOPPED }
        if (movingCount < config.minSamplesPerState || stoppedCount < config.minSamplesPerState) {
            return
        }

        val sorted = history.map { it.rms }.sorted()
        val newP25 = percentile(sorted, 0.25)
        val newP70 = percentile(sorted, 0.70)
        var targetStop = newP25.coerceIn(config.stopThresholdRange)
        var targetMoving = newP70.coerceIn(config.movingThresholdRange)

        if (targetMoving - targetStop < config.minHysteresisGap) {
            targetMoving = (targetStop + config.minHysteresisGap)
                .coerceIn(config.movingThresholdRange)
            if (targetMoving - targetStop < config.minHysteresisGap) {
                targetStop = (targetMoving - config.minHysteresisGap)
                    .coerceIn(config.stopThresholdRange)
            }
        }

        dynamicStopThreshold = smooth(
            dynamicStopThreshold ?: config.fixedStopThreshold,
            targetStop,
        )
        dynamicMovingThreshold = smooth(
            dynamicMovingThreshold ?: config.fixedMovingThreshold,
            targetMoving,
        )
        p25 = newP25
        p70 = newP70
        lastUpdateAt = now
    }

    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun smooth(current: Double, target: Double): Double =
        current + config.thresholdSmoothing * (target - current)
}
