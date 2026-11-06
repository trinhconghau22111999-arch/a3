package com.desktopmini.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Trung tâm nhận lệnh remote từ điện thoại, sống theo tiến trình (không theo Activity).
 *  - Giao diện app đang mở: chuyển cho MainActivity.routeRemote().
 *  - Giao diện đã tắt (thoát app / vuốt khỏi đa nhiệm): xử lý ở đây bằng dịch vụ Trợ năng
 *    (chuột ảo, cuộn, phím, lùi) + âm lượng + phím media + bật/tắt màn hình.
 * TransferHost (kênh Firebase/WebRTC) được giữ sống bởi RemoteKeepAliveService.
 */
object RemoteHub {
    private val ui = Handler(Looper.getMainLooper())
    var host: TransferHost? = null
        private set
    private var activityRef: WeakReference<MainActivity>? = null
    const val EXTRA_HOME = "goHome"
    /** true khi giao diện app đang ở phía trước (MainActivity.onResume/onPause). */
    @Volatile var appVisible = false
    private var warnedAcc = 0L

    fun setActivity(a: MainActivity?) { activityRef = a?.let { WeakReference(it) } }

    /** Gọi ở luồng chính. Tạo kênh nhận lệnh nếu chưa có. */
    fun ensureHost(ctx: Context): TransferHost {
        host?.let { return it }
        val h = TransferHost(ctx.applicationContext)
        h.onRemote = { json -> route(ctx.applicationContext, json) }
        host = h
        h.start(null)
        return h
    }

    private fun route(ctx: Context, json: String) {
        val a = activityRef?.get()
        if (a != null && !a.isFinishing && !a.isDestroyed) { a.routeRemote(json); return }
        background(ctx, json)
    }

    private val backTimes = ArrayList<Long>()

    private fun background(ctx: Context, json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val action = o.optString("action")
        val svc = RemoteAccessibilityService.instance
        when (action) {
            "back" -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (backTimes.isNotEmpty() && now - backTimes.last() > 1000) backTimes.clear()
                backTimes.add(now)
                if (backTimes.size >= 3) {              // lùi 3 lần liên tục: mở lại app này
                    backTimes.clear()
                    openApp(ctx)
                } else svc?.handle(json)
            }
            "home" -> bringToFront(ctx)
            "move", "click", "scroll", "key" -> if (svc != null) svc.handle(json) else warnNoAccessibility(ctx)
            "media" -> media(ctx, o.optString("cmd"))
            "volume" -> volume(ctx, o.optInt("level", -1))
            "screen" -> if (o.optBoolean("on", true)) wake(ctx) else svc?.lockScreen()
        }
    }

    private fun warnNoAccessibility(ctx: Context) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - warnedAcc < 15000) return
        warnedAcc = now
        ui.post { android.widget.Toast.makeText(ctx, "Chưa bật Trợ năng cho app TV nên không điều khiển được app khác. Vào Cài đặt > Trợ năng để bật.", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun openApp(ctx: Context) = bringToFront(ctx)

    /** Đưa app lên phía trước (về màn hình chính của app). Thử mở trực tiếp (từ dịch vụ Trợ năng trước), không được thì bấm Home hệ thống
     *  (app là giao diện mặc định thì Home sẽ về đúng app này). */
    fun bringToFront(ctx: Context) {
        ui.post {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_HOME, true)
            val svc = RemoteAccessibilityService.instance
            var started = false
            try { if (svc != null) { svc.startActivity(i); started = true } } catch (e: Exception) { }
            if (!started) try { ctx.startActivity(i) } catch (e: Exception) { }
            ui.postDelayed({ if (!appVisible) RemoteAccessibilityService.instance?.goHome() }, 900)
        }
    }

    private fun media(ctx: Context, cmd: String) {
        val code = when (cmd) {
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "fwd" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return
        }
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) { }
    }

    private fun volume(ctx: Context, level: Int) {
        if (level !in 0..100) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max / 100f).roundToInt(), 0) } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun wake(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "tv:remote-wake")
        wl.acquire(3000)
    }
}
