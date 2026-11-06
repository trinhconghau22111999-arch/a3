package com.desktopmini.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Tệp nhận và tệp ghi hình cũ hơn 1 giờ sẽ bị xoá; kiểm tra mỗi 10 phút
private const val MAX_FILE_AGE_MS = 60L * 60 * 1000
private const val PURGE_EVERY_MS = 10L * 60 * 1000

/**
 * Truyền tệp chạy NGẦM (tách từ app Duyệt web cũ): một WebView 1x1 ẩn nạp trang truyentep.html,
 * tự kết nối với mã cố định [CODE] và nhận/gửi tệp qua Firebase + WebRTC như trước.
 * Tệp nhận về nằm ở Tải xuống/TruyenTep.
 */
class TransferHost(context: Context) {
    // Gắn với Application (không còn gắn với Activity) nên vẫn sống khi giao diện bị tắt; dịch vụ nền giữ tiến trình.
    private val act: Context = context.applicationContext
    var onTextEdit: (Int, String) -> Unit = { _, _ -> }
    var onKeepAwake: (Boolean) -> Unit = { }
    var onRemote: (String) -> Unit = { }
    var pickFile: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean =
        { cb, _ -> cb?.onReceiveValue(null); true }

    private val ctx: Context get() = act
    private val contentResolver get() = act.contentResolver
    private fun getExternalFilesDir(t: String?) = act.getExternalFilesDir(t)
    private val filesDir get() = act.filesDir
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun runOnUiThread(r: () -> Unit) { mainHandler.post { r() } }

