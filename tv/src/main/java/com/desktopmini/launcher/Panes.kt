package com.desktopmini.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Cho phép khung video toàn màn hình (nằm dưới thanh menu). */
interface FullscreenHost {
    fun showFull(v: View, cb: WebChromeClient.CustomViewCallback?)
    fun hideFull()
    fun launchVoice()
    fun setBarVisible(v: Boolean)
}

private const val YT_HOME = "https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1"

private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
    "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
    "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
    "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
)

private fun hostIs(h: String, d: String) = h == d || h.endsWith(".$d")
private val AD_SET = AD_HOSTS.toHashSet()
private fun isAdHost(h: String): Boolean {
    var x = h
    while (true) {
        if (AD_SET.contains(x)) return true
        val i = x.indexOf('.')
        if (i < 0) return false
        x = x.substring(i + 1)
    }
}
private fun isYoutubeHost(h: String) = hostIs(h, "youtube.com") || h == "youtu.be" || hostIs(h, "youtube-nocookie.com")

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(act: Activity, web: WebView) {
    val s = web.settings
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.databaseEnabled = true
    s.mediaPlaybackRequiresUserGesture = false
    s.setSupportZoom(false)
    s.setGeolocationEnabled(false)
    s.setSupportMultipleWindows(false)
    s.javaScriptCanOpenWindowsAutomatically = false
    s.builtInZoomControls = false
    s.useWideViewPort = true
    s.loadWithOverviewMode = true
    s.allowFileAccess = false
    s.allowContentAccess = false
    if (android.os.Build.VERSION.SDK_INT >= 21) s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    // UA như Chrome thật (bỏ "; wv" và "Version/4.0") + không gửi X-Requested-With
    s.userAgentString = WebSettings.getDefaultUserAgent(act).replace("; wv)", ")").replace(" Version/4.0", "")
    try {
        if (android.os.Build.VERSION.SDK_INT >= 21 && WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
    } catch (e: Exception) { }
    CookieManager.getInstance().setAcceptCookie(true)
    if (android.os.Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    web.setBackgroundColor(Color.BLACK)
    try {
        if (android.os.Build.VERSION.SDK_INT >= 21 && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(web, "window.__wbAdSkip=true;" + YT_ONLY_AD_JS + HIDE_OPEN_APP_JS,
                setOf("https://*.youtube.com", "https://youtube.com"))
    } catch (e: Exception) { }
}

private fun chromeClient(full: FullscreenHost) = object : WebChromeClient() {
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) return
        full.showFull(view, callback)
    }
    override fun onHideCustomView() { full.hideFull() }
}

/** YouTube cũ (chỉ youtube.com, chặn quảng cáo, tìm bằng giọng nói) nhúng làm một khung dưới thanh menu. */
class YouTubePane(private val act: Activity, private val full: FullscreenHost) {
    val web: WebView = WebView(act)
    val view: View get() = web
    private var lastToastAt = 0L

    inner class VoiceBridge {
        @JavascriptInterface fun startVoiceSearch() { act.runOnUiThread { full.launchVoice() } }
    }

    private fun allowedPage(u: Uri): Boolean {
        val scheme = (u.scheme ?: return false).lowercase()
        if (scheme == "about") return true
        if (scheme != "https" && scheme != "http") return false
        val h = (u.host ?: return false).lowercase()
        if (isYoutubeHost(h)) return !(u.path ?: "").startsWith("/redirect")
        return h.startsWith("accounts.google.") || h.startsWith("consent.google.")
    }

    private fun toastBlocked() {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 1500) return
        lastToastAt = now
        act.runOnUiThread { Toast.makeText(act, "Mục này chỉ mở được YouTube", Toast.LENGTH_SHORT).show() }
    }

    init {
        configureWebView(act, web)
        web.addJavascriptInterface(VoiceBridge(), "AndroidVoice")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val r = request ?: return false
                if (!r.isForMainFrame) return false
                if (allowedPage(r.url)) return false
                toastBlocked()
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return null
                val h = (r.url?.host ?: return null).lowercase()
                if (isAdHost(h)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                if (r.isForMainFrame && !allowedPage(r.url)) {
                    toastBlocked()
                    val html = "<meta name=viewport content='width=device-width,initial-scale=1'>" +
                        "<body style='background:#111;color:#eee;font:16px sans-serif;text-align:center;padding:48px 20px'>" +
                        "Mục này chỉ mở được YouTube.<br><br>Bấm ← để quay lại.</body>"
                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
                return null
            }

            // Android < 7 (API < 24 / < 21) chỉ gọi các bản hàm nhận chuỗi URL
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = try { Uri.parse(url ?: return false) } catch (e: Exception) { return false }
                if (allowedPage(u)) return false
                toastBlocked()
                return true
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                val h = (try { Uri.parse(url ?: return null).host } catch (e: Exception) { null } ?: return null).lowercase()
                return if (isAdHost(h)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            }

            // Ghi lai tung trang da xem (ke ca chuyen trang kieu SPA cua YouTube) de nut lui quay lai dung tung buoc
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url == null || isReload) return
                val u = try { Uri.parse(url) } catch (e: Exception) { return }
                if (u.scheme != "https" && u.scheme != "http") return
                val k = pageKey(u)
                if (trail.isNotEmpty() && trailKeys.last() == k) { trail[trail.size - 1] = url; return }
                trail.add(url); trailKeys.add(k)
                if (trail.size > 40) { trail.removeAt(0); trailKeys.removeAt(0) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val h = try { Uri.parse(url ?: "").host } catch (e: Exception) { null } ?: return
                if (clearHistoryOnFinish && hostIs(h, "youtube.com")) { clearHistoryOnFinish = false; view?.clearHistory() }
                if (hostIs(h, "youtube.com")) {
                    view?.evaluateJavascript("window.__wbAdSkip=true;" + YT_ONLY_AD_JS, null)
                    view?.evaluateJavascript(VOICE_HOOK_JS, null)
                    view?.evaluateJavascript(HIDE_OPEN_APP_JS, null)
                    view?.evaluateJavascript(YT_NOMUTE_JS, null)
                    view?.evaluateJavascript(YT_NAV_JS, null)
                    view?.evaluateJavascript(HIDE_SHORTS_JS, null)
                }
            }
        }
        web.webChromeClient = chromeClient(full)
        web.loadUrl(YT_HOME)
    }

    /** Remote ▲▼ đưa ô chọn qua từng video, OK mở video đang có ô chọn; ◀▶ và trường hợp không có video thì bắn phím thô cho trang. */
    fun onKey(k: String): Boolean {
        val js = when (k) {
            "up" -> "window.__wbVert&&window.__wbVert(-1)"
            "down" -> "window.__wbVert&&window.__wbVert(1)"
            "left" -> "window.__wbNav&&window.__wbNav(-1)"
            "right" -> "window.__wbNav&&window.__wbNav(1)"
            "ok" -> "window.__wbOk&&window.__wbOk()"
            else -> return false
        }
        web.evaluateJavascript(js) { r -> if (r != "true") fallbackKey(k) }
        return true
    }

    /** Enter của bàn phím chữ: đang có ô chọn (video / nút tìm kiếm) thì làm OK; đang gõ chữ thì Enter đi thẳng vào ô gõ (tìm kiếm). */
    fun enterKey() {
        web.evaluateJavascript("window.__wbOk&&window.__wbOk()") { r ->
            if (r != "true") {
                web.requestFocus()
                web.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                web.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    private fun fallbackKey(k: String) {
        val code = when (k) {
            "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            else -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
        }
        web.requestFocus()
        web.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        web.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    // Lich su duoi dang danh sach URL tu theo doi (khong phu thuoc lich su WebView vi YouTube hay thay the muc lich su)
    private val trail = ArrayList<String>()
    private val trailKeys = ArrayList<String>()
    /** Cac URL cung 1 video (them t=, list=...) coi la 1 trang de lui khong bi ket o 1 video. */
    private fun pageKey(u: Uri): String {
        val path = u.path ?: ""
        return if (path == "/watch") "watch:" + (u.getQueryParameter("v") ?: "") else u.buildUpon().fragment(null).build().toString()
    }

    fun search(q: String) { web.loadUrl("https://www.youtube.com/results?search_query=" + Uri.encode(q)) }
    // Ve trang chu va xoa lich su duyet cu (nut ← khong quay lai video truoc do nua)
    private var clearHistoryOnFinish = false
    fun home() {
        clearHistoryOnFinish = true
        trail.clear(); trailKeys.clear()
        web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})", null)
        web.loadUrl(YT_HOME)
    }
    // Lùi từng bước theo lịch sử; hết lịch sử mà chưa ở trang chủ thì về trang chủ YouTube. false = đã ở trang chủ.
    fun back(): Boolean {
        if (trail.size > 1) {
            trail.removeAt(trail.size - 1); trailKeys.removeAt(trailKeys.size - 1)
            web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})", null)
            web.loadUrl(trail.last())
            return true
        }
        val path = try { Uri.parse(web.url ?: "").path ?: "" } catch (e: Exception) { "" }
        if (path.isEmpty() || path == "/") return false
        home(); return true
    }
    fun onPause() { CookieManager.getInstance().flush(); web.onPause() }
    fun onResume() { web.onResume() }
    fun destroy() { try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (e: Exception) { } }
}

