package com.desktopmini.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.desktopmini.launcher.aikara.AiKaraPane
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

private const val REQ_FILE = 3001
private const val REQ_STORAGE = 3002
private const val REQ_VOICE = 3003
private const val REQ_VOICE_PERM = 3004
private const val REQ_ROLE = 3005

class MainActivity : Activity(), FullscreenHost {
    private lateinit var content: FrameLayout
    private lateinit var barView: LinearLayout
    private lateinit var floatBack: TextView
    private var barH = 0
    private lateinit var fullLayer: FrameLayout
    private lateinit var hidden: FrameLayout
    private lateinit var btnMenu: TextView
    private lateinit var btnTv: TextView
    private lateinit var btnYt: TextView
    private lateinit var btnAi: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var menuLayer: FrameLayout
    private lateinit var remote: RemoteController
    private var connWake: PowerManager.WakeLock? = null

    private var mode = 0                  // 0 = trang trống, 1 = TV, 2 = YouTube, 3 = Ái Kara
    private var tv: TvPane? = null
    private var yt: YouTubePane? = null
    private var aikara: AiKaraPane? = null
    private val barItems = ArrayList<TextView>()
    private var barFocus = 2                 // ô chọn trên thanh dưới: 0 Menu,1 TV,2 YouTube,3 Ái Kara,4 Cài đặt,5 Home,6 ←; -1 = đang điều khiển trang. Mở app: nằm sẵn ở YouTube
    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val tick = object : Runnable {
        override fun run() { refreshStatus(); ui.postDelayed(this, 15000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        // Giu CPU/mang song khi man hinh bi tat tu xa de remote van ket noi va bat lai duoc
        try {
            connWake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tv:connection").also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) { }
        buildUi()
        hideSystemBars()
        setMode(2)                      // mở app lên là vào thẳng YouTube

        refreshStatus()
        ui.postDelayed(tick, 15000)

        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)

        // Kênh nhận lệnh remote/truyền tệp sống theo tiến trình (không bị tắt theo giao diện); dịch vụ nền giữ nó khi tắt app
        RemoteHub.setActivity(this)
        RemoteKeepAliveService.start(this)
        ui.postDelayed({
            if (isFinishing) return@postDelayed
            val h = RemoteHub.ensureHost(applicationContext)
            h.onTextEdit = { del, ins -> applyTextEdit(del, ins) }
            h.pickFile = { cb, params -> openFileChooser(cb, params) }
            h.attach(hidden)
        }, 2500)
        ui.postDelayed({ nextAfterAdmin() }, 600)
    }

    // Không xin quyền Quản trị thiết bị. Chuyển sang bước hỏi dịch vụ trợ năng.
    private var adminChainDone = false
    private fun nextAfterAdmin() {
        if (adminChainDone) return
        adminChainDone = true
        ui.postDelayed({ maybeAskAccessibility() }, 300)
    }

    // ---------------- Đặt làm giao diện (màn hình chính) mặc định ----------------

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun maybeAskDefaultHome() {
        if (isFinishing || isDefaultHome()) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (sp.getBoolean("noAskHome", false)) return
        AlertDialog.Builder(this)
            .setTitle("Đặt TV làm giao diện mặc định?")
            .setMessage("Khi bấm nút Home, máy sẽ vào thẳng giao diện TV.")
            .setPositiveButton("Đặt mặc định") { _, _ -> requestDefaultHome() }
            .setNegativeButton("Để sau", null)
            .setNeutralButton("Không hỏi lại") { _, _ -> sp.edit().putBoolean("noAskHome", true).apply() }
            .show()
    }

    private fun requestDefaultHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                    return
                }
            }
            // Android cũ: mở lại màn hình chính để hệ thống hỏi chọn ứng dụng, chọn TV rồi bấm "Luôn luôn"
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
            catch (e2: Exception) { Toast.makeText(this, "Hãy vào Cài đặt > Ứng dụng > Ứng dụng mặc định > Màn hình chính", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- Giao diện ----------------

    // Thanh dưới cùng màu trắng: nút và chữ tối; nút đang chọn nền màu, chữ trắng
    private val BAR_BG = 0xFFFFFFFF.toInt()
    private val BAR_BTN_BG = 0xFFE3E3EC.toInt()
    private val BAR_TEXT = 0xFF1A1A1A.toInt()

    private fun activeBtn(b: TextView, on: Boolean, onColor: Int) {
        val fill = if (on) onColor else BAR_BTN_BG
        val focused = barFocus >= 0 && barItems.indexOf(b) == barFocus
        b.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            if (focused) setStroke(dp(4), 0xFFFF6B00.toInt())     // ô chọn (viền cam)
        }
        b.setTextColor(if (on) Color.WHITE else BAR_TEXT)
    }

    private fun barBtn(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(BAR_TEXT)
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setBackgroundColor(BAR_BTN_BG)
        minWidth = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun small(t: String, sp: Float = 11f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(BAR_TEXT); gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        barH = dp(40)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR_BG)
            setPadding(dp(4), dp(3), dp(6), dp(3))
        }
        btnMenu = barBtn("Menu").apply { setOnClickListener { toggleMenu() } }
        btnTv = barBtn("TV").apply { setOnClickListener { hideMenu(); onModeButton(1) } }
        btnYt = barBtn("YouTube").apply { setOnClickListener { hideMenu(); onModeButton(2) } }
        btnAi = barBtn("Ái Kara").apply { setOnClickListener { hideMenu(); onModeButton(3) } }
        val btnSettings = barBtn("Cài đặt").apply { setOnClickListener { hideMenu(); openTvSettings() } }
        val btnHome = barBtn("Home").apply { setOnClickListener { hideMenu(); goTvHome() } }
        val btnBack = barBtn("←").apply { textSize = 22f; contentDescription = "Thoát"; setOnClickListener { hideMenu(); goBack() } }
        // 7 nút bằng nhau: Menu | TV | YouTube | Ái Kara | Cài đặt | Home | ← (Thoát)
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(btnMenu, btnTv, btnYt, btnAi, btnSettings, btnHome, btnBack).forEachIndexed { i, b ->
            group.addView(b, LinearLayout.LayoutParams(0, -1, 1f).also { if (i > 0) it.leftMargin = dp(4) })
        }
        bar.addView(group, LinearLayout.LayoutParams(0, -1, 1f))
        barItems.clear(); barItems.addAll(listOf(btnMenu, btnTv, btnYt, btnAi, btnSettings, btnHome, btnBack))

        // Góc phải: giờ, ngày, âm lịch (cùng cỡ chữ 13sp, đậm), rồi nút X gỡ app
        val cluster = 13f
        tvTime = small("--:--", cluster, true).apply { setPadding(dp(14), 0, dp(12), 0) }
        bar.addView(tvTime, LinearLayout.LayoutParams(-2, -1))
        tvDate = small("", cluster, true).apply { setPadding(0, 0, dp(12), 0) }
        bar.addView(tvDate, LinearLayout.LayoutParams(-2, -1))
        tvLunar = small("", cluster, true).apply { setTextColor(0xFFB7791F.toInt()) }
        bar.addView(tvLunar, LinearLayout.LayoutParams(-2, -1))
        val btnKill = barBtn("X").apply {
            setBackgroundColor(0xFFB3261E.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { hideMenu(); confirmSelfRemove() }
        }
        bar.addView(btnKill, LinearLayout.LayoutParams(dp(44), -1).also { it.leftMargin = dp(10) })

        barView = bar
        content = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Hình nền trang chủ (hiện khi chưa mở TV/YouTube); các khung TV/YouTube nằm phủ lên trên
        content.addView(android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.wallpaper)
        }, FrameLayout.LayoutParams(-1, -1))
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(bar, LinearLayout.LayoutParams(-1, barH))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        fullLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        val lp = FrameLayout.LayoutParams(-1, -1); lp.bottomMargin = barH
        root.addView(fullLayer, lp)
        menuLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xF0101018.toInt()); isClickable = true }
        val mlp = FrameLayout.LayoutParams(-1, -1); mlp.bottomMargin = barH
        root.addView(menuLayer, mlp)
        floatBack = TextView(this).apply {
            text = "←"; contentDescription = "Thoát"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        root.addView(floatBack, FrameLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8); it.topMargin = dp(8) })
        remote = RemoteController(this, root, { if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else if (mode == 3) aikara?.web else null }, { remoteVoice(it) })
        remote.onNext = { mode == 1 && (tv?.nextChannel() == true) }
        remote.onBack = { goBack() }
        remote.onScroll = { d -> mode == 1 && tv?.scrollList(d) == true }
        remote.onKey = { k -> handleRemoteKey(k) }
        remote.onFullscreen = { if (fullView != null) hideFull() else remote.fullscreenVideo() }
        hidden = FrameLayout(this)
        root.addView(hidden, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        updateButtons()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun updateButtons() {
        activeBtn(btnMenu, menuLayer.visibility == View.VISIBLE, 0xFF3A7BFF.toInt())
        activeBtn(btnTv, mode == 1, 0xFF3A7BFF.toInt())
        activeBtn(btnYt, mode == 2, 0xFFE02B2B.toInt())
        activeBtn(btnAi, mode == 3, 0xFF8E44AD.toInt())
        for (i in 4 until barItems.size) activeBtn(barItems[i], false, 0)
    }

    // ---------------- Phím ▲▼◀▶ OK từ remote: thanh dưới cùng / Menu ứng dụng / trang đang mở ----------------

    /** Thanh dưới có ô chọn: ◀▶ đi qua Menu...← (dừng trước đồng hồ), OK bấm nút, ▲ lên trang/menu. Menu ứng dụng: ▲▼◀▶ chọn, OK mở. */
    private fun handleRemoteKey(k: String): Boolean {
        if (k == "bar") {                               // giữ ▼ trên remote: nhảy xuống thanh dưới
            if (barFocus < 0) barFocus = when { menuLayer.visibility == View.VISIBLE -> 0; mode in 1..3 -> mode; else -> 0 }
            updateButtons(); return true
        }
        if (barFocus >= 0) {
            when (k) {
                "left" -> if (barFocus > 0) barFocus--
                "right" -> if (barFocus < barItems.lastIndex) barFocus++
                "up" -> barFocus = -1                   // rời thanh dưới: lên menu ứng dụng / trang đang mở
                "down" -> { }
                "ok" -> { barItems.getOrNull(barFocus)?.performClick(); return true }
                else -> return false
            }
            updateButtons(); return true
        }
        if (menuLayer.visibility == View.VISIBLE) return menuKey(k)
        return when (mode) { 1 -> tv?.onKey(k) == true; 2 -> yt?.onKey(k) == true; else -> false }
    }

    private fun menuKey(k: String): Boolean {
        val grid = menuLayer.getChildAt(0) as? android.widget.GridView ?: return true
        val code = when (k) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> -1; else -> return false
        }
        if (grid.count == 0) return true
        if (!grid.hasFocus()) { grid.isFocusableInTouchMode = true; grid.requestFocus() }
        if (grid.selectedItemPosition < 0) { grid.setSelection(0); return true }
        if (code == -1) {
            val pos = grid.selectedItemPosition
            grid.performItemClick(grid.getChildAt(pos - grid.firstVisiblePosition), pos, grid.getItemIdAtPosition(pos))
            return true
        }
        val before = grid.selectedItemPosition
        grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        if (k == "down" && grid.selectedItemPosition == before) { barFocus = 0; updateButtons() }   // hết hàng cuối: xuống thanh dưới
        return true
    }

    // Bàn phím / remote vật lý nối với TV: giữ ▼ đúng 2 giây = nhảy xuống thanh dưới (YouTube, TV, Ái Kara đều dùng được)
    private var downHoldPending = false
    private var downHoldFired = false
    private val downHold = Runnable { downHoldPending = false; downHoldFired = true; handleRemoteKey("bar") }

    private fun keyName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ok"
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && barFocus < 0) {
            // Đang ở trang/menu: ▼ nhấn nhanh = đi xuống như thường (xử lý lúc nhả phím); giữ 2 giây = xuống thanh dưới
            if (event?.repeatCount == 0) { downHoldFired = false; downHoldPending = true; ui.removeCallbacks(downHold); ui.postDelayed(downHold, 2000) }
            return true
        }
        val k = keyName(keyCode)
        if (k != null) {
            val inBarOrMenu = barFocus >= 0 || menuLayer.visibility == View.VISIBLE
            val isEnter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            // Trang đang mở (YouTube / chọn đài-kênh TV): ▲ ◀ ▶ OK đi qua đúng bộ xử lý như remote điện thoại.
            // Enter của bàn phím chữ chỉ làm OK ở thanh dưới/menu để không phá việc gõ chữ vào ô tìm kiếm.
            if (inBarOrMenu || !isEnter) {
                if (k == "ok" && (event?.repeatCount ?: 0) > 0) return true      // giữ OK không bấm lặp
                if (handleRemoteKey(k)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (downHoldPending || downHoldFired)) {
            val fired = downHoldFired
            downHoldFired = false
            if (downHoldPending) {
                ui.removeCallbacks(downHold); downHoldPending = false
                if (!fired && !handleRemoteKey("down")) super.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------- Chế độ TV / YouTube ----------------

    private fun onModeButton(m: Int) {
        if (mode == m) {                      // bấm lại nút đang mở: về trang chủ của chế độ đó
            when (m) { 1 -> tv?.home(); 2 -> yt?.home(); else -> aikara?.home() }
            return
        }
        hideFull()
        setMode(m)
        // Chuyen qua lai TV <-> YouTube: khong giu dai/video cu, luon ve menu chon dai (TV) hoac trang chu (YouTube)
        when (m) { 1 -> tv?.home(); 2 -> yt?.home(); else -> aikara?.home() }
    }

    override fun setBarVisible(v: Boolean) {
        runOnUiThread {
            barView.visibility = if (v) View.VISIBLE else View.GONE
            floatBack.visibility = if (v) View.GONE else View.VISIBLE
            (fullLayer.layoutParams as FrameLayout.LayoutParams).bottomMargin = if (v) barH else 0
            fullLayer.requestLayout()
        }
    }

    private fun setMode(m: Int) {
        hideMenu()
        setBarVisible(true)
        tv?.let { content.removeView(it.view); it.onPause() }
        yt?.let { content.removeView(it.view); it.onPause() }
        aikara?.let { content.removeView(it.view); it.onPause() }
        mode = m
        when (m) {
            1 -> {
                val p = tv ?: TvPane(this, this).also { tv = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            2 -> {
                val p = yt ?: YouTubePane(this, this).also { yt = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            3 -> {
                val p = aikara ?: AiKaraPane(this).also { aikara = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
        }
        updateButtons()
    }

    // Thoát: TV -> về thẳng menu chọn đài. YouTube -> lùi từng bước (thoát toàn màn hình trước), cuối cùng về trang chủ YouTube.
    private fun goBack() {
        when (mode) {
            1 -> { if (fullView != null) hideFull(); tv?.home() }
            2 -> { if (fullView != null) { hideFull(); return }; yt?.back() }
            3 -> aikara?.home()
        }
    }

    // Khoa cam ung: nuot moi cham tren man hinh TV. Chuot ao cua remote gui thang vao root nen van hoat dong.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (::remote.isInitialized && remote.touchLocked) return true
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (menuLayer.visibility == View.VISIBLE) hideMenu() else goBack() }     // luôn ở lại màn hình chính, không thoát app

    // ---------------- Điều khiển app bên ngoài (qua dịch vụ Trợ năng) ----------------

    private var resumed = false

    /** App TV ở phía trước: RemoteController xử lý. App khác ở phía trước: chuyển cho dịch vụ Trợ năng + phím media hệ thống. */
    fun routeRemote(json: String) {
        if (barFocus >= 0 && json.contains("\"click\"")) { barFocus = -1; updateButtons() }   // dùng chuột bấm: bỏ ô chọn trên thanh dưới
        val svc = RemoteAccessibilityService.instance
        if (backBurst(json)) return
        if (resumed || isFinishing) { remote.handle(json); return }
        val action = try { JSONObject(json).optString("action") } catch (e: Exception) { "" }
        when (action) {
            "move", "click", "scroll", "key", "back" -> if (svc != null) svc.handle(json) else remote.handle(json)
            "media" -> systemMedia(try { JSONObject(json).optString("cmd") } catch (e: Exception) { "" })
            else -> remote.handle(json)       // volume, screen (tắt/bật màn hình)... tác động toàn hệ thống nên giữ nguyên
        }
    }

    // Lệnh "lùi" nhấn 3 lần liên tục (mỗi lần cách nhau không quá ~1 giây) -> về trang chủ của chính app này,
    // kể cả khi đang ở trong app bên ngoài. Hai lần lùi đầu vẫn được xử lý bình thường.
    private val backTimes = ArrayList<Long>()

    private fun backBurst(json: String): Boolean {
        val isBack = try { JSONObject(json).optString("action") == "back" } catch (e: Exception) { false }
        if (!isBack) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (backTimes.isNotEmpty() && now - backTimes.last() > 1000) backTimes.clear()
        backTimes.add(now)
        if (backTimes.size < 3) return false
        backTimes.clear()
        goAppHome()
        return true
    }

    /** Trang chủ của app: hình nền + thanh menu (không mở TV/YouTube); đưa app lên phía trước nếu đang ở app khác. */
    private fun goAppHome() {
        hideMenu(); hideFull(); setMode(0)
        if (resumed) return
        val toFront = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { startActivity(toFront) } catch (e: Exception) { }
        // Nếu hệ thống chặn mở từ nền: dùng Trợ năng bấm Home (app này là launcher mặc định thì sẽ về đúng app)
        ui.postDelayed({ if (!resumed && !isFinishing) RemoteAccessibilityService.instance?.goHome() }, 800)
    }

    private fun systemMedia(cmd: String) {
        val code = when (cmd) {
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "fwd" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return
        }
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) { }
    }

    private var accPendingHome = false

    private fun maybeAskAccessibility() {
        if (isFinishing) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (RemoteAccessibilityService.isEnabled(this) || sp.getBoolean("noAskAcc", false)) { maybeAskDefaultHome(); return }
        AlertDialog.Builder(this)
            .setTitle("Bật điều khiển ứng dụng bên ngoài?")
            .setMessage("Để điện thoại điều khiển (chuột ảo, chạm, vuốt, Back) cả những ứng dụng mở từ Menu. " +
                "Bạn cần vào Cài đặt > Trợ năng, chọn \"TV_Youtube\" rồi bật lên.")
            .setPositiveButton("Mở cài đặt") { _, _ ->
                accPendingHome = true
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (e: Exception) { accPendingHome = false; maybeAskDefaultHome() }
            }
            .setNegativeButton("Để sau") { _, _ -> maybeAskDefaultHome() }
            .setNeutralButton("Không hỏi lại") { _, _ -> sp.edit().putBoolean("noAskAcc", true).apply(); maybeAskDefaultHome() }
            .setOnCancelListener { maybeAskDefaultHome() }
            .show()
    }

    // ---------------- Menu (toàn bộ app), Cài đặt, Home, tự gỡ ----------------

    private fun hideMenu() { if (::menuLayer.isInitialized) menuLayer.visibility = View.GONE; updateButtons() }

    private fun toggleMenu() {
        if (menuLayer.visibility == View.VISIBLE) { hideMenu(); return }
        hideFull()
        showAppMenu()
    }

    private class AppEntry(val label: String, val icon: android.graphics.drawable.Drawable?, val pkg: String, val cls: String)
    private var appCache: List<AppEntry>? = null
    private var menuLoading = false

    // Gói hệ thống/nền không đưa vào menu. Khớp theo tiền tố tên gói.
    private val HIDDEN_PKG_PREFIXES = listOf(
        "com.android.", "com.google.android.gms", "com.google.android.gsf", "com.google.android.setupwizard",
        "com.google.android.partnersetup", "com.google.android.feedback", "com.google.android.tungsten",
        "com.google.android.backuptransport", "com.google.android.onetimeinitializer",
        "com.google.android.apps.restore", "com.google.android.ext", "com.google.android.networkstack",
        "com.google.android.inputmethod", "com.google.android.marvin", "com.google.android.tts",
        "com.google.android.as", "com.google.android.printservice", "com.google.android.tvrecommendations",
        "com.google.android.leanbacklauncher", "com.google.android.overlay",
        "com.qualcomm.", "com.mediatek.", "com.amlogic.", "com.rockchip.", "com.realtek.", "com.droidlogic.", "com.mstar."
    )
    // Ứng dụng hệ thống vẫn giữ dù khớp tiền tố ở trên (người dùng thường cần)
    private val KEPT_SYSTEM_PKGS = setOf(
        "com.android.chrome", "com.android.vending", "com.google.android.youtube", "com.google.android.youtube.tv"
    )

    private fun shouldShowOnTv(pkg: String, ai: android.content.pm.ApplicationInfo, isTvApp: Boolean): Boolean {
        if (pkg in KEPT_SYSTEM_PKGS) return true
        if (HIDDEN_PKG_PREFIXES.any { pkg.startsWith(it) }) return false
        val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        // App hệ thống gốc (không phải bản cập nhật): chỉ giữ nếu được thiết kế cho TV, ví dụ Netflix, YouTube TV.
        // App hệ thống chỉ có biểu tượng điện thoại (lịch, máy tính, tệp...) bị bỏ.
        if (isSystem && !isUpdatedSystem && !isTvApp) return false
        return true
    }

    /** App có icon mở được trên TV, đã lọc app hệ thống/nền, trừ chính app này. Chạy ở luồng nền (TV Android 8 yếu). */
    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        fun query(cat: String): List<android.content.pm.ResolveInfo> =
            try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (e: Exception) { emptyList() }
        val tvResolves = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tvPkgs = tvResolves.map { it.activityInfo.packageName }.toHashSet()
        val all = tvResolves + query(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in all) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg in seen) continue
            if (!shouldShowOnTv(pkg, ri.activityInfo.applicationInfo, pkg in tvPkgs)) continue
            seen.add(pkg)
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
            val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
            out.add(AppEntry(label, icon, pkg, ri.activityInfo.name))
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun showAppMenu() {
        menuLayer.visibility = View.VISIBLE
        updateButtons()
        val cached = appCache
        if (cached != null) renderApps(cached) else {
            menuLayer.removeAllViews()
            menuLayer.addView(TextView(this).apply {
                text = "Đang tải ứng dụng..."; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        if (menuLoading) return
        menuLoading = true
        Thread {
            val list = try { loadApps() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                menuLoading = false
                if (isFinishing) return@runOnUiThread
                val changed = appCache?.map { it.pkg } != list.map { it.pkg }
                appCache = list
                if (menuLayer.visibility == View.VISIBLE && (cached == null || changed)) renderApps(list)
            }
        }.start()
    }

    private fun renderApps(apps: List<AppEntry>) {
        menuLayer.removeAllViews()
        if (apps.isEmpty()) {
            menuLayer.addView(TextView(this).apply {
                text = "Không tìm thấy ứng dụng nào"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val grid = android.widget.GridView(this).apply {
            numColumns = android.widget.GridView.AUTO_FIT
            columnWidth = dp(120)
            stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(8); horizontalSpacing = dp(8)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0x553A7BFF)
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(i: Int) = apps[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                    val e = apps[i]
                    val cell = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        addView(android.widget.ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(56), dp(56)))
                        addView(TextView(this@MainActivity).apply {
                            setTextColor(Color.WHITE); textSize = 12f
                            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
                    }
                    (cell.getChildAt(0) as android.widget.ImageView).setImageDrawable(e.icon)
                    (cell.getChildAt(1) as TextView).text = e.label
                    return cell
                }
            }
            setOnItemClickListener { _, _, pos, _ ->
                val e = apps[pos]
                val intent = Intent(Intent.ACTION_MAIN).setClassName(e.pkg, e.cls)
                    .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { hideMenu(); startActivity(intent) }
                catch (ex: Exception) { Toast.makeText(this@MainActivity, "Không mở được ứng dụng này", Toast.LENGTH_SHORT).show() }
            }
        }
        menuLayer.addView(grid, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openTvSettings() {
        val tries = listOf(
            Intent(android.provider.Settings.ACTION_SETTINGS),
            Intent("android.settings.SETTINGS"))
        for (i in tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { }
        }
        Toast.makeText(this, "Không mở được Cài đặt của TV", Toast.LENGTH_LONG).show()
    }

    /** Về màn hình chính gốc của TV (launcher hệ thống), không phải chính app này. */
    private fun goTvHome() {
        try {
            val q = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .filter { it.activityInfo.packageName != packageName }
            val pick = q.firstOrNull { (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 } ?: q.firstOrNull()
            if (pick != null) {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        } catch (e: Exception) { }
        // Không có launcher nào khác: về trang chủ của chính app (hình nền, không mở TV/YouTube)
        hideFull(); setMode(0)
        Toast.makeText(this, "TV không có màn hình chính nào khác", Toast.LENGTH_SHORT).show()
    }

    private fun confirmSelfRemove() {
        AlertDialog.Builder(this)
            .setTitle("Gỡ ứng dụng này khỏi TV?")
            .setMessage("Sẽ bỏ thiết lập giao diện mặc định (nếu có) rồi gỡ cài đặt ứng dụng.")
            .setPositiveButton("Gỡ") { _, _ -> selfRemove() }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun selfRemove() {
        // 1) Bỏ thiết lập "giao diện mặc định" của app này (nếu có)
        try { packageManager.clearPackagePreferredActivities(packageName) } catch (e: Exception) { }
        // 2) Gỡ app
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS)) }
            catch (e2: Exception) { Toast.makeText(this, "Hãy gỡ thủ công trong Cài đặt > Ứng dụng", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- Video toàn màn hình ----------------

    override fun showFull(v: View, cb: WebChromeClient.CustomViewCallback?) {
        if (fullView != null) { cb?.onCustomViewHidden(); return }
        fullView = v; fullCb = cb
        v.setBackgroundColor(Color.BLACK)
        fullLayer.addView(v, FrameLayout.LayoutParams(-1, -1))
        fullLayer.visibility = View.VISIBLE
    }

    override fun hideFull() {
        val v = fullView ?: return
        fullLayer.removeView(v)
        fullLayer.visibility = View.GONE
        fullView = null
        fullCb?.onCustomViewHidden(); fullCb = null
    }

    // ---------------- Giọng nói (YouTube) ----------------

    override fun launchVoice() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE_PERM); return
        }
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().let { if (it.country.isEmpty()) it.language else it.language + "-" + it.country })
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên YouTube")
        }
        try { startActivityForResult(it, REQ_VOICE) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Máy chưa có ứng dụng nhận dạng giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Truyền tệp (ngầm) ----------------

    private fun openFileChooser(cb: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
        fileCb?.onReceiveValue(null)
        fileCb = cb
        return try { startActivityForResult(params!!.createIntent(), REQ_FILE); true }
        catch (e: Exception) { fileCb?.onReceiveValue(null); fileCb = null; false }
    }

    // Remote giong noi, tuy che do dang chon san tren TV:
    //  - dang o TV (mode 1): noi ten kenh/dai -> mo kenh do
    //  - con lai (YouTube hoac trang trong): noi ten video -> tim tren YouTube va tu mo video dau tien
    // Chuẩn hoá câu nói: bỏ dấu, chữ thường, bỏ dấu câu (để so khớp lệnh "mở TV" / "mở YouTube").
    private fun normVoice(q: String): String =
        java.text.Normalizer.normalize(q.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val VOICE_OPEN = "(?:mo|bat|vao|open|chuyen sang|chuyen qua|di toi|quay ve)"
    private val VOICE_TV = Regex("^$VOICE_OPEN (?:ra |lai )?(?:trang chon kenh |trang chu |man hinh )?(?:tv|ti vi|tivi|truyen hinh|kenh tv)(?: len| di| giup| cho toi)?$")
    private val VOICE_YT = Regex("^$VOICE_OPEN (?:ra |lai )?(?:trang chu )?(?:youtube|you tube|du tup|diu tup|yu tup|iu tup)(?: len| di| giup| cho toi)?$")

    // "mở TV" -> sang TV và hiện trang chọn kênh (đang ở YouTube cũng được); "mở YouTube" -> sang YouTube, vào trang chủ.
    // Trả về true nếu câu nói là lệnh mở và đã xử lý xong.
    private fun voiceOpenCommand(q: String): Boolean {
        val n = normVoice(q)
        when {
            VOICE_TV.matches(n) -> { if (mode != 1) { hideFull(); setMode(1) }; tv?.home() }
            VOICE_YT.matches(n) -> { if (mode != 2) { hideFull(); setMode(2) }; yt?.home() }
            else -> return false
        }
        return true
    }

    // Câu ghép "mở YouTube mở bài hát X" / "mở TV kênh HTV9": chuyển app rồi làm luôn phần sau, chỉ cần nói một lần.
    // Trả về (chế độ 1 = TV, 2 = YouTube) và phần nội dung (giữ dấu); null nếu không phải câu ghép.
    private val VOICE_PREFIX = setOf("mo", "bat", "vao", "open", "chuyen", "sang", "qua", "di", "toi", "quay", "ve", "ra", "lai",
        "trang", "chu", "man", "hinh", "chon", "kenh")
    private val VOICE_YT_KEYS = listOf(listOf("youtube"), listOf("you", "tube"), listOf("du", "tup"), listOf("diu", "tup"),
        listOf("yu", "tup"), listOf("iu", "tup"))
    private val VOICE_TV_KEYS = listOf(listOf("tv"), listOf("tivi"), listOf("ti", "vi"), listOf("truyen", "hinh"))
    private val VOICE_YT_FILL = setOf("va", "roi", "mo", "phat", "nghe", "tim", "kiem", "xem", "bai", "hat", "nhac", "video", "clip",
        "cua", "ten", "cho", "toi", "len", "tren", "di")
    private val VOICE_TV_FILL = setOf("va", "roi", "mo", "bat", "xem", "kenh", "dai", "truyen", "hinh", "tren", "cho", "toi", "len", "di", "nhe")

    private fun voiceSwitchAndQuery(q: String): Pair<Int, String>? {
        val words = ArrayList<String>(); val nw = ArrayList<String>()
        for (w in q.trim().split(Regex("\\s+"))) {
            val k = normVoice(w).replace(" ", "")
            if (k.isNotEmpty()) { words.add(w.trim(',', '.', '!', '?', ';', '"', '\'')); nw.add(k) }
        }
        fun find(keys: List<List<String>>): Pair<Int, Int>? {
            for (i in nw.indices) for (k in keys) {
                if (i + k.size <= nw.size && k.indices.all { nw[i + it] == k[it] }) return i to k.size
            }
            return null
        }
        val yt = find(VOICE_YT_KEYS); val tvk = find(VOICE_TV_KEYS)
        // Lấy từ khóa xuất hiện sớm hơn (đó là app được yêu cầu chuyển tới)
        val (mode2, hit) = when {
            yt != null && (tvk == null || yt.first <= tvk.first) -> 2 to yt
            tvk != null -> 1 to tvk
            else -> return null
        }
        if (!nw.subList(0, hit.first).all { it in VOICE_PREFIX }) return null
        var a = hit.first + hit.second
        val fill = if (mode2 == 2) VOICE_YT_FILL else VOICE_TV_FILL
        while (a < nw.size && nw[a] in fill) a++
        if (a >= nw.size) return null      // chỉ có lệnh mở, không kèm nội dung
        return mode2 to words.subList(a, words.size).joinToString(" ")
    }

    private fun remoteVoice(q: String) {
        // Câu ghép: chuyển app + nói luôn tên bài / kênh trong một lần
        voiceSwitchAndQuery(q)?.let { (m, rest) ->
            hideFull()
            if (m == 1) {
                if (mode != 1) setMode(1)
                tv?.home()
                if (tv?.playByName(rest) != true) Toast.makeText(this, "Không tìm thấy kênh: $rest", Toast.LENGTH_LONG).show()
            } else {
                if (mode != 2) setMode(2)
                yt?.search(rest)
            }
            return
        }
        if (voiceOpenCommand(q)) return
        if (mode == 1) {
            val ok = tv?.playByName(q) ?: false
            if (!ok) Toast.makeText(this, "Không tìm thấy kênh: $q", Toast.LENGTH_LONG).show()
            return
        }
        if (mode != 2) { hideFull(); setMode(2) }
        yt?.search(q)
    }

    // Điện thoại gửi {del, ins}: xoá `del` ký tự trước con trỏ rồi chèn `ins` vào ô nhập đang chọn của TV/YouTube.
    private fun applyTextEdit(delRaw: Int, ins: String) {
        val wv: WebView = (if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else if (mode == 3) aikara?.web else null) ?: return
        val del = delRaw.coerceIn(0, 1000000)
        if (del == 0 && ins.isEmpty()) return
        if (ins == "\n" && del == 0) {
            wv.requestFocus()
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return
        }
        wv.evaluateJavascript("(" + TEXT_EDIT_FN + ")(" + del + "," + JSONObject.quote(ins) + ");", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_FILE -> { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCb = null }
            REQ_VOICE -> if (resultCode == RESULT_OK) {
                val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!q.isNullOrBlank()) { if (mode != 2) setMode(2); yt?.search(q) }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VOICE_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchVoice()
    }

    // ---------------- Thanh trạng thái ----------------

    private fun refreshStatus() {
        val cal = Calendar.getInstance()
        tvTime.text = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val dow = when (cal.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> "CN"; else -> "T" + cal.get(Calendar.DAY_OF_WEEK) }
        val d = cal.get(Calendar.DAY_OF_MONTH); val m = cal.get(Calendar.MONTH) + 1; val y = cal.get(Calendar.YEAR)
        tvDate.text = "$dow $d/$m/$y"
        val l = LunarCalendar.solarToLunar(d, m, y)
        tvLunar.text = "ÂL " + l[0] + "/" + l[1] + (if (l[3] == 1) " nhuận" else "")
    }

    override fun onResume() { super.onResume(); resumed = true; RemoteAccessibilityService.instance?.hideCursor(); if (accPendingHome) { accPendingHome = false; ui.postDelayed({ maybeAskDefaultHome() }, 500) }; hideSystemBars(); refreshStatus(); if (mode == 1) tv?.onResume() else if (mode == 2) yt?.onResume() else if (mode == 3) aikara?.onResume() }
    override fun onPause() { super.onPause(); resumed = false; tv?.onPause(); yt?.onPause(); aikara?.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        RemoteHub.setActivity(null)
        RemoteHub.host?.let { it.onTextEdit = { _, _ -> }; it.pickFile = { cb, _ -> cb?.onReceiveValue(null); true }; it.detach() }
        try { connWake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        tv?.destroy(); yt?.destroy(); aikara?.destroy()
        super.onDestroy()
    }
}
