package com.ytbrowser.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

/**
 * Điều khiển đồng bộ phía điện thoại (máy chính).
 *
 * Khi lệch quá 0,1 giây, điện thoại tạm dừng và bảo TV tua tới đúng vị trí. Khi TV báo đã sẵn sàng,
 * điện thoại hẹn TV một mốc giờ rất gần (START_LEAD_MS) và cả hai cùng phát tại mốc đó.
 */
class SyncController(private val web: WebView, private val server: SyncServer) {

    companion object {
        private const val TICK_MS = 250L
        private const val START_LEAD_MS = 250L          // khoảng hẹn tối thiểu để lệnh "start" kịp tới TV
        private const val COOLDOWN_AFTER_START_MS = 500L
        private const val DRIFT_LIMIT_SEC = 0.1         // ngưỡng lệch
        private const val SEEK_LANDED_SEC = 0.05        // TV được coi là đã tua tới đúng chỗ khi sai dưới mức này
        private const val SEEK_GIVE_UP_MS = 3000L       // quá lâu mà TV chưa sẵn sàng thì phát chung luôn
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
    private var cooldownUntil = 0L
    private var seeking = false       // điện thoại đang tạm dừng, chờ TV tua xong
    private var seekPos = 0.0
    private var seekSentAt = 0L

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
                seeking = false
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
            holdForAd = false
            holdPlaying = false
            seeking = false
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
                seeking = false
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

        // 3) Đang tua và chờ TV sẵn sàng: điện thoại vẫn tạm dừng
        if (seeking) {
            handleSeeking(now)
            return
        }

        // 4) Đang trong bước bắt đầu phát chung thì bỏ qua để tránh xung đột
        if (now < cooldownUntil) return
        if (!tvReady) return

        // 5) Điện thoại tạm dừng (người dùng bấm) -> TV cũng dừng
        if (p.optBoolean("paused", true)) {
            if (!(tv?.optBoolean("paused", true) ?: true)) server.send(JSONObject().put("cmd", "pause"))
            needResync = true
            return
        }

        // 6) Điện thoại đang phát nhưng TV chưa phát (hoặc vừa đổi video) -> bắt đầu phát chung
        if (needResync || (tv?.optBoolean("paused", true) ?: true)) {
            resync(p)
            return
        }

        // 7) Cả hai đang phát: lệch quá 0,1 giây thì tạm dừng và bảo TV tua tới đúng vị trí
        val phoneT = p.optDouble("t", -1.0)
        if (phoneT < 0) return
        if (!p.optBoolean("ready", true)) return      // điện thoại đang đệm: không lấy vị trí đứng yên
        val tvT = tvPositionNow(now) ?: return
        val err = phoneT - tvT                        // dương: TV đang chậm hơn điện thoại
        if (abs(err) > DRIFT_LIMIT_SEC) beginSeek(phoneT, now)
    }

    // Tạm dừng điện thoại, bảo TV tua tới đúng vị trí và chờ TV báo đã sẵn sàng
    private fun beginSeek(pos: Double, now: Long) {
        seeking = true
        seekPos = pos
        seekSentAt = now
        web.evaluateJavascript(PAUSE_JS, null)
        server.send(JSONObject().put("cmd", "seek").put("pos", pos))
    }

    private fun handleSeeking(now: Long) {
        val s = tv
        val tvTs = s?.optDouble("t", -1.0) ?: -1.0
        val landed = s != null && tvRecvAt > seekSentAt && tvReady &&
            s.optBoolean("paused", false) && abs(tvTs - seekPos) <= SEEK_LANDED_SEC
        if (landed) {
            startTogether(seekPos)
            return
        }
        if (now - seekSentAt > SEEK_GIVE_UP_MS) {
            startTogether(seekPos)   // quá lâu: phát chung luôn, lệch nhỏ còn lại sẽ được tua tiếp
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

    // Lần bắt đầu phát chung (cũng dùng cho lần đầu và sau quảng cáo): hẹn TV một mốc giờ rất gần,
    // cả hai cùng đặt về vị trí pos rồi phát tại mốc đó
    private fun startTogether(pos: Double) {
        val now = System.currentTimeMillis()
        val at = now + START_LEAD_MS
        needResync = false
        seeking = false
        cooldownUntil = at + COOLDOWN_AFTER_START_MS
        server.send(JSONObject().put("cmd", "start").put("pos", pos).put("at", at))
        web.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){v.pause();if(Math.abs(v.currentTime-$pos)>0.02)v.currentTime=$pos;}})()",
            null
        )
        ui.postDelayed({ web.evaluateJavascript(PLAY_JS, null) }, START_LEAD_MS)
    }

    private fun resync(p: JSONObject) {
        startTogether(maxOf(0.0, p.optDouble("t", 0.0)))
    }
}
