package com.screencast.app;

import android.app.Activity;

import java.lang.ref.WeakReference;

/** Giữ tham chiếu tới màn hình Tube for me đang hiển thị - đây là nguồn DUY NHẤT được chiếu lên TV. */
public final class ShareBridge {
    private static volatile WeakReference<Activity> ref = new WeakReference<>(null);

    private ShareBridge() {}

    public static void setActivity(Activity a) {
        ref = new WeakReference<>(a);
    }

    public static void clearActivity(Activity a) {
        if (ref.get() == a) ref = new WeakReference<>(null);
    }

    public static Activity get() {
        return ref.get();
    }
}
