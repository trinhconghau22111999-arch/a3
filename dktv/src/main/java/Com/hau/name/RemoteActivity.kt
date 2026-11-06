package Com.hau.name

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tay cam dieu khien app TV (thay cho giao dien chup anh/gui tep cu - cai cu van con trong
 * code nhung khong con duoc mo).
 *
 * Moi lenh la 1 tin JSON {"type":"remote","action":...} gui qua DataChannel truyen tep co san
 * (cung ma co dinh 123456, cung ket noi WebRTC/Firebase) toi app TV:
 *   - volume / brightness : level = 0..100 (am luong / do sang man hinh TV)
 *   - quality : q = 360 | 720 (chat luong video YouTube)
 *   - back : nut LUI - lui 1 buoc tren TV (giong nut Back)
 *   - fullscreen : phong to / thu nho video dang phat tren TV
 *   - media : cmd = rew | fwd | playpause | next (tua 10 giay, phat/dung, bai/video ke tiep)
 *   - touchlock : on = true (TV khong nhan cam ung nua) / false
 *   - key    : key = up | down | left | right | ok (5 phim dieu huong tren khung chuot)
 *   - move   : dx, dy (chuot cam ung, di chuyen tuong doi)
 *   - click  : cham 1 lan tren touchpad
 *   - scroll : dy (2 ngon tay truot)
 *   - screen : on = false (tat man hinh TV) / true (bat lai)
 *   - voice  : q = cau noi da nhan dang tren dien thoai (TV mo YouTube tim va mo video)
 */
class RemoteActivity : AppCompatActivity(), CameraStreamService.ConnectionStatusListener {

    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnScreen: View