/** TV mới: trang chọn đài (Việt Nam, Cà Mau, Cần Thơ...) -> chọn kênh -> xem. Danh sách ở assets/channels.json. */
class TvPane(private val act: Activity, private val full: FullscreenHost) {
    val view = FrameLayout(act)
    private val chooser = FrameLayout(act)
    private var player: WebView? = null
    private var groups: List<Pair<String, List<Pair<String, String>>>> = emptyList()
    private var level = 0           // 0 = chọn đài, 1 = chọn kênh, 2 = đang xem
    private var curGroup = -1

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    // ---- Chọn đài / kênh bằng remote ▲▼◀▶ + OK: ô sáng viền trắng đánh dấu mục đang chọn ----
    private var navItems: List<Button> = emptyList()
    private var navCols = 1
    private var navSel = 0
    private var navScroll: ScrollView? = null

    private fun paintNav() {
        navItems.forEachIndexed { i, b ->
            val sel = i == navSel
            b.background = GradientDrawable().apply {
                setColor(if (sel) 0xFF1565C0.toInt() else 0xFF2B2B3A.toInt())
                cornerRadius = dp(6).toFloat()
                if (sel) setStroke(dp(3), Color.WHITE)
            }
        }
        val b = navItems.getOrNull(navSel) ?: return
        val sv = navScroll ?: return
        sv.post {      // cuộn để mục đang chọn luôn nằm giữa màn hình
            var top = 0; var p: View = b
            while (p !== sv) { top += p.top; p = (p.parent as? View) ?: break }
            sv.smoothScrollTo(0, maxOf(0, top - (sv.height - b.height) / 2))
        }
    }

