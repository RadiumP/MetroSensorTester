package com.metrobooming.sensortester

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class CsvReplayTest {
    private val config = TuningConfig()

    @Test
    fun replayConfiguredCsvFiles() {
        val configured = System.getenv("METRO_REPLAY_CSV_PATHS").orEmpty()
        assumeTrue("METRO_REPLAY_CSV_PATHS is not configured", configured.isNotBlank())
        val files = configured.split(File.pathSeparatorChar).map(::File)
        files.forEach { require(it.isFile) { "CSV not found: $it" } }

        val summaries = files.map(::replay)
        val totalEvaluated = summaries.sumOf { it.evaluated }
        val totalCorrect = summaries.sumOf { it.correct }
        val totalRunning = summaries.sumOf { it.runningTotal }
        val totalRunningCorrect = summaries.sumOf { it.runningCorrect }
        val totalStopped = summaries.sumOf { it.stoppedTotal }
        val totalStoppedCorrect = summaries.sumOf { it.stoppedCorrect }

        summaries.forEach { summary ->
            println(
                "REPLAY file=${summary.file.name} rows=${summary.rows} " +
                    "evaluated=${summary.evaluated} accuracy=${ratio(summary.correct, summary.evaluated)} " +
                    "running_recall=${ratio(summary.runningCorrect, summary.runningTotal)} " +
                    "stopped_recall=${ratio(summary.stoppedCorrect, summary.stoppedTotal)} " +
                    "zero_abnormal_rows=${summary.zeroAbnormalRows} " +
                    "max_zero_ms=${summary.maxZeroDurationMs} " +
                    "zero_state_changes=${summary.zeroStateChanges} " +
                    "abnormal_state_changes=${summary.abnormalStateChanges}",
            )
        }
        println(
            "REPLAY_TOTAL evaluated=$totalEvaluated " +
                "accuracy=${ratio(totalCorrect, totalEvaluated)} " +
                "running_recall=${ratio(totalRunningCorrect, totalRunning)} " +
                "stopped_recall=${ratio(totalStoppedCorrect, totalStopped)}",
        )
    }

    private fun replay(file: File): ReplaySummary {
        val lines = file.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        require(lines.isNotEmpty()) { "Empty CSV: $file" }
        val headers = parseCsvLine(lines.first().removePrefix("\uFEFF"))
        val indexes = headers.withIndex().associate { it.value to it.index }
        fun index(name: String): Int = requireNotNull(indexes[name]) { "Missing field $name in $file" }

        val elapsedIndex = index("elapsed_ms")
        val micActiveIndex = index("mic_active")
        val micRmsIndex = index("mic_rms")
        val accelIndex = index("accel_rms")
        val gyroIndex = index("gyro_rms_deg_s")
        val markIndex = index("mark")

        val monitor = MicQualityMonitor()
        val engine = InferenceEngine()
        var evaluated = 0
        var correct = 0
        var runningTotal = 0
        var runningCorrect = 0
        var stoppedTotal = 0
        var stoppedCorrect = 0
        var zeroAbnormalRows = 0
        var maxZeroDurationMs = 0L
        var abnormalStateChanges = 0
        var zeroStateChanges = 0
        var heldZeroTrainState: TrainState? = null
        var heldZeroHistorySize: Int? = null
        var heldZeroDynamicStop: Double? = null
        var heldZeroDynamicMoving: Double? = null
        var heldTrainState: TrainState? = null
        var heldHistorySize: Int? = null
        var heldDynamicStop: Double? = null
        var heldDynamicMoving: Double? = null

        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val row = parseCsvLine(line)
            val now = row[elapsedIndex].toLong()
            val rms = row[micRmsIndex].toDouble()
            val quality = monitor.update(
                rms = rms,
                hasSamples = row[micActiveIndex] == "1",
                now = now,
            )
            val result = engine.update(
                micRms = rms,
                micValid = quality.valid,
                accelRms = row[accelIndex].toDouble(),
                gyroDegreesRms = row[gyroIndex].toDouble(),
                now = now,
            )

            if (rms < config.micNonZeroFloor) {
                if (heldZeroTrainState == null) {
                    heldZeroTrainState = result.trainState
                    heldZeroHistorySize = result.validMicSampleCount
                    heldZeroDynamicStop = result.dynamicStopThreshold
                    heldZeroDynamicMoving = result.dynamicMovingThreshold
                } else {
                    if (result.trainState != heldZeroTrainState) zeroStateChanges++
                    assertEquals(heldZeroHistorySize, result.validMicSampleCount)
                    assertEquals(heldZeroDynamicStop, result.dynamicStopThreshold)
                    assertEquals(heldZeroDynamicMoving, result.dynamicMovingThreshold)
                }
            } else {
                heldZeroTrainState = null
                heldZeroHistorySize = null
                heldZeroDynamicStop = null
                heldZeroDynamicMoving = null
            }

            if (quality.quality == MicQuality.ZERO_ABNORMAL) {
                zeroAbnormalRows++
                maxZeroDurationMs = maxOf(maxZeroDurationMs, quality.zeroDurationMs)
                if (heldTrainState == null) {
                    heldTrainState = result.trainState
                    heldHistorySize = result.validMicSampleCount
                    heldDynamicStop = result.dynamicStopThreshold
                    heldDynamicMoving = result.dynamicMovingThreshold
                } else {
                    if (result.trainState != heldTrainState) abnormalStateChanges++
                    assertEquals(heldHistorySize, result.validMicSampleCount)
                    assertEquals(heldDynamicStop, result.dynamicStopThreshold)
                    assertEquals(heldDynamicMoving, result.dynamicMovingThreshold)
                }
            } else {
                heldTrainState = null
                heldHistorySize = null
                heldDynamicStop = null
                heldDynamicMoving = null
            }

            val markState = TrainState.fromLabel(row[markIndex])
            if (
                markState != null && markState != TrainState.CALIBRATING &&
                result.trainState != TrainState.CALIBRATING
            ) {
                evaluated++
                if (result.trainState == markState) correct++
                when (markState) {
                    TrainState.MOVING -> {
                        runningTotal++
                        if (result.trainState == TrainState.MOVING) runningCorrect++
                    }

                    TrainState.STOPPED -> {
                        stoppedTotal++
                        if (result.trainState == TrainState.STOPPED) stoppedCorrect++
                    }

                    else -> {}
                }
            }
        }

        assertEquals("Train state changed during near-zero audio", 0, zeroStateChanges)
        assertEquals("Train state changed during all-zero abnormal audio", 0, abnormalStateChanges)
        return ReplaySummary(
            file = file,
            rows = lines.size - 1,
            evaluated = evaluated,
            correct = correct,
            runningTotal = runningTotal,
            runningCorrect = runningCorrect,
            stoppedTotal = stoppedTotal,
            stoppedCorrect = stoppedCorrect,
            zeroAbnormalRows = zeroAbnormalRows,
            maxZeroDurationMs = maxZeroDurationMs,
            zeroStateChanges = zeroStateChanges,
            abnormalStateChanges = abnormalStateChanges,
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }

                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += field.toString()
                    field.clear()
                }

                else -> field.append(char)
            }
            index++
        }
        result += field.toString()
        return result
    }

    private fun ratio(numerator: Int, denominator: Int): String =
        if (denominator == 0) "n/a" else "%.4f".format(Locale.US, numerator.toDouble() / denominator)

    private data class ReplaySummary(
        val file: File,
        val rows: Int,
        val evaluated: Int,
        val correct: Int,
        val runningTotal: Int,
        val runningCorrect: Int,
        val stoppedTotal: Int,
        val stoppedCorrect: Int,
        val zeroAbnormalRows: Int,
        val maxZeroDurationMs: Long,
        val zeroStateChanges: Int,
        val abnormalStateChanges: Int,
    )
}
