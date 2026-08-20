package com.metrobooming.sensortester

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI

class MainActivity : Activity() {
    companion object {
        private const val AUDIO_PERMISSION_REQUEST = 100
        private const val EXPORT_REQUEST = 101
        private const val TICK_MS = 250L
    }

    private lateinit var sensors: SensorCollector
    private val audio = AudioCollector()
    private val inference = InferenceEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<List<Any?>>()

    private lateinit var statusText: TextView
    private lateinit var valuesText: TextView
    private lateinit var capabilityText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button
    private lateinit var markText: TextView

    private var recording = false
    private var startedAt = 0L
    private var currentMark = ""
    private var segmentId = 0
    private var pendingCsv: String? = null

    private val headers = listOf(
        "ts", "elapsed_ms", "accel_rms", "accel_peak",
        "gyro_rms_deg_s", "gyro_peak_deg_s", "gyro_x_rad_s", "gyro_y_rad_s", "gyro_z_rad_s",
        "magnet_active", "magnet_x_ut", "magnet_y_ut", "magnet_z_ut", "magnet_magnitude_ut",
        "pressure_active", "pressure_hpa",
        "mic_active", "mic_valid", "mic_rms", "mic_peak", "mic_input_device",
        "state_schema_version", "raw_state", "state", "raw_train_state", "train_state",
        "player_state", "is_train_moving", "is_player_active",
        "mic_level_ratio", "mic_stop_rms_threshold", "mic_moving_rms_threshold",
        "mic_above_moving_threshold", "mic_below_stop_threshold",
        "stop_confirmation_ms", "stop_candidate_elapsed_ms", "decision_reason",
        "mark", "segment_id", "device_model", "android_version"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = SensorCollector(this)
        setContentView(buildUi())
        showCapabilities()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 48)
            setBackgroundColor(Color.rgb(14, 23, 32))
        }
        scroll.addView(root)

        root.addView(text("Metro Sensor Tester", 25f, Color.WHITE).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(text("原生 Android 传感器采集器 · 250 ms/条", 14f, Color.LTGRAY))
        root.addView(text(versionLabel(), 13f, Color.GRAY))

        statusText = text("尚未开始", 22f, Color.rgb(91, 201, 232)).also { root.addView(it) }
        markText = text("人工标记：未标记", 16f, Color.WHITE).also { root.addView(it) }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = button("开始") { requestStart() }
        stopButton = button("停止") { stopRecording() }.apply { isEnabled = false }
        exportButton = button("导出 CSV") { exportCsv() }.apply { isEnabled = false }
        controls.addView(startButton, weighted())
        controls.addView(stopButton, weighted())
        controls.addView(exportButton, weighted())
        root.addView(controls)

        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.addView(button("停站") { setMark("停站") }, weighted())
        labels.addView(button("运行") { setMark("运行") }, weighted())
        labels.addView(button("清除标记") { setMark("") }, weighted())
        root.addView(labels)

        root.addView(section("实时数据"))
        valuesText = text("等待采集", 15f, Color.WHITE).also {
            it.typeface = android.graphics.Typeface.MONOSPACE
            root.addView(it)
        }
        root.addView(section("传感器能力"))
        capabilityText = text("检查中", 14f, Color.LTGRAY).also { root.addView(it) }
        root.addView(text(
            "说明：本版本只在界面保持打开时采集。请使用人工标记记录真实的运行/停站状态。",
            13f,
            Color.GRAY,
        ))
        return scroll
    }

    @Suppress("DEPRECATION")
    private fun versionLabel(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        return "版本 ${packageInfo.versionName.orEmpty()} ($versionCode)"
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, 10, 0, 10)
    }

    private fun section(value: String) = text(value, 18f, Color.rgb(168, 214, 108)).apply {
        setPadding(0, 28, 0, 8)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun showCapabilities() {
        capabilityText.text = sensors.capabilities().joinToString("\n") { item ->
            if (item.available) {
                val rate = item.maxRateHz?.let { " · 最高约 %.0f Hz".format(it) }.orEmpty()
                "✓ ${item.label}: ${item.name} · ${item.vendor}$rate"
            } else {
                "✗ ${item.label}: 本机没有该传感器"
            }
        }
    }

    private fun requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "没有麦克风权限，无法开始完整测试", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        rows.clear()
        inference.reset()
        currentMark = ""
        segmentId = 0
        startedAt = System.currentTimeMillis()
        sensors.start()
        val micStarted = audio.start()
        recording = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        exportButton.isEnabled = false
        statusText.text = if (micStarted) "校准中" else "采集中（麦克风启动失败）"
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val now = System.currentTimeMillis()
            val sensor = sensors.takeSnapshot()
            val mic = audio.takeSnapshot()
            val gyroRmsDeg = sensor.gyroRms * 180.0 / PI
            val gyroPeakDeg = sensor.gyroPeak * 180.0 / PI
            val inferred = inference.update(
                mic.rms, mic.valid, sensor.accelRms, gyroRmsDeg, now
            )

            statusText.text = "${inferred.state} · ${formatElapsed(now - startedAt)} · ${rows.size + 1} 条"
            statusText.setTextColor(
                when (inferred.state) {
                    "运行" -> Color.rgb(255, 159, 69)
                    "停站" -> Color.rgb(168, 214, 108)
                    "停站但玩家活动" -> Color.rgb(255, 214, 102)
                    else -> Color.rgb(91, 201, 232)
                }
            )
            valuesText.text = buildString {
                appendLine("麦克风 RMS : %.5f".format(Locale.US, mic.rms))
                appendLine("麦克风比值  : %.2f ×".format(Locale.US, inferred.micLevelRatio))
                appendLine("输入设备    : ${mic.inputDevice}")
                appendLine("加速度 RMS : %.4f m/s²".format(Locale.US, sensor.accelRms))
                appendLine("陀螺仪 RMS : %.2f °/s".format(Locale.US, gyroRmsDeg))
                appendLine("玩家活动    : ${if (inferred.playerActive) "是" else "否"}")
                appendLine("磁场强度    : ${format(sensor.magnetMagnitude, 2)} μT")
                appendLine("气压        : ${format(sensor.pressureHpa?.toDouble(), 2)} hPa")
                append("判定依据    : ${inferred.reason}")
            }

            rows += listOf(
                Instant.ofEpochMilli(now).toString(), now - startedAt,
                sensor.accelRms, sensor.accelPeak,
                gyroRmsDeg, gyroPeakDeg, sensor.gyroX, sensor.gyroY, sensor.gyroZ,
                if (sensor.magnetMagnitude != null) 1 else 0,
                sensor.magnetX, sensor.magnetY, sensor.magnetZ, sensor.magnetMagnitude,
                if (sensor.pressureHpa != null) 1 else 0, sensor.pressureHpa,
                if (mic.active) 1 else 0, if (mic.valid) 1 else 0, mic.rms, mic.peak, mic.inputDevice,
                2, inferred.rawState, inferred.state,
                inferred.rawTrainState, inferred.trainState,
                if (inferred.playerActive) "活动" else "静止",
                if (inferred.trainState == "运行") 1 else 0,
                if (inferred.playerActive) 1 else 0,
                inferred.micLevelRatio,
                InferenceEngine.MIC_STOP_RMS_THRESHOLD,
                InferenceEngine.MIC_MOVING_RMS_THRESHOLD,
                if (inferred.micAboveMovingThreshold) 1 else 0,
                if (inferred.micBelowStopThreshold) 1 else 0,
                InferenceEngine.STOP_CONFIRMATION_MS,
                inferred.stopCandidateElapsedMs, inferred.reason,
                currentMark, segmentId, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE,
            )
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun setMark(mark: String) {
        if (mark != currentMark) segmentId++
        currentMark = mark
        markText.text = "人工标记：${mark.ifEmpty { "未标记" }}"
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(tick)
        sensors.stop()
        audio.stop()
        startButton.isEnabled = true
        stopButton.isEnabled = false
        exportButton.isEnabled = rows.isNotEmpty()
        statusText.text = "已停止 · 共 ${rows.size} 条"
    }

    private fun exportCsv() {
        if (rows.isEmpty()) return
        pendingCsv = buildCsv()
        val localTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "metro-native-$localTime.csv")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write("\uFEFF")
                it.write(pendingCsv.orEmpty())
            }
            Toast.makeText(this, "CSV 已保存", Toast.LENGTH_LONG).show()
            pendingCsv = null
        }
    }

    private fun buildCsv(): String = buildString {
        appendLine(headers.joinToString(","))
        rows.forEach { row -> appendLine(row.joinToString(",") { csvEscape(it) }) }
    }

    private fun csvEscape(value: Any?): String {
        val string = value?.toString().orEmpty().replace("\"", "\"\"")
        return "\"$string\""
    }

    private fun format(value: Double?, decimals: Int): String =
        value?.let { "% .${decimals}f".trim().format(Locale.US, it) } ?: "不支持"

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        super.onDestroy()
    }
}
