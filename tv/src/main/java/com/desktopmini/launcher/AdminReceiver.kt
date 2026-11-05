package com.desktopmini.launcher

import android.app.admin.DeviceAdminReceiver

/** Quyền "Quản trị thiết bị" (chỉ xin chính sách force-lock) để remote tắt màn hình TV bằng lockNow(). */
class AdminReceiver : DeviceAdminReceiver()
