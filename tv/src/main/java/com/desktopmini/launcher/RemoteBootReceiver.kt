package com.desktopmini.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** TV vừa bật/khởi động lại: chạy sẵn dịch vụ nhận lệnh để điện thoại điều khiển được ngay, chưa cần mở app. */
class RemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == "android.intent.action.QUICKBOOT_POWERON" ||
            a == "com.htc.intent.action.QUICKBOOT_POWERON" || a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            RemoteKeepAliveService.start(context.applicationContext)
        }
    }
}
