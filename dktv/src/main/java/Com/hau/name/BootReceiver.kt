package Com.hau.name

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Tự khởi động lại CameraStreamService sau khi máy reboot hoặc app được cập nhật.
 * Chỉ khởi động lại nếu phiên camera đang THỰC SỰ hoạt động (KEY_SESSION_ACTIVE = true).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(CameraActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(CameraActivity.KEY_FIXED_CODE, null) ?: CameraActivity.FIXED_CODE
        if (!prefs.getBoolean(CameraActivity.KEY_SESSION_ACTIVE, false)) return
        val serviceIntent = Intent(context, CameraStreamService::class.java).apply {
            putExtra(CameraStreamService.EXTRA_ROOM_CODE, code)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
