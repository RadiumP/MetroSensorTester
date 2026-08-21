package com.metrobooming.sensortester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class RecordingNotifications(
    private val context: Context,
    private val openActivity: Class<*>,
) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "传感器后台采集",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持地铁传感器和麦克风采集运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(content: String): Notification {
        val openIntent = Intent(context, openActivity)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Metro Sensor Tester 正在采集")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun update(trainStateLabel: String, playerStateLabel: String) {
        manager.notify(
            NOTIFICATION_ID,
            build("列车：$trainStateLabel · 玩家：$playerStateLabel"),
        )
    }

    companion object {
        const val CHANNEL_ID = "metro_sensor_recording"
        const val NOTIFICATION_ID = 1001
    }
}