    private var attachedService: CameraStreamService? = null
    private var connected = false
    private var tvScreenOn = true
    private var lastWarnAt = 0L

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val q = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!q.isNullOrBlank()) send(JSONObject().put("action", "voice").put("q", q.trim()))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int = 0, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        // Back chi dua app xuong nen (dich vu ket noi van chay), khong thoat han.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        setConnected(false)
    }

    // ---------------------------------------------------------------- UI

    /** Khung chu nhat bo goc: nen [fill], vien [strokeDp] mau den. */
    private fun box(fill: Int, radiusDp: Int, strokeDp: Float) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat()
        setStroke((strokeDp * resources.displayMetrics.density).toInt(), Color.BLACK)
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t; textSize = 10.5f; setTextColor(Color.BLACK); letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
    }

    private fun labeled(btn: View, label: String, size: Int = 64, compact: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(btn, LinearLayout.LayoutParams(dp(size), dp(size)))
        addView(caption(label).also { if (compact) { it.textSize = 9f; it.letterSpacing = 0.04f } },
            LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(if (compact) 2 else 6) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        // Thanh he thong sang de hop nen trang
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }

        // Dau trang: ten (chu hoa, gian chu) + trang thai ket noi
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "DK TV"; textSize = 15f; setTextColor(Color.BLACK); letterSpacing = 0.2f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        statusText = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 11.5f; letterSpacing = 0.04f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusDot = View(this)
        head.addView(statusDot, LinearLayout.LayoutParams(dp(11), dp(11)).also { it.rightMargin = dp(8) })
        head.addView(statusText)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(-1, dp(2)).also { it.topMargin = dp(8) })

        // Hang tren: tat man hinh + giong noi
        btnScreen = IconButton(this, IconButton.POWER).also {
            it.setOnClickListener {
                tvScreenOn = !tvScreenOn
                send(JSONObject().put("action", "screen").put("on", tvScreenOn))
                refreshScreenButton()
            }
        }
        val btnMic = IconButton(this, IconButton.MIC).also {
            it.setOnClickListener {
                startVoice()
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnTouch = IconButton(this, IconButton.TOUCH).also {
            it.setOnClickListener {
                tvTouchOff = !tvTouchOff
                send(JSONObject().put("action", "touchlock").put("on", tvTouchOff))
                refreshTouchButton()
            }
        }
        touchBox = labeled(btnTouch, "TẮT CẢM\nỨNG TV")
        val btnFull = IconButton(this, IconButton.FULL).also {
            it.setOnClickListener { send(JSONObject().put("action", "fullscreen")) }
        }
        // 4 nut cung co, cach deu, nhan 2 dong cho can doi
        screenBox = labeled(btnScreen, "TẮT\nMÀN HÌNH")
        for (b in listOf(screenBox, labeled(btnMic, "GIỌNG\nNÓI"), touchBox, labeled(btnFull, "PHÓNG TO\nMÀN HÌNH"))) {
            top.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(top, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        refreshScreenButton()
        refreshTouchButton()
        // TV khong co cam ung: AN nut tat cam ung tren giao dien (code gui lenh van giu nguyen)
        touchBox.visibility = View.GONE

        // Chuot cam ung: khung trang vien den
        val pad = FrameLayout(this).apply { background = box(Color.WHITE, 24, 2.2f) }
        // 5 phim: len / xuong / trai / phai / OK (giua) - nam trong khung chuot, can doi
        fun key(kind: Int, k: String, w: Int, h: Int, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
            val v = DPadButton(this, kind).also { btn ->
                btn.setOnClickListener {
                    if (k == "down" && barHoldFired) { barHoldFired = false; return@setOnClickListener }   // vua giu 2s: khong tinh la bam
                    send(JSONObject().put("action", "key").put("key", k))
                }
                // Giu phim ▼ dung 2 giay: nhay xuong thanh duoi cung (Menu / TV / YouTube / Ai Kara...) cua TV
                if (k == "down") btn.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            barHoldFired = false
                            ui.removeCallbacks(barHold); ui.postDelayed(barHold, BAR_HOLD_MS)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(barHold)
                    }
                    false
                }
            }
            pad.addView(v, FrameLayout.LayoutParams(dp(w), dp(h), gravity).also {
                it.setMargins(dp(l), dp(t), dp(r), dp(b))
            })
        }
        key(DPadButton.UP, "up", 112, 52, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 36)
        key(DPadButton.DOWN, "down", 112, 52, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 36)
        key(DPadButton.LEFT, "left", 52, 112, Gravity.START or Gravity.CENTER_VERTICAL, l = 28)
        key(DPadButton.RIGHT, "right", 52, 112, Gravity.END or Gravity.CENTER_VERTICAL, r = 28)
        key(DPadButton.OK, "ok", 76, 76, Gravity.CENTER)
        attachTouchpad(pad)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(8); it.bottomMargin = dp(8) })

        // Thay cho vong dieu huong cu: 2 thanh truot (am luong, do sang) + 2 nut chat luong video 360p / 720p
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Hang tren cua panel: 2 thanh truot dich sat trai + nut LUI to ben phai, cao bang 2 thanh cong lai
        val btnBack = TextView(this).apply {
            text = "LÙI"; textSize = 16f; gravity = Gravity.CENTER; letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.BLACK); background = box(Color.WHITE, 14, 2.2f)
            setOnClickListener { Haptics.tick(this); send(JSONObject().put("action", "back")) }
        }
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        sliders.addView(sliderRow("ÂM LƯỢNG", "volume", 50), LinearLayout.LayoutParams(-1, dp(30)))
        // Do sang TV co dinh: AN thanh do sang tren giao dien (van giu code, chi doi visibility)
        sliders.addView(sliderRow("ĐỘ SÁNG", "brightness", 70).also { it.visibility = View.GONE },
            LinearLayout.LayoutParams(-1, dp(30)).also { it.topMargin = dp(2) })
        val sliderBack = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sliderBack.addView(sliders, LinearLayout.LayoutParams(0, -2, 1f))
        sliderBack.addView(btnBack, LinearLayout.LayoutParams(dp(84), dp(62)).also { it.leftMargin = dp(8) })
        panel.addView(sliderBack, LinearLayout.LayoutParams(-1, -2))
        // Chat luong video: nhan ben trai + 2 nut cung 1 hang
        val q = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        q.addView(caption("CHẤT LƯỢNG").also { it.gravity = Gravity.START }, LinearLayout.LayoutParams(dp(LABEL_W), -2))
        btnQ360 = qualityButton(360); btnQ720 = qualityButton(720)
        val btnNext = TextView(this).apply {
            text = "BÀI TIẾP"; textSize = 12f; gravity = Gravity.CENTER; letterSpacing = 0.02f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.BLACK); background = box(Color.WHITE, 12, 2.2f)
            setOnClickListener { Haptics.tick(this); send(JSONObject().put("action", "media").put("cmd", "next")) }
        }
        // 3 nut cung hang, rong bang nhau: 360p | 720p | BÀI TIẾP
        q.addView(btnQ360, LinearLayout.LayoutParams(0, dp(38), 1f).also { it.rightMargin = dp(4) })
        q.addView(btnQ720, LinearLayout.LayoutParams(0, dp(38), 1f).also { it.leftMargin = dp(4); it.rightMargin = dp(4) })
        q.addView(btnNext, LinearLayout.LayoutParams(0, dp(38), 1f).also { it.leftMargin = dp(4) })
        panel.addView(q, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
        // Hang duoi cung: 3 nut chu nhat bo goc (khong nhan), rong bang nhau: lui 10s, phat/dung, tien 10s
        val media = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun mediaBtn(kind: Int, cmd: String) = IconButton(this, kind).also { b ->
            b.rect = true
            b.setOnClickListener { send(JSONObject().put("action", "media").put("cmd", cmd)) }
        }
        val mediaBtns = listOf(mediaBtn(IconButton.REW, "rew"), mediaBtn(IconButton.PLAY, "playpause"), mediaBtn(IconButton.FWD, "fwd"))
        for ((i, b) in mediaBtns.withIndex()) {
            media.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).also {
                if (i > 0) it.leftMargin = dp(4); if (i < mediaBtns.size - 1) it.rightMargin = dp(4)
            })
        }
        panel.addView(media, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        refreshQuality()
        return root
    }

    private lateinit var btnQ360: TextView
    private lateinit var btnQ720: TextView
    private var quality = 0

    /** Thanh truot kieu Sony: duong day (cao 12dp) vien den, phan da keo to den, nut tron cam lon; 1 hang: nhan | thanh | so. */
    private fun sliderRow(label: String, action: String, initial: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val value = TextView(this@RemoteActivity).apply {
            text = "$initial"; textSize = 12f; setTextColor(Color.BLACK); gravity = Gravity.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        addView(caption(label).also { it.gravity = Gravity.START }, LinearLayout.LayoutParams(dp(LABEL_W), -2))
        val trackH = dp(12)
        val bg = GradientDrawable().apply {
            setColor(0xFFE6E6E6.toInt()); cornerRadius = trackH / 2f; setStroke(dp(2), Color.BLACK); setSize(-1, trackH)
        }
        val fg = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = trackH / 2f; setSize(-1, trackH) }
        val layers = LayerDrawable(arrayOf<Drawable>(bg, ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress)
        val knob = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0xFFFF6B00.toInt()); setStroke(dp(2), Color.BLACK); setSize(dp(20), dp(20))
        }
        val sb = SeekBar(this@RemoteActivity).apply {
            max = 100; progress = initial
            progressDrawable = layers
            thumb = knob
            splitTrack = false
            setPadding(dp(16), 0, dp(16), 0)
            var last = 0L
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = "$p"
                    if (!fromUser) return
                    val now = SystemClock.uptimeMillis()
                    if (now - last >= 80) {
                        last = now
                        send(JSONObject().put("action", action).put("level", p), warn = false)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    send(JSONObject().put("action", action).put("level", s.progress))
                }
            })
        }
        addView(sb, LinearLayout.LayoutParams(0, -1, 1f))
        addView(value, LinearLayout.LayoutParams(dp(32), -2))
    }

    private fun qualityButton(q: Int) = TextView(this).apply {
        text = "${q}p"; textSize = 13f; gravity = Gravity.CENTER; letterSpacing = 0.06f
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        setOnClickListener {
            Haptics.tick(this)
            quality = q
            refreshQuality()
            send(JSONObject().put("action", "quality").put("q", q))
        }
    }

    /** Nut dang chon: nen den chu trang; nut con lai: nen trang vien den chu den. */
    private fun refreshQuality() {
        for ((b, q) in listOf(btnQ360 to 360, btnQ720 to 720)) {
            val on = quality == q
            b.background = box(if (on) Color.BLACK else Color.WHITE, 12, 2.2f)
            b.setTextColor(if (on) Color.WHITE else Color.BLACK)
        }
    }

    private lateinit var btnTouch: View
    private lateinit var touchBox: LinearLayout
    private lateinit var screenBox: LinearLayout
    private var tvTouchOff = false

    /** Nut khoa cam ung: dang khoa thi dao mau + co gach cheo, nhan doi thanh "BAT CAM UNG TV". */
    private fun refreshTouchButton() {
        (btnTouch as IconButton).inverted = tvTouchOff
        (touchBox.getChildAt(1) as TextView).text = if (tvTouchOff) "BẬT CẢM\nỨNG TV" else "TẮT CẢM\nỨNG TV"
    }

    /** Nut tat man hinh: do khi TV dang sang, xanh khi TV dang tat. */
    private fun refreshScreenButton() {
        // Man hinh TV dang sang: nut do (bam de tat); da tat: nut xanh (bam de bat lai)
        (btnScreen as IconButton).tint = if (tvScreenOn) 0xFFE5322D.toInt() else 0xFF2BB673.toInt()
        (screenBox.getChildAt(1) as TextView).text = if (tvScreenOn) "TẮT\nMÀN HÌNH" else "MỞ\nMÀN HÌNH"
    }

    // ---------------------------------------------------------------- Touchpad

    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downAt = 0L
    private var moved = false
    private var multi = false
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendScroll = 0f
    private var flushScheduled = false
    private var lastMoveT = 0L
    private var scrollActive = false
    private var flingV = 0f
    private var flingLastT = 0L
    private val sampT = LongArray(6)
    private val sampY = FloatArray(6)
    private var sampN = 0

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchpad(pad: View) {
        pad.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; startX = e.x; startY = e.y
                    downAt = e.eventTime; moved = false; multi = false
                    stopFling(); scrollActive = false; sampN = 0; lastMoveT = e.eventTime
                    // Bao TV hien con tro ngay luc cham (khong cho den khi truot)
                    send(JSONObject().put("action", "move").put("dx", 0).put("dy", 0), warn = false)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    stopFling(); sampN = 0
                    lastY = avgY(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val y = avgY(e)
                        pendScroll += (y - lastY) * SCROLL_GAIN
                        lastY = y
                        scrollActive = true
                        addSample(e.eventTime, y)
                        scheduleFlush()
                    } else if (!multi) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        lastX = e.x; lastY = e.y
                        if (!moved && (abs(e.x - startX) > dp(6) || abs(e.y - startY) > dp(6))) moved = true
                        // Gia toc con tro: di cham = chinh xac, vuot nhanh = bay xa (nhu chuot may tinh)
                        val dt = (e.eventTime - lastMoveT).coerceAtLeast(1L)
                        lastMoveT = e.eventTime
                        val v = kotlin.math.hypot(dx, dy) / dt / resources.displayMetrics.density   // dp tren ms
                        val t = ((v - ACC_V0) / (ACC_V1 - ACC_V0)).coerceIn(0f, 1f)
                        val g = MOVE_GAIN_SLOW + (MOVE_GAIN_FAST - MOVE_GAIN_SLOW) * t * t * (3f - 2f * t)
                        pendDx += dx * g
                        pendDy += dy * g
                        scheduleFlush()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (scrollActive && e.pointerCount == 2) startFling(e.eventTime)
                    // Con 1 ngon sau khi nha ngon thu 2: khong gay nhay con tro
                    val idx = if (e.actionIndex == 0) 1 else 0
                    if (idx < e.pointerCount) { lastX = e.getX(idx); lastY = e.getY(idx) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !multi && e.eventTime - downAt < TAP_MS) {
                        Haptics.tick(pad)
                        send(JSONObject().put("action", "click"))
                    }
                }
            }
            true
        }
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private var lastFlushAt = 0L
    private var barHoldFired = false
    private val barHold = Runnable {
        barHoldFired = true
        pendingHaptic()
        send(JSONObject().put("action", "key").put("key", "bar"))
    }
    private fun pendingHaptic() { try { Haptics.tick(window.decorView) } catch (e: Exception) { } }

    /** Gui ngay cu truot dau tien; cac cu tiep theo gop toi da ~60 tin/giay de khong nghen kenh truyen. */
    private fun scheduleFlush() {
        if (flushScheduled) return
        val wait = 16L - (SystemClock.uptimeMillis() - lastFlushAt)
        if (wait <= 0L) { flushNow(); return }
        flushScheduled = true
        ui.postDelayed({ flushScheduled = false; flushNow() }, wait)
    }

    private fun addSample(t: Long, y: Float) {
        if (sampN == sampT.size) {
            for (i in 1 until sampN) { sampT[i - 1] = sampT[i]; sampY[i - 1] = sampY[i] }
            sampN--
        }
        sampT[sampN] = t; sampY[sampN] = y; sampN++
    }

    /** Nha 2 ngon dang luot: cuon tiep voi van toc cu va cham dan (quan tinh nhu touchpad laptop). */
    private fun startFling(upT: Long) {
        scrollActive = false
        if (sampN < 2 || upT - sampT[sampN - 1] > 80) { sampN = 0; return }       // dung ngon truoc khi nha: khong troi
        var i0 = sampN - 1
        while (i0 > 0 && sampT[sampN - 1] - sampT[i0 - 1] <= 90) i0--
        val dt = (sampT[sampN - 1] - sampT[i0]).coerceAtLeast(1L)
        val v = (sampY[sampN - 1] - sampY[i0]) / dt * SCROLL_GAIN               // px tren ms
        sampN = 0
        if (kotlin.math.abs(v) < FLING_MIN_V) return
        flingV = v.coerceIn(-FLING_MAX_V, FLING_MAX_V)
        flingLastT = SystemClock.uptimeMillis()
        ui.removeCallbacks(flingStep); ui.post(flingStep)
    }

    private fun stopFling() { flingV = 0f; ui.removeCallbacks(flingStep) }

    private val flingStep = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = (now - flingLastT).coerceIn(1L, 50L)
            flingLastT = now
            pendScroll += flingV * dt
            flingV *= Math.pow(FLING_DECAY.toDouble(), dt / 16.0).toFloat()       // ma sat: giam deu theo thoi gian
            scheduleFlush()
            if (kotlin.math.abs(flingV) >= FLING_STOP_V) ui.postDelayed(this, 16) else flingV = 0f
        }
    }

    private fun flushNow() {
        lastFlushAt = SystemClock.uptimeMillis()
        val mx = pendDx.roundToInt(); val my = pendDy.roundToInt()
        val sc = pendScroll.roundToInt()
        if (mx != 0 || my != 0) {
            pendDx -= mx; pendDy -= my
            send(JSONObject().put("action", "move").put("dx", mx).put("dy", my), warn = false)
        }
        if (sc != 0) {
            pendScroll -= sc
            send(JSONObject().put("action", "scroll").put("dy", sc), warn = false)
        }
    }

    // ---------------------------------------------------------------- Mic

    private fun startVoice() {
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên video, tên kênh TV, hoặc “mở TV” / “mở YouTube”")
        }
        try { voiceLauncher.launch(it) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Điện thoại chưa có ứng dụng nhận dạng giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- Gui lenh

    private fun send(cmd: JSONObject, warn: Boolean = true) {
        val json = cmd.put("type", "remote").toString()
        sender.execute {
            val n = CameraStreamService.instance?.sendRemoteCommand(json) ?: 0
            if (n == 0 && warn) {
                val now = System.currentTimeMillis()
                if (now - lastWarnAt > 3000) {
                    lastWarnAt = now
                    ui.post { Toast.makeText(this, "Chưa kết nối với TV", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Trang thai ket noi

    override fun onConnectionStatusChanged(connectedCount: Int, maxViewers: Int) {
        setConnected(connectedCount > 0)
    }

    private fun setConnected(on: Boolean) {
        connected = on
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF2BB673.toInt() else 0xFFE5322D.toInt())
            setStroke(dp(1), Color.BLACK)
        }
        statusText.text = if (on) "ĐÃ KẾT NỐI"
        else "CHƯA KẾT NỐI"
    }

    /** Service co the chua kip khoi dong luc mo man hinh: thu gan lai moi giay cho toi khi co. */
    private val attachLoop = object : Runnable {
        override fun run() {
            val svc = CameraStreamService.instance
            if (svc !== attachedService) {
                attachedService?.removeConnectionStatusListener(this@RemoteActivity)
                attachedService = svc
                svc?.addConnectionStatusListener(this@RemoteActivity)
                if (svc == null) setConnected(false)
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(attachLoop)
    }

    override fun onPause() {
        ui.removeCallbacks(attachLoop)
        attachedService?.removeConnectionStatusListener(this)
        attachedService = null
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        sender.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val LABEL_W = 70
        private const val MOVE_GAIN_SLOW = 1.1f      // di cham: chinh xac
        private const val MOVE_GAIN_FAST = 3.6f      // vuot nhanh: bay xa
        private const val ACC_V0 = 0.15f             // dp/ms bat dau tang toc
        private const val ACC_V1 = 1.6f              // dp/ms dat toc do toi da
        private const val FLING_MIN_V = 0.25f        // px/ms: cham hon thi khong troi
        private const val FLING_MAX_V = 6f
        private const val FLING_STOP_V = 0.03f
        private const val FLING_DECAY = 0.94f        // moi 16ms
        private const val SCROLL_GAIN = 1.0f
        private const val TAP_MS = 250L
        private const val BAR_HOLD_MS = 2000L
    }
}
