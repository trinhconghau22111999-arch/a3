package com.screencast.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.View;
import android.view.Window;

import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;

/**
 * Chỉ chụp cửa sổ của Tube for me (qua ShareBridge), KHÔNG chụp toàn màn hình điện thoại.
 * Khi Tube for me không hiển thị (ra màn hình chính / mở app khác) thì không có khung hình mới,
 * TV đứng yên ở hình cuối, không bao giờ thấy app khác.
 */
public class WindowCapturer implements VideoCapturer {
    private final int maxSide;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceTextureHelper helper;
    private CapturerObserver observer;
    private HandlerThread thread;
    private Handler handler;
    private Surface surface;
    private Bitmap bitmap;
    private int curW, curH, fps = 30;
    private volatile boolean running;

    public WindowCapturer(int maxSide) {
        this.maxSide = maxSide;
    }

    @Override
    public void initialize(SurfaceTextureHelper h, Context c, CapturerObserver o) {
        helper = h;
        observer = o;
    }

    @Override
    public void startCapture(int w, int h, int f) {
        fps = Math.max(1, f);
        curW = w;
        curH = h;
        helper.setTextureSize(w, h);
        surface = new Surface(helper.getSurfaceTexture());
        helper.startListening(frame -> observer.onFrameCaptured(frame));
        thread = new HandlerThread("window-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;
        observer.onCapturerStarted(true);
        handler.post(this::tick);
    }

    @Override
    public void stopCapture() throws InterruptedException {
        running = false;
        if (thread != null) {
            thread.quitSafely();
            thread.join();
            thread = null;
        }
        helper.stopListening();
        observer.onCapturerStopped();
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override
    public void changeCaptureFormat(int w, int h, int f) {
        // Kích thước tự theo cửa sổ Tube for me
    }

    @Override
    public void dispose() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    private void next(long t0, long minDelay) {
        if (!running || handler == null) return;
        long interval = 1000L / fps;
        long wait = Math.max(minDelay, interval - (SystemClock.uptimeMillis() - t0));
        handler.postDelayed(this::tick, Math.max(0, wait));
    }

    private void tick() {
        if (!running) return;
        final long t0 = SystemClock.uptimeMillis();
        Activity a = ShareBridge.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            next(t0, 200);
            return;
        }
        final Window win = a.getWindow();
        final View decor = win.peekDecorView();
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0 || !decor.isShown()) {
            next(t0, 200);
            return;
        }
        int sw = decor.getWidth(), sh = decor.getHeight();
        float s = Math.min(1f, maxSide / (float) Math.max(sw, sh));
        final int tw = Math.max(2, ((int) (sw * s)) & ~1);
        final int th = Math.max(2, ((int) (sh * s)) & ~1);
        if (bitmap == null || bitmap.getWidth() != tw || bitmap.getHeight() != th) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PixelCopy.request(win, bitmap, result -> {
                    if (result == PixelCopy.SUCCESS) push(tw, th);
                    next(t0, 0);
                }, handler);
            } catch (Exception e) {
                next(t0, 200);
            }
        } else {
            // Android 7.x: vẽ view vào bitmap (video có thể bị đen trên máy cũ)
            main.post(() -> {
                try {
                    Canvas cv = new Canvas(bitmap);
                    cv.scale(tw / (float) decor.getWidth(), th / (float) decor.getHeight());
                    decor.draw(cv);
                    handler.post(() -> {
                        push(tw, th);
                        next(t0, 0);
                    });
                } catch (Exception e) {
                    handler.post(() -> next(t0, 200));
                }
            });
        }
    }

    private void push(int tw, int th) {
        try {
            if (tw != curW || th != curH) {
                helper.setTextureSize(tw, th);
                surface.release();
                surface = new Surface(helper.getSurfaceTexture());
                curW = tw;
                curH = th;
            }
            Canvas c = Build.VERSION.SDK_INT >= 23 ? surface.lockHardwareCanvas() : surface.lockCanvas(null);
            try {
                c.drawBitmap(bitmap, null, new Rect(0, 0, c.getWidth(), c.getHeight()), null);
            } finally {
                surface.unlockCanvasAndPost(c);
            }
        } catch (Exception ignored) {
        }
    }
}
