package com.desktopmini.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Điều khiển app BÊN NGOÀI (app mở từ Menu) bằng remote điện thoại.
 * Khi app TV không còn ở phía trước, MainActivity chuyển lệnh sang đây:
 *  - move/click: chuột ảo vẽ trên cửa sổ overlay trợ năng, "click" = chạm giả lập bằng dispatchGesture (Android 7+)
 *  - scroll: vuốt tại vị trí con trỏ; back/home: hành động hệ thống
 *  - phím mũi tên: dịch con trỏ từng bước (Android 13+ dùng phím mũi tên thật của hệ thống); ok: chạm tại con trỏ
 * Người dùng phải tự bật dịch vụ trong Cài đặt > Trợ năng.
 */
class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            return try {
                val v = android.provider.Settings.Secure.getString(ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
                v.split(':').any { it.startsWith(ctx.packageName + "/") && it.contains("RemoteAccessibilityService") }
            } catch (e: Exception) { false }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cursor: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var cx = 0f
    private var cy = 0f
    private var placed = false
    private val hideRunnable = Runnable { cursor?.visibility = View.GONE }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeCursor(); instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() { removeCursor(); instance = null; super.onDestroy() }

    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm?.defaultDisplay?.getRealMetrics(dm)
        return dm
    }

    private fun ensureCursor() {
        if (cursor != null || Build.VERSION.SDK_INT < 22) return
        val size = (28 * resources.displayMetrics.density).toInt()
        val v = object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val r = width / 2f
                p.style = Paint.Style.FILL; p.color = 0xCCFFFFFF.toInt(); c.drawCircle(r, r, r - 3f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.BLACK; c.drawCircle(r, r, r - 3f, p)
            }
        }
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm?.addView(v, params); cursor = v; lp = params } catch (e: Exception) { }
    }

    private fun removeCursor() {
        ui.removeCallbacks(hideRunnable)
        try { cursor?.let { wm?.removeView(it) } } catch (e: Exception) { }
        cursor = null; lp = null
    }

    /** MainActivity quay lại phía trước: ẩn con trỏ overlay (dùng con trỏ của app). */
    fun hideCursor() { ui.post { cursor?.visibility = View.GONE } }

    private fun showCursor() {
        val dm = screen()
        if (!placed) { cx = dm.widthPixels / 2f; cy = dm.heightPixels / 2f; placed = true }
        ensureCursor()
        val v = cursor ?: return
        val p = lp ?: return
        p.x = (cx - p.width / 2f).toInt(); p.y = (cy - p.height / 2f).toInt()
        v.visibility = View.VISIBLE
        try { wm?.updateViewLayout(v, p) } catch (e: Exception) { }
        ui.removeCallbacks(hideRunnable); ui.postDelayed(hideRunnable, 6000)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (Build.VERSION.SDK_INT < 24) return
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null)
        } catch (e: Exception) { }
    }

    /** Nhận cùng JSON với RemoteController.handle(); chỉ xử lý các lệnh liên quan app ngoài. */
    fun handle(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        ui.post {
            val dm = screen()
            when (o.optString("action")) {
                "move" -> {
                    if (!placed) { cx = dm.widthPixels / 2f; cy = dm.heightPixels / 2f; placed = true }
                    cx = (cx + o.optInt("dx") * 1.6f).coerceIn(0f, dm.widthPixels - 1f)
                    cy = (cy + o.optInt("dy") * 1.6f).coerceIn(0f, dm.heightPixels - 1f)
                    showCursor()
                }
                "click" -> { showCursor(); gesture(cx, cy, cx, cy, 50) }
                "scroll" -> {
                    scrollAccum += -o.optInt("dy") * 2 * resources.displayMetrics.density * 6f
                    showCursor(); pumpScroll(dm)
                }
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "key" -> key(o.optString("key"), dm)
            }
        }
    }

    // Cuon: gop cac lenh den trong luc cu vuot truoc chua xong, vuot ngan (~90ms) lien tiep cho muot
    private var scrollAccum = 0f
    private var scrollBusy = false

    private fun pumpScroll(dm: DisplayMetrics) {
        if (scrollBusy || Build.VERSION.SDK_INT < 24) return
        val d = scrollAccum.coerceIn(-dm.heightPixels * 0.6f, dm.heightPixels * 0.6f)
        if (kotlin.math.abs(d) < 1f) return
        scrollAccum = 0f
        val y1 = cy.coerceIn(0f, dm.heightPixels - 1f)
        val y2 = (cy - d).coerceIn(0f, dm.heightPixels - 1f)
        val path = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
        scrollBusy = true
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { scrollBusy = false; if (scrollAccum != 0f) pumpScroll(screen()) }
            override fun onCancelled(g: GestureDescription?) { scrollBusy = false }
        }
        try {
            if (!dispatchGesture(GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build(), cb, ui)) scrollBusy = false
        } catch (e: Exception) { scrollBusy = false }
    }

    private fun key(k: String, dm: DisplayMetrics) {
        if (Build.VERSION.SDK_INT >= 33) {
            val g = when (k) { "up" -> 16; "down" -> 17; "left" -> 18; "right" -> 19; "ok" -> 20; else -> -1 }
            if (g > 0 && performGlobalAction(g)) return
        }
        val step = 60 * resources.displayMetrics.density
        when (k) {
            "up" -> cy = (cy - step).coerceAtLeast(0f)
            "down" -> cy = (cy + step).coerceAtMost(dm.heightPixels - 1f)
            "left" -> cx = (cx - step).coerceAtLeast(0f)
            "right" -> cx = (cx + step).coerceAtMost(dm.widthPixels - 1f)
            "ok" -> { showCursor(); gesture(cx, cy, cx, cy, 50); return }
            else -> return
        }
        showCursor()
    }

    fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    /** Khóa màn hình (tắt hình TV) bằng dịch vụ trợ năng, không cần quyền Quản trị thiết bị. Chỉ Android 9 trở lên. */
    fun lockScreen(): Boolean = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
}