    private fun setNav(items: List<Button>, cols: Int, sv: ScrollView, sel: Int) {
        navItems = items; navCols = cols; navScroll = sv
        navSel = sel.coerceIn(0, maxOf(0, items.lastIndex))
        paintNav()
    }

    private fun moveNav(d: Int) {
        val last = navItems.lastIndex
        val n = navSel + d
        if (n in 0..last) navSel = n
        else if (d == navCols && navCols > 1 && navSel / navCols < last / navCols) navSel = last   // ▼ ở hàng áp chót -> xuống ô cuối
        paintNav()
    }

    /** Remote vuốt 2 ngón: cuộn danh sách đài / kênh. true = đã cuộn; false (đang xem kênh) = để trang phát tự cuộn. */
    fun scrollList(dy: Int): Boolean {
        if (level == 2) return false
        val sv = navScroll ?: return false
        sv.scrollBy(0, dy)
        return true
    }

    /** Remote ở trang chọn đài / chọn kênh. true = đã xử lý; false (đang xem kênh) = chuyển cho trang phát. */
    fun onKey(k: String): Boolean {
        if (level == 2 || navItems.isEmpty()) return false
        when (k) {
            "left" -> moveNav(-1)
            "right" -> moveNav(1)
            "up" -> moveNav(-navCols)
            "down" -> moveNav(navCols)
            "ok" -> navItems.getOrNull(navSel)?.performClick()
            else -> return false
        }
        return true
    }

