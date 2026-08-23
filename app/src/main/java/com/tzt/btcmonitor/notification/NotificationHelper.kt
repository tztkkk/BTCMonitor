package com.tzt.btcmonitor.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.tzt.btcmonitor.MainActivity
import com.tzt.btcmonitor.R
import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.logging.LogManager
import com.tzt.btcmonitor.service.MarketMonitorService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(
    private val context: Context,
    private val logs: LogManager
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alertIds = AtomicInteger(2_000)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun createChannels() {
        val serviceChannel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            "Monitor Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "行情监控前台服务的常驻状态"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Trading Alert",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "价格条件触发的交易提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 180, 300)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
    }

    fun serviceNotification(): Notification {
        val stopIntent = Intent(context, MarketMonitorService::class.java).apply {
            action = MarketMonitorService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("行情监控运行中")
            .setContentText("BTC-USDT 正在监控")
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "停止监控", stopPendingIntent)
            .build()
    }

    fun sendTradingAlert(message: String, currentPrice: Double, test: Boolean = false) {
        val title = if (test) "BTC 测试提醒" else "BTC 监控提醒"
        val body = "$message\n当前价格：${formatPrice(currentPrice)}\n时间：${timeFormatter.format(Instant.now())}"
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        runCatching { manager.notify(alertIds.incrementAndGet(), notification) }
            .onSuccess { logs.log("NotificationSent", if (test) "test" else message) }
            .onFailure {
                logs.log("Exception", "Notification: ${it.message}", LogLevel.ERROR)
                throw it
            }
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatPrice(value: Double): String = "%.2f".format(value)

    companion object {
        const val MONITOR_CHANNEL_ID = "monitor_service"
        const val ALERT_CHANNEL_ID = "trading_alert"
        const val SERVICE_NOTIFICATION_ID = 1_001
    }
}
