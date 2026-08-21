package com.metrobooming.sensortester

data class TuningConfig(
    val micNonZeroFloor: Double = 0.000001,
    val fixedStopThreshold: Double = 0.0014,
    val fixedMovingThreshold: Double = 0.0018,
    val stopConfirmationMs: Long = 3_000L,
    val movingConfirmationMs: Long = 1_750L,
    val playerAccelRmsThreshold: Double = 0.40,
    val playerGyroRmsThresholdDegS: Double = 15.0,
    val dynamicHistoryMs: Long = 180_000L,
    val minDynamicSamples: Int = 240,
    val minSamplesPerState: Int = 20,
    val thresholdUpdateIntervalMs: Long = 5_000L,
    val thresholdSmoothing: Double = 0.08,
    val minHysteresisGap: Double = 0.0002,
    val stopThresholdRange: ClosedFloatingPointRange<Double> = 0.0008..0.0016,
    val movingThresholdRange: ClosedFloatingPointRange<Double> = 0.0016..0.0030,
)