    init {
        view.setBackgroundColor(Color.BLACK)
        view.addView(chooser, FrameLayout.LayoutParams(-1, -1))
        try {
            val txt = act.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(txt).getJSONArray("groups")
            val list = ArrayList<Pair<String, List<Pair<String, String>>>>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val chs = g.getJSONArray("channels")
                val cl = ArrayList<Pair<String, String>>()
                for (j in 0 until chs.length()) { val c = chs.getJSONObject(j); cl.add(c.getString("name") to c.getString("url")) }
                list.add(g.getString("name") to cl)
            }
            groups = list
        } catch (e: Exception) { }
        showGroups()
    }

    private fun label(t: String, sizeSp: Float = 18f) = TextView(act).apply {
        text = t; setTextColor(Color.WHITE); textSize = sizeSp; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
    }

    private fun chip(t: String, onClick: () -> Unit) = Button(act).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 16f
        setBackgroundColor(0xFF2B2B3A.toInt()); setOnClickListener { onClick() }
    }

    private fun showGroups(sel: Int = 0) {
        level = 0; curGroup = -1
        stopPlayer()
        chooser.removeAllViews(); chooser.visibility = View.VISIBLE
        val sv = ScrollView(act)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        col.addView(label("Chọn Kênh", 20f).apply { setPadding(0, 0, 0, dp(10)) })
        val grid = GridLayout(act).apply { columnCount = 3 }
        val navList = ArrayList<Button>()
        groups.forEachIndexed { i, g ->
            val b = chip(g.first) { showChannels(i) }
            navList.add(b)
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0; lp.height = dp(64); lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            grid.addView(b, lp)
        }
        col.addView(grid)
        sv.addView(col)
        chooser.addView(sv, FrameLayout.LayoutParams(-1, -1))
        setNav(navList, 3, sv, sel)
    }

    private fun showChannels(gi: Int, sel: Int = 0) {
        level = 1; curGroup = gi
        if (groups[gi].second.size == 1) { play(groups[gi].second[0].second); return }   // đài chỉ có 1 kênh: xem luôn
        stopPlayer()
        chooser.removeAllViews(); chooser.visibility = View.VISIBLE
        val sv = ScrollView(act)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        col.addView(label(groups[gi].first, 20f).apply { setPadding(0, 0, 0, dp(10)) })
        val navList = ArrayList<Button>()
        groups[gi].second.forEach { (name, url) ->
            val b = chip(name) { play(url) }
            navList.add(b)
            val lp = LinearLayout.LayoutParams(-1, dp(56)); lp.setMargins(0, dp(4), 0, dp(4))
            col.addView(b, lp)
        }
        sv.addView(col)
        chooser.addView(sv, FrameLayout.LayoutParams(-1, -1))
        setNav(navList, 1, sv, sel)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun play(url: String) {
        level = 2
        curUrl = url
        navItems = emptyList()
        chooser.visibility = View.GONE
        val w = WebView(act)
        configureWebView(act, w)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val r = request ?: return false
                val sc = (r.url.scheme ?: "").lowercase()
                return sc != "https" && sc != "http"        // không mở app ngoài
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val h = (request?.url?.host ?: return null).lowercase()
                return if (isAdHost(h)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val sc = (try { Uri.parse(url ?: return false).scheme } catch (e: Exception) { null } ?: "").lowercase()
                return sc != "https" && sc != "http"
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                val h = (try { Uri.parse(url ?: return null).host } catch (e: Exception) { null } ?: return null).lowercase()
                return if (isAdHost(h)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            }
            override fun onPageFinished(view: WebView?, u: String?) {
                view?.evaluateJavascript(TV_FULL_JS, null)
                val h = try { Uri.parse(u ?: "").host } catch (e: Exception) { null } ?: return
                if (hostIs(h, "youtube.com")) {
                    view?.evaluateJavascript("window.__wbAdSkip=true;" + YT_ONLY_AD_JS, null)
                    view?.evaluateJavascript(YT_NOMUTE_JS, null)
                }
            }
        }
        w.webChromeClient = chromeClient(full)
        view.addView(w, FrameLayout.LayoutParams(-1, -1))
        player = w
        startCover()
        w.loadUrl(url)
    }

    // ---- Lớp phủ đen đếm ngược 5 4 3 2 1 che lúc kênh đang tải; đếm xong thì bỏ lớp (âm thanh không bị ảnh hưởng) ----
    private var cover: TextView? = null
    private var coverTick: Runnable? = null
    private val coverHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startCover() {
        stopCover()
        val t = TextView(act).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 96f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true      // chặn chạm xuyên xuống khi đang đếm
            text = "5"
        }
        view.addView(t, FrameLayout.LayoutParams(-1, -1))
        cover = t
        var left = 5
        lateinit var next: Runnable
        next = Runnable {
            left--
            if (left >= 1) {
                t.text = left.toString()
                coverHandler.postDelayed(next, 1000)
            } else stopCover()
        }
        coverTick = next
        coverHandler.postDelayed(next, 1000)
    }

    private fun stopCover() {
        coverTick?.let { coverHandler.removeCallbacks(it) }
        coverTick = null
        cover?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cover = null
    }

    private fun stopPlayer() {
        full.setBarVisible(true)
        stopCover()
        val w = player ?: return
        player = null
        try {
            w.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})", null)
            w.onPause()
            (w.parent as? ViewGroup)?.removeView(w)       // gỡ khỏi màn hình ngay để menu hiện tức thì
        } catch (e: Exception) { }
        view.postDelayed({ try { w.loadUrl("about:blank"); w.destroy() } catch (e: Exception) { } }, 300)
    }

    private fun normName(t: String): String {
        val d = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')
        val skip = setOf("mo", "kenh", "dai", "truyen", "hinh", "xem", "cho", "toi", "tv", "len", "bat")
        return d.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in skip }.joinToString("")
    }

    /** Remote giong noi: tim kenh/dai theo ten da noi (vd "HTV9", "Ca Mau", "VTV1") roi phat. true neu tim thay. */
    fun playByName(q: String): Boolean {
        val nq = normName(q)
        if (nq.isEmpty()) return false
        var best = 0; var bestGi = -1; var bestUrl = ""; var bestDiff = Int.MAX_VALUE
        groups.forEachIndexed { gi, g ->
            g.second.forEach { (name, url) ->
                for (cand in listOf(name, g.first)) {
                    val n = normName(cand)
                    if (n.isEmpty()) continue
                    val score = if (n == nq) 3 else if (n.contains(nq) || nq.contains(n)) 2 else 0
                    val diff = kotlin.math.abs(n.length - nq.length)
                    if (score > best || (score == best && score > 0 && diff < bestDiff)) {
                        best = score; bestGi = gi; bestUrl = url; bestDiff = diff
                    }
                }
            }
        }
        if (best == 0) return false
        curGroup = bestGi
        play(bestUrl)
        return true
    }

    val activeWeb: WebView? get() = player

    private var curUrl = ""

    /** Remote: sang kênh kế tiếp trong cùng đài (vòng lại kênh đầu). false nếu chưa xem kênh nào. */
    fun nextChannel(): Boolean {
        if (level != 2 || curGroup < 0) return false
        val list = groups[curGroup].second
        if (list.size < 2) return false
        val i = list.indexOfFirst { it.second == curUrl }
        stopPlayer()
        play(list[(i + 1).mod(list.size)].second)
        return true
    }

    /** Về trang chọn đài (trang ngoài cùng của TV). */
    fun home() { showGroups() }

    fun back(): Boolean {
        return when (level) {
            2 -> {
                if (curGroup >= 0 && groups[curGroup].second.size > 1) showChannels(curGroup) else showGroups()
                true
            }
            1 -> { showGroups(); true }
            else -> false
        }
    }

    fun onPause() { player?.onPause() }
    fun onResume() { player?.onResume() }
    fun destroy() { stopPlayer() }
}
