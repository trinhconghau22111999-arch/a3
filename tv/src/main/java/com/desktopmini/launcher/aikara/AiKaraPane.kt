package com.desktopmini.launcher.aikara

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * App con "Ái Kara" (rút gọn từ app TV trong repo truyen: com.screencast.tv.MainActivity).
 * Chỉ còn đồng bộ YouTube với điện thoại: nghe beacon UDP "SYNC1|ip|cổng" rồi nối TCP bằng SyncClient.
 * Đã bỏ phần nhận hình chiếu (trang http://ip:cổng) và mDNS.
 * Chỉ nghe mạng khi cửa sổ này đang mở (onResume) và dừng khi rời (onPause).
 */
class AiKaraPane(private val act: Activity) {
    private companion object {
        const val BEACON_PORT = 8091
        const val LOST_AFTER_MS = 15000L
        const val SYNC_URL = "https://m.youtube.com/"
    }

    private val ui = Handler(Looper.getMainLooper())
    val view = FrameLayout(act)
    val web = WebView(act)
    private val status = TextView(act)

    private var mcLock: WifiManager.MulticastLock? = null
    @Volatile private var udpRun = false
    private var udpThread: Thread? = null
    private var started = false

    private var sync: SyncClient? = null
    private var syncHost: String? = null
    private var syncPort = 0
    private var syncSeen = 0L

    init {
        view.setBackgroundColor(Color.BLACK)
        web.setBackgroundColor(Color.BLACK)
        web.visibility = View.INVISIBLE
        val ws = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                if (r != null && r.isForMainFrame && sync == null) ui.post { goSearching() }
            }
        }
        view.addView(web, FrameLayout.LayoutParams(-1, -1))
        status.setTextColor(Color.WHITE)
        status.textSize = 26f
        status.gravity = Gravity.CENTER
        status.setPadding(60, 0, 60, 0)
        view.addView(status, FrameLayout.LayoutParams(-1, -1))
        goSearching()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            // Mất tín hiệu tìm (UDP) không đủ để ngắt: chỉ ngắt khi kết nối TCP cũng đã rớt quá lâu.
            // Nếu TCP còn sống thì giữ nguyên trang YouTube, không tải lại từ đầu.
            val s = sync
            if (s != null && !s.isConnected() && System.currentTimeMillis() - syncSeen > LOST_AFTER_MS) stopSync()
            ui.postDelayed(this, 3000)
        }
    }

    // ---------------- Trạng thái ----------------

    private fun goSearching() {
        web.visibility = View.INVISIBLE
        web.loadUrl("about:blank")
        status.visibility = View.VISIBLE
        status.text = "Đang tìm điện thoại...\n\nMở app trên điện thoại và bật đồng bộ YouTube\n(điện thoại và TV dùng chung Wi-Fi)" + webViewWarning()
    }

    // TV Android 8 hay có WebView cũ; WebRTC cần WebView tương đối mới
    private fun webViewWarning(): String {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val pi = WebView.getCurrentWebViewPackage()
                val vn = pi?.versionName
                if (vn != null) {
                    val major = vn.split(".")[0].toInt()
                    if (major < 70) return "\n\n⚠ Android System WebView trên TV đang cũ ($vn).\nNếu không lên hình, hãy cập nhật WebView."
                }
            }
        } catch (e: Exception) { }
        return ""
    }

    private fun onSyncFound(host: String, port: Int) {
        syncSeen = System.currentTimeMillis()
        if (sync != null && host == syncHost && port == syncPort) return
        sync?.stop()
        syncHost = host; syncPort = port
        status.visibility = View.GONE
        web.visibility = View.VISIBLE
        val cur = web.url
        if (cur == null || !cur.startsWith("https://m.youtube.com")) web.loadUrl(SYNC_URL)
        sync = SyncClient(ui, web, host, port).also { it.start() }
    }

    private fun stopSync() {
        sync?.stop(); sync = null; syncHost = null
        goSearching()
    }

    // ---------------- Tìm điện thoại ----------------

    private fun startUdp() {
        try {
            val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                mcLock = wm.createMulticastLock("aikara").also { it.setReferenceCounted(false); it.acquire() }
            }
        } catch (e: Exception) { }
        udpRun = true
        udpThread = Thread {
            while (udpRun) {
                var s: DatagramSocket? = null
                try {
                    s = DatagramSocket(null)
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(BEACON_PORT))
                    s.soTimeout = 2000
                    val buf = ByteArray(256)
                    while (udpRun) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (t: java.net.SocketTimeoutException) { continue }
                        val parts = String(p.data, 0, p.length, Charsets.UTF_8).trim().split("|")
                        if (parts.size >= 3 && parts[0] == "SYNC1") {
                            val h = parts[1]; val pt = parts[2].toIntOrNull() ?: continue
                            ui.post { onSyncFound(h, pt) }
                            continue
                        }
                    }
                } catch (e: Exception) {
                    try { Thread.sleep(2000) } catch (ie: InterruptedException) { return@Thread }
                } finally {
                    try { s?.close() } catch (e: Exception) { }
                }
            }
        }.also { it.isDaemon = true; it.name = "aikara-udp"; it.start() }
    }

    // ---------------- Vòng đời (do MainActivity gọi) ----------------

    fun onResume() {
        web.onResume()
        if (started) return
        started = true
        startUdp()
        ui.postDelayed(watchdog, 3000)
    }

    fun onPause() {
        web.onPause()
        if (!started) return
        started = false
        udpRun = false
        ui.removeCallbacks(watchdog)
        try { mcLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
    }

    /** Bấm lại nút Ái Kara / nút ←: ngắt kết nối hiện tại và tìm điện thoại lại từ đầu. */
    fun home() {
        sync?.stop(); sync = null; syncHost = null
        goSearching()
    }

    fun destroy() {
        onPause()
        sync?.stop(); sync = null
        try { web.destroy() } catch (e: Exception) { }
    }
}
