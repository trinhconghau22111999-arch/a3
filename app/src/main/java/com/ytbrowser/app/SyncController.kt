package com.ytbrowser.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

/**
 * Điều khiển đồng bộ phía điện thoại (máy chính). Điện thoại quyết định video nào đang mở,
 * khi nào tạm dừng hoặc phát, và khi nào TV phải đồng bộ lại theo điện thoại.
 * Tua trên điện thoại thì TV tua theo. Lệch quá 100 ms (liên tục 3 lần kiểm tra) thì đồng bộ lại.
 */
class SyncController(private val web: WebView, private val server: SyncServer) {

    companion object {
        private const val TICK_MS = 250L
        private const val START_LEAD_MS = 1200L
        private const val COOLDOWN_AFTER_START_MS = 1000L
        private const val DRIFT_LIMIT_SEC = 0.1
        private const val DRIFT_TICKS = 3
        private const val STALE_TV_MS = 1000L
        private val VID_REGEX = Regex("[?&]v=([A-Za-z0-9_-]{6,})")

        private const val STATE_JS = "(function(){var v=document.querySelector('video');" +
            "var p=document.querySelector('.html5-video-player');" +
            "var ad=!!(p&&/ad-showing|ad-interrupting/.test(p.className||''));" +
            "return JSON.stringify({url:location.href,t:v?v.currentTime:-1,paused:v?v.paused:true," +
            "ready:!!(v&&v.readyState>=3),ad:ad});})()"
        private const val PAUSE_JS = "(function(){var v=document.querySelector('video');if(v){v.pause();}})()"
        private const val PLAY_JS = "(function(){var v=document.querySelector('video');if(v){v.play();}})()"
    }

    private val ui = Handler(Looper.getMainLooper())
    private var running = false
    private var currentVid: String? = null
    private var tv: JSONObject? = null
    private var tvRecvAt = 0L
    private var tvReady = false
    private var needResync = false
    private var holdForAd = false
    private var holdPlaying = false
    private var driftTicks = 0
    private var cooldownUntil = 0L

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            ui.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        server.start()
        ui.post(loop)
    }

    fun stop() {
        running = false
        ui.removeCallbacks(loop)
        server.stop()
    }

    fun onTvMessage(m: JSONObject) {
        when (m.optString("cmd")) {
            "state" -> {
                tv = m
                tvRecvAt = System.currentTimeMillis()
                tvReady = m.optBoolean("ready", false) && tvUrlMatches(m.optString("url"))
            }
            "disconnected" -> {
                tv = null
                tvReady = false
                needResync = true
            }
        }
    }

    private fun tvUrlMatches(url: String): Boolean {
        val vid = currentVid ?: return false
        return url.contains("v=$vid")
    }

    private fun tick() {
        web.evaluateJavascript(STATE_JS) { raw -> parseJs(raw)?.let { decide(it) } }
    }

    private fun parseJs(raw: String?): JSONObject? {
        if (raw == null || raw == "null") return null
        return try {
            val s = JSONTokener(raw).nextValue() as? String ?: return null
            JSONObject(s)
        } catch (e: Exception) {
            null
        }
    }

    private fun decide(p: JSONObject) {
        val now = System.currentTimeMillis()

        // 1) Đổi video trên điện thoại -> TV tải video đó
        val vid = VID_REGEX.find(p.optString("url"))?.groupValues?.get(1) ?: return
        if (vid != currentVid) {
            currentVid = vid
            tvReady = false
            needResync = true
            driftTicks = 0
            holdForAd = false
            holdPlaying = false
            server.send(JSONObject().put("cmd", "load").put("vid", vid))
            return
        }

        // 2) Quảng cáo: máy nào vào quảng cáo thì cả hai tạm dừng, chờ đến khi cả hai hết quảng cáo
        val phoneAd = p.optBoolean("ad", false)
        val tvAd = tv?.optBoolean("ad", false) ?: false
        if (phoneAd || tvAd) {
            if (!holdForAd) {
                holdForAd = true
                holdPlaying = !p.optBoolean("paused", true)
                web.evaluateJavascript(PAUSE_JS, null)
                server.send(JSONObject().put("cmd", "pause"))
            }
            return
        }
        if (holdForAd) {
            holdForAd = false
            if (holdPlaying) {
                holdPlaying = false
                resync(p)
                return
            }
        }

        // 3) Đang trong bước bắt đầu phát đồng bộ thì bỏ qua để tránh xung đột
        if (now < cooldownUntil) return
        if (!tvReady) return

        // 4) Điện thoại tạm dừng (người dùng bấm) -> TV cũng dừng
        if (p.optBoolean("paused", true)) {
            if (!(tv?.optBoolean("paused", true) ?: true)) server.send(JSONObject().put("cmd", "pause"))
            needResync = true
            return
        }

        // 5) Điện thoại đang phát nhưng TV chưa phát (hoặc vừa đổi video) -> đồng bộ lại
        if (needResync || (tv?.optBoolean("paused", true) ?: true)) {
            resync(p)
            return
        }

        // 6) Cả hai đang phát: kiểm tra lệch
        val phoneT = p.optDouble("t", -1.0)
        if (phoneT < 0) return
        val tvT = tvPositionNow(now) ?: return
        if (abs(phoneT - tvT) > DRIFT_LIMIT_SEC) {
            driftTicks++
            if (driftTicks >= DRIFT_TICKS) {
                driftTicks = 0
                resync(p)
            }
        } else {
            driftTicks = 0
        }
    }

    // Vị trí hiện tại của TV, quy về đồng hồ điện thoại
    private fun tvPositionNow(now: Long): Double? {
        val s = tv ?: return null
        if (now - tvRecvAt > STALE_TV_MS) return null
        val t = s.optDouble("t", -1.0)
        if (t < 0) return null
        if (s.optBoolean("paused", true)) return t
        val tvTsOnPhoneClock = s.optLong("ts") + s.optLong("off")
        val age = (now - tvTsOnPhoneClock).coerceIn(0L, 1000L)
        return t + age / 1000.0
    }

    // Đưa cả hai về cùng vị trí của điện thoại, rồi cùng phát tại một mốc giờ chung
    private fun resync(p: JSONObject) {
        val pos = maxOf(0.0, p.optDouble("t", 0.0))
        val now = System.currentTimeMillis()
        val at = now + START_LEAD_MS
        needResync = false
        driftTicks = 0
        cooldownUntil = at + COOLDOWN_AFTER_START_MS
        server.send(JSONObject().put("cmd", "start").put("pos", pos).put("at", at))
        web.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){v.pause();v.currentTime=$pos;}})()",
            null
        )
        ui.postDelayed({ web.evaluateJavascript(PLAY_JS, null) }, START_LEAD_MS)
    }
}
