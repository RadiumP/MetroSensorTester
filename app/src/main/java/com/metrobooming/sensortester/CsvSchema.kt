package com.metrobooming.sensortester

import java.time.Instant
import kotlin.math.PI

data class CsvRowContext(
    val nowMs: Long,
    val startedAtMs: Long,
    val sensor: SensorSnapshot,
    val audio: AudioSnapshot,
    val inference: InferenceResult,
    val mark: String,
    val segmentId: Int,
    val appInForeground: Boolean,
    val screenOn: Boolean,
    val wakeLockHeld: Boolean,
    val foregroundServiceActive: Boolean,
    val deviceModel: String,
    val androidVersion: String,
)

object CsvSchema {
    const val SCHEMA_VERSION = 3

    val columns: List<Pair<String, (CsvRowContext) -> Any?>> = listOf(
        "ts" to { Instant.ofEpochMilli(it.nowMs).toString() },
        "elapsed_ms" to { it.nowMs - it.startedAtMs },
        "accel_rms" to { it.sensor.accelRms },
        "accel_peak" to { it.sensor.accelPeak },
        "gyro_rms_deg_s" to { it.sensor.gyroRms * 180.0 / PI },
        "gyro_peak_deg_s" to { it.sensor.gyroPeak * 180.0 / PI },
        "gyro_x_rad_s" to { it.sensor.gyroX },
        "gyro_y_rad_s" to { it.sensor.gyroY },
        "gyro_z_rad_s" to { it.sensor.gyroZ },
        "magnet_active" to { if (it.sensor.magnetMagnitude != null) 1 else 0 },
        "magnet_x_ut" to { it.sensor.magnetX },
        "magnet_y_ut" to { it.sensor.magnetY },
        "magnet_z_ut" to { it.sensor.magnetZ },
        "magnet_magnitude_ut" to { it.sensor.magnetMagnitude },
        "pressure_active" to { if (it.sensor.pressureHpa != null) 1 else 0 },
        "pressure_hpa" to { it.sensor.pressureHpa },
        "mic_active" to { if (it.audio.active) 1 else 0 },
        "mic_valid" to { if (it.audio.valid) 1 else 0 },
        "mic_rms" to { it.audio.rms },
        "mic_peak" to { it.audio.peak },
        "mic_input_device" to { it.audio.inputDevice },
        "state_schema_version" to { SCHEMA_VERSION },
        "raw_state" to { it.inference.rawState },
        "state" to { it.inference.state },
        "raw_train_state" to { it.inference.rawTrainState.label },
        "train_state" to { it.inference.trainState.label },
        "player_state" to { it.inference.playerState.label },
        "is_train_moving" to { if (it.inference.trainState == TrainState.MOVING) 1 else 0 },
        "is_player_active" to { if (it.inference.playerActive) 1 else 0 },
        "mic_level_ratio" to { it.inference.micLevelRatio },
        "mic_stop_rms_threshold" to { it.inference.fixedStopThreshold },
        "mic_moving_rms_threshold" to { it.inference.fixedMovingThreshold },
        "mic_above_moving_threshold" to { if (it.inference.micAboveMovingThreshold) 1 else 0 },
        "mic_below_stop_threshold" to { if (it.inference.micBelowStopThreshold) 1 else 0 },
        "stop_confirmation_ms" to { it.inference.stopConfirmationMs },
        "stop_candidate_elapsed_ms" to { it.inference.stopCandidateElapsedMs },
        "decision_reason" to { it.inference.reason },
        "mic_zero_duration_ms" to { it.audio.zeroDurationMs },
        "mic_restart_count" to { it.audio.restartCount },
        "mic_quality" to { it.audio.quality.label },
        "mic_fixed_stop_threshold" to { it.inference.fixedStopThreshold },
        "mic_fixed_moving_threshold" to { it.inference.fixedMovingThreshold },
        "mic_dynamic_stop_threshold" to { it.inference.dynamicStopThreshold },
        "mic_dynamic_moving_threshold" to { it.inference.dynamicMovingThreshold },
        "mic_effective_stop_threshold" to { it.inference.effectiveStopThreshold },
        "mic_effective_moving_threshold" to { it.inference.effectiveMovingThreshold },
        "mic_threshold_mode" to { it.inference.thresholdMode.label },
        "mic_p25" to { it.inference.micP25 },
        "mic_p70" to { it.inference.micP70 },
        "mic_valid_history_size" to { it.inference.validMicSampleCount },
        "moving_confirmation_ms" to { it.inference.movingConfirmationMs },
        "moving_candidate_elapsed_ms" to { it.inference.movingCandidateElapsedMs },
        "app_in_foreground" to { if (it.appInForeground) 1 else 0 },
        "screen_on" to { if (it.screenOn) 1 else 0 },
        "wake_lock_held" to { if (it.wakeLockHeld) 1 else 0 },
        "audio_record_state" to { it.audio.audioRecordState },
        "foreground_service_active" to { if (it.foregroundServiceActive) 1 else 0 },
        "mark" to { it.mark },
        "segment_id" to { it.segmentId },
        "device_model" to { it.deviceModel },
        "android_version" to { it.androidVersion },
    )

    val headers: List<String> = columns.map { it.first }

    fun buildRow(context: CsvRowContext): List<Any?> = columns.map { it.second(context) }

    fun escape(value: Any?): String {
        val string = value?.toString().orEmpty()
        return if (string.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${string.replace("\"", "\"\"")}\""
        } else {
            string
        }
    }
}
