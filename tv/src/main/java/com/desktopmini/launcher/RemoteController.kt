package com.desktopmini.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Nhan lenh tu app DK_TV (dien thoai) qua kenh truyen tep: {"type":"remote","action":...}
 *   key (up/down/left/right/ok), move (dx,dy), click, scroll (dy), screen (on), voice (q),
 *   volume / brightness (level 0..100), quality (q = 360 | 720), touchlock (on), fullscreen, media (cmd = rew | fwd | playpause | next).
 * Con tro chuot la 1 cham tron ve de len tren cung [root]; nhap = tiep xuc gia lap gui vao [root].
 */
class RemoteController(
    private val act: Activity,
    private val root: FrameLayout,
    private val activeWeb: () -> WebView?,
    private val onVoice: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())

    /** true = TV khong nhan cam ung truc tiep nua (chi dieu khien bang remote tren dien thoai). Khong luu: mo lai app la tu bat lai. */
    @Volatile var touchLocked = false

    /** Dat boi MainActivity: bam nut phong to tren remote (dang toan man hinh thi thu nho lai). */
    var onFullscreen: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    /** Dat boi MainActivity: phim ▲▼◀▶ OK di qua o chon cua trang dang mo (YouTube: tung video; TV: dai/kenh). true = da xu ly. */
    var onKey: ((String) -> Boolean)? = null

    /** Dat boi MainActivity: vuot 2 ngon cuon danh sach native (chon dai / kenh TV). true = da cuon. */
    var onScroll: ((Int) -> Boolean)? = null

    /** Dat boi MainActivity: "tiep theo" - TV: kenh ke tiep trong dai; tra false thi dung lenh cua trang (YouTube). */
    var onNext: (() -> Boolean)? = null

    /** Nut media duoi cung remote: rew / fwd (10 giay), playpause (1 nut bat/tat), next (video/kenh ke tiep). */
    private fun media(cmd: String) {
        val wv = activeWeb() ?: return
        val js = when (cmd) {
            "rew" -> "var v=document.querySelector('video');if(v&&isFinite(v.duration))v.currentTime=Math.max(0,v.currentTime-10);"
            "fwd" -> "var v=document.querySelector('video');if(v&&isFinite(v.duration))v.currentTime=Math.min(v.duration,v.currentTime+10);"
            "playpause" -> "var v=document.querySelector('video');if(v){if(v.paused){var r=v.play();if(r&&r.catch)r.catch(function(){});}else v.pause();}"
            "next" -> {
                if (onNext?.invoke() == true) return
                "var p=document.getElementById('movie_player');if(p&&p.nextVideo){p.nextVideo();}else{var b=document.querySelector('.ytp-next-button,button[aria-label*=\"Next\"],button[aria-label*=\"Tiếp\"]');if(b)b.click();}"
            }
            else -> return
        }
        wv.evaluateJavascript("(function(){try{$js}catch(e){}})()", null)
    }
    private var cx = 0f
    private var cy = 0f
    private var placed = false
    private val size = (28 * act.resources.displayMetrics.density).toInt()

    private val cursor = object : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.style = Paint.Style.FILL; p.color = 0xCCFFFFFF.toInt()
            c.drawCircle(r, r, r - 3f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.BLACK
            c.drawCircle(r, r, r - 3f, p)
        }
    }.apply { visibility = View.GONE }

    private val hideRunnable = Runnable { cursor.visibility = View.GONE }

    init { root.addView(cursor, FrameLayout.LayoutParams(size, size)) }

    /** Ẩn con trỏ ngay (khi chuyển sang dùng phím). */
    fun hideCursor() { ui.removeCallbacks(hideRunnable); cursor.visibility = View.GONE }

    private fun showCursor() {
        val w = root.width.toFloat().coerceAtLeast(1f)
        val h = root.height.toFloat().coerceAtLeast(1f)
        if (!placed) { cx = w / 2f; cy = h / 2f; placed = true }
        cursor.visibility = View.VISIBLE
        cursor.bringToFront()
        cursor.translationX = cx - size / 2f
        cursor.translationY = cy - size / 2f
        ui.removeCallbacks(hideRunnable)
        ui.postDelayed(hideRunnable, 6000)
    }

    fun handle(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        when (o.optString("action")) {
            "move" -> {
                val w = root.width.toFloat(); val h = root.height.toFloat()
                if (w <= 0f || h <= 0f) return
                if (!placed) { cx = w / 2f; cy = h / 2f; placed = true }
                cx = (cx + o.optInt("dx") * GAIN).coerceIn(0f, w - 1f)
                cy = (cy + o.optInt("dy") * GAIN).coerceIn(0f, h - 1f)
                showCursor()
            }
            "click" -> { showCursor(); tap() }
            "scroll" -> {
                val dy = o.optInt("dy")
                if (onScroll?.invoke(-dy * 2) != true) queueScroll(-dy * 2)
            }
            "key" -> key(o.optString("key"))
            "screen" -> if (o.optBoolean("on", true)) wake() else lock()
            "voice" -> { val q = o.optString("q"); if (q.isNotBlank()) onVoice(q) }
            "volume" -> setVolume(o.optInt("level", -1))
            "brightness" -> setBrightness(o.optInt("level", -1))
            "quality" -> setQuality(o.optInt("q"))
            "touchlock" -> touchLocked = o.optBoolean("on", false)
            "fullscreen" -> onFullscreen?.invoke()
            "back" -> onBack?.invoke()
            "media" -> media(o.optString("cmd"))
        }
    }

    /**
     * Phong to video dang phat: chuyen sang toan man hinh. Fullscreen API cua WebView doi 1 cu cham that tren trang,
     * nen cham gia lap vao giua video (kich hoat tuong tac) roi moi yeu cau fullscreen tren khung player.
     */
    fun fullscreenVideo() {
        val wv = activeWeb() ?: return
        val js = "(function(){var v=document.querySelector('video');if(!v)return '';var r=v.getBoundingClientRect();" +
            "if(r.width<=0||r.height<=0)return '';return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,d:window.devicePixelRatio||1});})()"
        wv.evaluateJavascript(js) { raw ->
            val s = try { org.json.JSONArray("[" + raw + "]").optString(0) } catch (e: Exception) { "" }
            if (s.isBlank()) { Toast.makeText(act, "Chưa có video để phóng to", Toast.LENGTH_SHORT).show(); return@evaluateJavascript }
            try {
                val o = JSONObject(s)
                val a = IntArray(2); val b = IntArray(2)
                wv.getLocationInWindow(a); root.getLocationInWindow(b)
                val d = o.optDouble("d", 1.0).toFloat()
                tap(a[0] - b[0] + o.optDouble("x").toFloat() * d, a[1] - b[1] + o.optDouble("y").toFloat() * d)
            } catch (e: Exception) { }
            ui.postDelayed({
                wv.evaluateJavascript("(function(){try{var v=document.querySelector('video');var p=document.getElementById('movie_player')||v;" +
                    "if(!document.fullscreenElement&&p&&p.requestFullscreen)p.requestFullscreen();}catch(e){}})()", null)
            }, 250)
        }
    }

    /**
     * Cuon dung vung dang tro chuot: tim phan tu duoi con tro, di len cha cho toi khi gap khung cuon duoc
     * (vd. danh sach goi y ben phai) va cuon khung do; khong co thi cuon ca trang (video chinh).
     */
    // Gop cac lenh cuon: chi 1 lenh JS chay cung luc, lenh den trong luc do duoc cong don roi chay 1 lan -> khong xep hang, khong giat.
    private var scrollAccum = 0
    private var scrollBusy = false
    private val scrollUnlock = Runnable { scrollBusy = false; pumpScroll() }

    private fun queueScroll(d: Int) {
        scrollAccum += d
        pumpScroll()
    }

    private fun pumpScroll() {
        if (scrollBusy || scrollAccum == 0) return
        val d = scrollAccum; scrollAccum = 0
        scrollBusy = true
        ui.removeCallbacks(scrollUnlock)
        ui.postDelayed(scrollUnlock, 120)          // du phong: JS khong tra loi thi van mo khoa
        scrollAtCursor(d) {
            ui.removeCallbacks(scrollUnlock)
            scrollBusy = false
            if (scrollAccum != 0) pumpScroll()
        }
    }

    private fun scrollAtCursor(dy: Int, done: () -> Unit = {}) {
        val wv = activeWeb() ?: run { done(); return }
        val a = IntArray(2); val b = IntArray(2)
        wv.getLocationInWindow(a); root.getLocationInWindow(b)
        val fx = ((cx - (a[0] - b[0])) / wv.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val fy = ((cy - (a[1] - b[1])) / wv.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        // Ham cuon duoc cai 1 lan vao trang va nho khung dang cuon (khi con tro dung yen): moi lenh chi con 1 phep gan scrollTop, nhe cho TV yeu
        val call = "window.__wbScroll($fx,$fy,$dy)"
        wv.evaluateJavascript("typeof window.__wbScroll==='function'?" + call + ":0") { r ->
            if (r == "0") wv.evaluateJavascript(SCROLL_DEF + ";" + call) { done() } else done()
        }
    }

    /** Am luong TV (luong media) theo muc 0..100. */
    private fun setVolume(level: Int) {
        if (level !in 0..100) return
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max / 100f).roundToInt(), 0) } catch (e: Exception) { }
    }

    /** Do sang man hinh app theo muc 0..100 (khong can cap quyen he thong). */
    private fun setBrightness(level: Int) {
        if (level !in 0..100) return
        ui.post {
            val lp = act.window.attributes
            lp.screenBrightness = (level / 100f).coerceIn(0.02f, 1f)
            act.window.attributes = lp
        }
    }

    /** Chat luong video YouTube 360p / 720p: luu vao localStorage cua YouTube + ep player dang phat, thu lai vai lan vi player doi chat luong khi nap video. */
    private fun setQuality(q: Int) {
        val name = when (q) { 360 -> "medium"; 480 -> "large"; 720 -> "hd720"; else -> return }
        val js = "(function(){var q=$q,n='$name';try{var t=Date.now();localStorage.setItem('yt-player-quality',JSON.stringify({data:JSON.stringify({quality:q,previousQuality:q}),expiration:t+2592000000,creation:t}));}catch(e){}" +
            "try{var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');if(p){if(p.setPlaybackQualityRange)p.setPlaybackQualityRange(n,n);if(p.setPlaybackQuality)p.setPlaybackQuality(n);}}catch(e){}})()"
        for (d in longArrayOf(0, 1500, 4000)) ui.postDelayed({ activeWeb()?.evaluateJavascript(js, null) }, d)
    }

    private fun tap(x: Float = cx, y: Float = cy) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(down)
        ui.postDelayed({ root.dispatchTouchEvent(up); up.recycle() }, 60)
        down.recycle()
    }

    private fun key(k: String) {
        if (k == "bar") { onKey?.invoke(k); return }     // giu ▼ tren remote: nhay xuong thanh duoi cung
        val code = when (k) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> return
        }
        if (onKey?.invoke(k) == true) return
        activeWeb()?.requestFocus()
        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Tắt màn hình TV bằng dịch vụ trợ năng (Android 9+), không cần quyền Quản trị thiết bị.
     *  Không được thì giảm độ sáng xuống thấp nhất. */
    private fun lock() {
        if (RemoteAccessibilityService.instance?.lockScreen() == true) return
        setBrightness(0)
        Toast.makeText(act, "TV này không tắt được hình (cần Android 9 trở lên). Đã giảm độ sáng tối thiểu.", Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "tv:remote-wake")
        wl.acquire(3000)
    }

    companion object {
        private const val GAIN = 1.6f
        private const val SCROLL_DEF = "(function(){if(window.__wbScroll)return 1;var el=null,lx=-9,ly=-9;" +
            "function can(e){var o=getComputedStyle(e).overflowY;return (o==='auto'||o==='scroll'||o==='overlay')&&e.scrollHeight>e.clientHeight+1;}" +
            "function app(e,d){var t=e.scrollTop;e.scrollTop=t+d;return e.scrollTop!==t;}" +
            "function raw(fx,fy,d){try{" +
            "var x=Math.min(innerWidth-1,fx*innerWidth),y=Math.min(innerHeight-1,fy*innerHeight);" +
            "if(Math.abs(fx-lx)>0.02||Math.abs(fy-ly)>0.02){el=null;lx=fx;ly=fy;}" +
            "if(el&&el.isConnected){var doc=(el===document.scrollingElement||el===document.documentElement);" +
            "if(doc||(function(){var r=el.getBoundingClientRect();return x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom;})()){if(app(el,d))return 1;}}" +
            "var e=document.elementFromPoint(x,y);" +
            "while(e&&e!==document.body&&e!==document.documentElement){if(can(e)&&app(e,d)){el=e;return 1;}e=e.parentElement;}" +
            "var s=document.scrollingElement||document.documentElement;el=s;app(s,d);}catch(err){window.scrollBy(0,d);}return 1;}" +
            "window.__wbScroll=function(fx,fy,d){raw(fx,fy,d);try{if(window.__wbFollow)window.__wbFollow();}catch(e){}return 1;};return 1;})()"
    }
}
