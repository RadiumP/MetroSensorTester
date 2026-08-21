package com.metrobooming.sensortester

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

class MainActivity : Activity() {
    companion object {
        private const val START_PERMISSION_REQUEST = 100
        private const val EXPORT_REQUEST = 101
        private const val UI_TICK_MS = 250L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recordingService: RecordingService? = null
    private var serviceBound = false
    private var pendingCsv: String? = null

    private lateinit var statusText: TextView
    private lateinit var valuesText: TextView
    private lateinit var capabilityText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button
    private lateinit var markText: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as RecordingService.LocalBinder).getService()
            serviceBound = true
            recordingService?.setAppInForeground(true)
            showCapabilities()
            updateUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            recordingService = null
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, RecordingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        handler.post(uiTick)
    }

    override fun onStop() {
        handler.removeCallbacks(uiTick)
        recordingService?.setAppInForeground(false)
        if (serviceBound) unbindService(serviceConnection)
        serviceBound = false
        recordingService = null
        super.onStop()
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

        statusText = text("正在连接采集服务", 22f, Color.rgb(91, 201, 232)).also {
            root.addView(it)
        }
        markText = text("人工标记：未标记", 16f, Color.WHITE).also { root.addView(it) }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = button("开始") { requestStart() }.apply { isEnabled = false }
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

        root.addView(section("实时状态"))
        valuesText = text("等待采集服务", 15f, Color.WHITE).also {
            it.typeface = android.graphics.Typeface.MONOSPACE
            root.addView(it)
        }
        root.addView(section("传感器能力"))
        capabilityText = text("等待服务连接", 14f, Color.LTGRAY).also { root.addView(it) }
        root.addView(text(
            "说明：采集由前台服务保持。关闭界面或关闭屏幕后仍会继续，必须点击“停止”才会释放麦克风、传感器和 WakeLock。",
            13f,
            Color.GRAY,
        ))
        return scroll
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, UI_TICK_MS)
        }
    }

    private fun updateUi() {
        val status = recordingService?.getStatus()
        if (status == null) {
            statusText.text = if (serviceBound) "采集服务已连接" else "正在连接采集服务"
            startButton.isEnabled = serviceBound
            stopButton.isEnabled = false
            exportButton.isEnabled = false
            return
        }

        startButton.isEnabled = serviceBound && !status.recording
        stopButton.isEnabled = serviceBound && status.recording
        exportButton.isEnabled = serviceBound && !status.recording && status.rowCount > 0
        markText.text = "人工标记：${status.currentMark.ifEmpty { "未标记" }}"

        val inferred = status.inference
        val mic = status.audio
        val sensor = status.sensor
        val elapsed = if (status.recording) {
            System.currentTimeMillis() - status.startedAt
        } else {
            0L
        }
        statusText.text = if (status.recording) {
            "${inferred?.state ?: "正在启动"} · ${formatElapsed(elapsed)} · ${status.rowCount} 条"
        } else if (status.rowCount > 0) {
            "已停止 · 共 ${status.rowCount} 条"
        } else {
            "尚未开始"
        }
        statusText.setTextColor(
            when (inferred?.state) {
                "运行" -> Color.rgb(255, 159, 69)
                "停站" -> Color.rgb(168, 214, 108)
                "停站但玩家活动" -> Color.rgb(255, 214, 102)
                else -> Color.rgb(91, 201, 232)
            }
        )

        valuesText.text = buildString {
            appendLine("列车状态    : ${inferred?.trainState ?: "等待"}")
            appendLine("玩家状态    : ${inferred?.playerState ?: "等待"}")
            appendLine("麦克风状态  : ${mic?.quality ?: "未启动"}")
            appendLine("麦克风 RMS : ${format(mic?.rms, 6)}")
            appendLine("输入设备    : ${mic?.inputDevice ?: "未启动"}")
            appendLine(
                "采用阈值    : 停 ${format(inferred?.effectiveStopThreshold, 6)} / " +
                    "行 ${format(inferred?.effectiveMovingThreshold, 6)}"
            )
            appendLine("阈值模式    : ${inferred?.thresholdMode ?: "固定"}")
            appendLine("Audio重启   : ${mic?.restartCount ?: 0} 次")
            appendLine("Audio状态   : ${mic?.audioRecordState ?: "STOPPED"}")
            appendLine(
                "前台服务    : ${if (status.foregroundServiceActive) "运行中" else "已停止"}"
            )
            appendLine("WakeLock    : ${if (status.wakeLockHeld) "已持有" else "未持有"}")
            appendLine("Activity    : ${if (status.appInForeground) "前台" else "后台"}")
            appendLine("屏幕        : ${if (status.screenOn) "开启" else "关闭"}")
            appendLine("加速度 RMS : ${format(sensor?.accelRms, 4)} m/s²")
            appendLine(
                "陀螺仪 RMS : ${format(sensor?.gyroRms?.times(180.0 / kotlin.math.PI), 2)} °/s"
            )
            append("判定依据    : ${inferred?.reason ?: "等待采集"}")
        }
    }

    private fun requestStart() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isEmpty()) {
            startRecording()
        } else {
            requestPermissions(missing.toTypedArray(), START_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != START_PERMISSION_REQUEST) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "没有麦克风权限，无法开始采集", Toast.LENGTH_LONG).show()
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "通知权限未开启；系统仍会显示前台服务状态，但通知可能不可见",
                Toast.LENGTH_LONG,
            ).show()
        }
        startRecording()
    }

    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "正在启动前台采集服务"
    }

    private fun stopRecording() {
        recordingService?.stopRecording()
        updateUi()
    }

    private fun setMark(mark: String) {
        recordingService?.setMark(mark)
        updateUi()
    }

    private fun exportCsv() {
        val status = recordingService?.getStatus() ?: return
        if (status.rowCount == 0 || status.recording) return
        pendingCsv = recordingService?.buildCsv()
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

    private fun showCapabilities() {
        capabilityText.text = recordingService?.capabilities()?.joinToString("\n") { item ->
            if (item.available) {
                val rate = item.maxRateHz?.let { " · 最高约 %.0f Hz".format(it) }.orEmpty()
                "✓ ${item.label}: ${item.name} · ${item.vendor}$rate"
            } else {
                "✗ ${item.label}: 本机没有该传感器"
            }
        } ?: "等待服务连接"
    }

    @Suppress("DEPRECATION")
    private fun versionLabel(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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

    private fun weighted() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun format(value: Double?, decimals: Int): String =
        value?.let { "%.${decimals}f".format(Locale.US, it) } ?: "不支持"

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