    var web: WebView? = null
        private set

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(act))
            .build()
    }

    private val recordings = HashMap<String, FileOutputStream>()
    private var recSeq = 0

    // ---- Dọn tệp cũ: xoá tệp nhận và ghi hình cũ hơn MAX_FILE_AGE_MS ----
    private val purgeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val purgeTask = object : Runnable {
        override fun run() {
            purgeOldFiles()
            purgeHandler.postDelayed(this, PURGE_EVERY_MS)
        }
    }

    private fun purgeOldFiles() {
        val cutoff = System.currentTimeMillis() - MAX_FILE_AGE_MS
        for (dir in listOf(receivedDir(), camDir())) {
            // Tệp đang ghi có thời gian sửa đổi mới nên không bị xoá giữa chừng
            dir.listFiles()?.forEach { f ->
                try { if (f.isFile && f.lastModified() < cutoff) f.delete() } catch (e: Exception) { }
            }
        }
    }

    /** Gắn WebView ẩn (1x1, trong suốt) vào [parent] và bắt đầu kết nối. */
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun start(parent: FrameLayout?) {
        if (web != null) { if (parent != null) attach(parent); return }
        purgeHandler.post(purgeTask)
        val w = WebView(act)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.alpha = 0f
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(u)
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                val u = try { Uri.parse(url ?: return null) } catch (e: Exception) { return null }
                return assetLoader.shouldInterceptRequest(u)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = pickFile(filePathCallback, fileChooserParams)
        }
        w.addJavascriptInterface(CamBridge(), "AndroidCam")
        w.loadUrl("https://appassets.androidplatform.net/assets/truyentep.html")
        web = w
        if (parent != null) parent.addView(w, FrameLayout.LayoutParams(1, 1))
        else { w.onResume(); w.resumeTimers() }
    }

    /** Giao diện mở: gắn WebView ẩn vào khung của giao diện. */
    fun attach(parent: FrameLayout) {
        val w = web ?: return
        if (w.parent === parent) return
        (w.parent as? ViewGroup)?.removeView(w)
        parent.addView(w, FrameLayout.LayoutParams(1, 1))
    }

    /** Giao diện đóng: gỡ WebView khỏi khung nhưng KHÔNG huỷ, để kênh nhận lệnh vẫn chạy ngầm. */
    fun detach() {
        val w = web ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        w.onResume(); w.resumeTimers()
    }

    fun destroy() {
        try { web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } } catch (e: Exception) { }
        web = null
    }

    private fun camDir(): File {
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val d = File(base, "TruyenTep")
        d.mkdirs()
        return d
    }

    inner class CamBridge {
        @JavascriptInterface
        fun folder(): String = camDir().absolutePath

        @JavascriptInterface
        fun startFile(name: String): String {
            return try {
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                synchronized(recordings) {
                    val id = "rec" + (++recSeq)
                    recordings[id] = FileOutputStream(File(camDir(), safe))
                    id
                }
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun appendChunk(id: String, b64: String): Boolean {
            return try {
                synchronized(recordings) {
                    val o = recordings[id]
                    if (o == null) {
                        false
                    } else {
                        o.write(Base64.decode(b64, Base64.DEFAULT))
                        true
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finishFile(id: String): Boolean {
            return try {
                synchronized(recordings) {
                    val o = recordings.remove(id)
                    if (o == null) {
                        false
                    } else {
                        o.flush()
                        o.close()
                        true
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        // ---- Nhan tep / sua anh tu dien thoai (DataChannel) ----
        @JavascriptInterface
        fun startReceived(name: String): String {
            return try {
                val dir = receivedDir()
                var safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                if (safe.isEmpty() || safe.all { it == '.' }) safe = "tep_" + System.currentTimeMillis()
                if (safe.length > 120) safe = safe.takeLast(120)
                val dot = safe.lastIndexOf('.')
                val stem = if (dot > 0) safe.substring(0, dot) else safe
                val ext = if (dot > 0) safe.substring(dot) else ""
                var f = File(dir, safe)
                var n = 1
                while (f.exists()) {
                    f = File(dir, stem + " (" + n + ")" + ext)
                    n++
                }
                if (!isInReceivedDir(f)) return ""
                synchronized(recordings) {
                    val id = "rec" + (++recSeq)
                    recordings[id] = FileOutputStream(f)
                    JSONObject().put("id", id).put("path", f.absolutePath).toString()
                }
            } catch (e: Exception) {
                ""
            }
        }

        // Huy tep dang nhan do dang: dong luong ghi va xoa tep tam.
        @JavascriptInterface
        fun discardReceived(id: String, path: String) {
            try {
                synchronized(recordings) { recordings.remove(id)?.let { try { it.close() } catch (e: Exception) { } } }
                val f = File(path)
                if (isInReceivedDir(f) && f.isFile) f.delete()
            } catch (e: Exception) {
            }
        }

        // Chep tep da nhan sang Tai xuong/TruyenTep trong luong nen (tep lon khong treo giao dien).
        // Xong goi window.__published(ten, ok) trong trang Truyen tep. Tra ve true neu da bat dau chep.
        @JavascriptInterface
        fun publishDownloadAsync(path: String, mime: String, name: String): Boolean {
            val f = File(path)
            if (!isInReceivedDir(f) || !f.isFile) return false
            val type = if (mime.isNotEmpty()) mime else guessMime(f.name)
            Thread {
                val ok = try { publishToDownloads(f, type) } catch (e: Exception) { false }
                runOnUiThread {
                    web?.evaluateJavascript(
                        "(window.__published&&window.__published(" + JSONObject.quote(name) + "," + ok + "))", null
                    )
                }
            }.start()
            return true
        }

        // Ghi de anh da sua. ext rong = giu nguyen duoi tep; tra ve duong dan moi ("" neu loi).
        @JavascriptInterface
        fun saveEdited(path: String, b64: String, ext: String): String {
            return try {
                val f = File(path)
                if (!isInReceivedDir(f) || !f.isFile) return ""
                val target = if (ext == "jpg" || ext == "png" || ext == "webp")
                    File(f.parentFile, f.nameWithoutExtension + "." + ext) else f
                target.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                if (target.absolutePath != f.absolutePath) f.delete()
                target.absolutePath
            } catch (e: Exception) {
                ""
            }
        }

        // Chep tep da nhan vao thu muc Tai xuong/TruyenTep de mo tu bat ky ung dung nao.
        @JavascriptInterface
        fun publishDownload(path: String, mime: String): Boolean {
            return try {
                val f = File(path)
                if (!isInReceivedDir(f) || !f.isFile) return false
                val type = if (mime.isNotEmpty()) mime else guessMime(f.name)
                publishToDownloads(f, type)
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun toastMsg(msg: String) {
            runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }

        // Co anh moi ve: dua trang Truyen tep len truoc de hien trinh sua anh.
        @JavascriptInterface
        fun showOverlay() { }

        @JavascriptInterface
        fun textEdit(del: Int, ins: String) {
            runOnUiThread { onTextEdit(del, ins) }
        }

        // Lenh dieu khien tu app DK_TV (JSON {"type":"remote",...})
        @JavascriptInterface
        fun remote(json: String) {
            runOnUiThread { onRemote(json) }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread { onKeepAwake(on) }
        }

        @JavascriptInterface
        fun close() { }
    }

    private fun receivedDir(): File {
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val d = File(base, "TruyenTep")
        d.mkdirs()
        return d
    }

    private fun isInReceivedDir(f: File): Boolean {
        return try {
            f.canonicalPath.startsWith(receivedDir().canonicalPath + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun publishToDownloads(src: File, mime: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) publishToDownloadsQ(src, mime) else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TruyenTep")
            dir.mkdirs()
            src.copyTo(File(dir, src.name), overwrite = true)
            true
        }
    }

    @android.annotation.TargetApi(29)
    private fun publishToDownloadsQ(src: File, mime: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TruyenTep")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val out = contentResolver.openOutputStream(uri) ?: return false
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
        return true
    }

    companion object {
        /** Mã 6 số cố định: máy gửi (điện thoại) mở Truyền tệp và dùng đúng mã này. */
        const val CODE = "123456"
    }
}
