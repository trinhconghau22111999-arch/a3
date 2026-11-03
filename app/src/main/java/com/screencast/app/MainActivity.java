package com.screencast.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 2;

    // Bảng màu "gaming" neon
    private static final int BG_TOP = 0xFF0B0F2A;
    private static final int BG_MID = 0xFF1A0B3B;
    private static final int BG_BOT = 0xFF05060F;
    private static final int CARD = 0xFF12172E;
    private static final int CYAN = 0xFF00E5FF;
    private static final int PURPLE = 0xFF7C4DFF;
    private static final int PINK = 0xFFFF2E93;
    private static final int GREEN = 0xFF00FF9C;
    private static final int RED = 0xFFFF3D4D;
    private static final int ORANGE = 0xFFFF9100;
    private static final int GREY = 0xFF5A6283;
    private static final int TEXT = 0xFFE8ECFF;
    private static final int TEXT_DIM = 0xFF9AA3C7;

    private TextView statusText;
    private View statusDot;
    private TextView info;
    private TextView ipText;
    private LinearLayout statusCard;
    private TextView btn;
    private TextView delayValue;
    private SeekBar delayBar;
    private SharedPreferences sp;
    private int delayMs;
    private boolean openAfterStart;
    private float density;

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private GradientDrawable shape(int fill, int stroke, int strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private GradientDrawable gradient(int from, int to, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return v;
    }

    private void pressable(View v) {
        v.setClickable(true);
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                view.setAlpha(0.65f);
                view.setScaleX(0.97f);
                view.setScaleY(0.97f);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.setAlpha(1f);
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
            return false;
        });
    }

    private TextView bigButton(String label) {
        TextView t = text(label, 18, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setShadowLayer(dp(6), 0, 0, 0x88000000);
        pressable(t);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;

        getWindow().setStatusBarColor(BG_TOP);
        getWindow().setNavigationBarColor(BG_BOT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));

        // ---- Tiêu đề ----
        TextView tag = text("◢ TUBE CAST ◣", 12, PINK, true);
        tag.setGravity(Gravity.CENTER);
        tag.setLetterSpacing(0.3f);

        TextView title = text("CHIẾU TUBE FOR ME", 28, CYAN, true);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.06f);
        title.setShadowLayer(dp(14), 0, 0, CYAN);
        title.setPadding(0, dp(6), 0, 0);

        TextView sub = text("ĐIỆN THOẠI  ▶  TV  •  QUA WIFI", 12, TEXT_DIM, true);
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.15f);
        sub.setPadding(0, dp(6), 0, 0);

        root.addView(tag);
        root.addView(title);
        root.addView(sub);

        // ---- Thẻ trạng thái ----
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(18));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setLayoutParams(new LinearLayout.LayoutParams(dp(12), dp(12)));
        statusText = text("", 15, TEXT, true);
        statusText.setLetterSpacing(0.15f);
        statusText.setPadding(dp(10), 0, 0, 0);
        statusRow.addView(statusDot);
        statusRow.addView(statusText);

        info = text("", 14, TEXT_DIM, false);
        info.setLineSpacing(dp(3), 1f);
        info.setPadding(0, dp(12), 0, 0);

        ipText = text("", 20, GREEN, true);
        ipText.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        ipText.setGravity(Gravity.CENTER);
        ipText.setShadowLayer(dp(10), 0, 0, GREEN);
        ipText.setBackground(shape(0xFF070A1A, GREEN, 1, 12));
        ipText.setPadding(dp(12), dp(14), dp(12), dp(14));
        ipText.setTextIsSelectable(true);

        statusCard.addView(statusRow);
        statusCard.addView(info);
        statusCard.addView(ipText, matchWrap(14));
        root.addView(statusCard, matchWrap(24));

        // ---- Nút chính ----
        btn = bigButton("");
        btn.setOnClickListener(v -> {
            if (ScreenService.running) stopShare();
            else startShare(true);
        });
        root.addView(btn, matchWrap(20));

        TextView ytBtn = bigButton("▶  MỞ TUBE FOR ME");
        ytBtn.setTextColor(PINK);
        ytBtn.setShadowLayer(dp(8), 0, 0, PINK);
        ytBtn.setBackground(shape(0x1AFF2E93, PINK, 2, 14));
        ytBtn.setOnClickListener(v -> openTube());
        root.addView(ytBtn, matchWrap(12));

        // ---- Thẻ độ trễ âm thanh ----
        sp = getSharedPreferences("ytbrowser_prefs", MODE_PRIVATE);
        delayMs = sp.getInt("audio_delay_ms", 150);

        LinearLayout delayCard = new LinearLayout(this);
        delayCard.setOrientation(LinearLayout.VERTICAL);
        delayCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        delayCard.setBackground(shape(CARD, 0xFF2A3157, 1, 16));

        TextView delayTitle = text("ĐỘ TRỄ ÂM THANH", 12, TEXT_DIM, true);
        delayTitle.setLetterSpacing(0.2f);

        delayValue = text("", 34, CYAN, true);
        delayValue.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        delayValue.setGravity(Gravity.CENTER);
        delayValue.setShadowLayer(dp(10), 0, 0, CYAN);
        delayValue.setPadding(0, dp(6), 0, dp(2));

        delayBar = new SeekBar(this);
        delayBar.setMax(100); // 0..1000 ms, mỗi nấc 10 ms
        delayBar.setProgress(delayMs / 10);
        delayBar.setProgressTintList(ColorStateList.valueOf(CYAN));
        delayBar.setThumbTintList(ColorStateList.valueOf(PINK));
        delayBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2A3157));
        delayBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) saveDelay(p * 10);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        TextView minus = bigButton("−10 ms");
        minus.setTextSize(15);
        minus.setTextColor(CYAN);
        minus.setBackground(shape(0x1A00E5FF, CYAN, 1, 12));
        minus.setOnClickListener(v -> saveDelay(Math.max(0, delayMs - 10)));
        TextView plus = bigButton("+10 ms");
        plus.setTextSize(15);
        plus.setTextColor(CYAN);
        plus.setBackground(shape(0x1A00E5FF, CYAN, 1, 12));
        plus.setOnClickListener(v -> saveDelay(Math.min(1000, delayMs + 10)));

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(6);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(6);
        row.addView(minus, half);
        row.addView(plus, half2);

        delayCard.addView(delayTitle);
        delayCard.addView(delayValue);
        delayCard.addView(delayBar);
        delayCard.addView(row, matchWrap(10));
        root.addView(delayCard, matchWrap(20));
        updateDelayUi();

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG_MID, BG_BOT});
        sv.setBackground(bg);
        sv.addView(root);
        setContentView(sv);
        refresh();

        // Mở app là chiếu sẵn luôn (không tự mở Tube for me, ở lại màn hình này)
        if (b == null && !ScreenService.running) startShare(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void saveDelay(int ms) {
        delayMs = ms;
        sp.edit().putInt("audio_delay_ms", ms).apply();
        updateDelayUi();
    }

    private void updateDelayUi() {
        delayValue.setText(delayMs + " ms");
        if (delayBar.getProgress() != delayMs / 10) delayBar.setProgress(delayMs / 10);
    }

    private void setDot(int color, boolean pulse) {
        statusDot.setBackground(shape(color, 0, 0, 6));
        statusDot.clearAnimation();
        if (pulse) {
            AlphaAnimation a = new AlphaAnimation(1f, 0.25f);
            a.setDuration(700);
            a.setRepeatMode(Animation.REVERSE);
            a.setRepeatCount(Animation.INFINITE);
            statusDot.startAnimation(a);
        }
    }

    private void refresh() {
        if (ScreenService.running) {
            String ip = ScreenService.getIp();
            statusCard.setBackground(shape(CARD, GREEN, 2, 16));
            if (ip == null) {
                statusText.setText("ĐANG CHIẾU • CHƯA CÓ WIFI");
                statusText.setTextColor(ORANGE);
                setDot(ORANGE, true);
                info.setText("Hãy kết nối WiFi (cùng mạng với TV).");
                ipText.setVisibility(View.GONE);
            } else {
                statusText.setText("ĐANG CHIẾU TRỰC TIẾP");
                statusText.setTextColor(GREEN);
                setDot(GREEN, true);
                info.setText("Trên TV mở trình duyệt và gõ địa chỉ:");
                ipText.setText("http://" + ip + ":" + ScreenService.PORT);
                ipText.setVisibility(View.VISIBLE);
            }
            btn.setText("■  DỪNG CHIẾU");
            btn.setBackground(gradient(RED, ORANGE, 14));
        } else {
            statusCard.setBackground(shape(CARD, 0xFF2A3157, 1, 16));
            statusText.setText("SẴN SÀNG");
            statusText.setTextColor(TEXT_DIM);
            setDot(GREY, false);
            info.setText("Điện thoại và TV phải dùng chung WiFi.\nBấm nút bên dưới để chiếu, app sẽ tự mở Tube for me.");
            ipText.setVisibility(View.GONE);
            btn.setText("▶  BẮT ĐẦU CHIẾU");
            btn.setBackground(gradient(CYAN, PURPLE, 14));
        }
    }

    private void openTube() {
        startActivity(new Intent().setClassName(getPackageName(), "com.ytbrowser.app.MainActivity"));
    }

    private void startShare(boolean openTubeAfter) {
        openAfterStart = openTubeAfter;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else {
            launchShare();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIF) launchShare();
    }

    private void launchShare() {
        Intent i = new Intent(this, ScreenService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        ScreenService.running = true;
        refresh();
        if (openAfterStart) openTube();
    }

    private void stopShare() {
        stopService(new Intent(this, ScreenService.class));
        ScreenService.running = false;
        refresh();
    }
}
