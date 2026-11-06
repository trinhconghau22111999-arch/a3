package com.desktopmini.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Giữ tiến trình + kênh nhận lệnh remote (Wi-Fi) sống kể cả khi giao diện app đã tắt,
 * để điện thoại vẫn điều khiển được chuột, ▲▼◀▶, OK, lùi... ở màn hình chính hoặc app khác của TV.
 */
class RemoteKeepAliveService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { }
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tv:remote-service").also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) { }
        // Mở kênh nhận lệnh nếu giao diện chưa kịp mở (vd. dịch vụ được hệ thống dựng lại sau khi bị giết)
        ui.postDelayed({ try { RemoteHub.ensureHost(this) } catch (e: Exception) { } }, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "Điều khiển từ xa", NotificationManager.IMPORTANCE_MIN))
        }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        return b.setContentTitle("Điều khiển từ xa đang chạy")
            .setContentText("Điện thoại vẫn điều khiển được TV qua Wi-Fi")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CH = "remote_keepalive"
        private const val NOTIF_ID = 4711

        fun start(ctx: Context) {
            val i = Intent(ctx, RemoteKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }
    }
}
