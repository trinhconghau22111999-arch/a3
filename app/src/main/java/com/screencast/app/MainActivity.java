package com.screencast.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 2;

    private TextView info;
    private Button btn;
    private TextView delayLabel;
    private SeekBar delayBar;
    private SharedPreferences sp;
    private int delayMs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Chiếu Tube for me lên TV");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);

        info = new TextView(this);
        info.setTextSize(18);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, pad, 0, pad);

        btn = new Button(this);
        btn.setOnClickListener(v -> {
            if (ScreenService.running) stopShare();
            else startShare();
        });

        Button ytBtn = new Button(this);
        ytBtn.setText("Mở Tube for me");
        ytBtn.setOnClickListener(v -> openTube());

        root.addView(title);
        root.addView(info);
        root.addView(btn);
        root.addView(ytBtn);

        // Độ trễ âm thanh của Tube for me (cho khớp với độ trễ chia sẻ màn hình)
        sp = getSharedPreferences("ytbrowser_prefs", MODE_PRIVATE);
        delayMs = sp.getInt("audio_delay_ms", 150);
        delayLabel = new TextView(this);
        delayLabel.setTextSize(16);
        delayLabel.setGravity(Gravity.CENTER);
        delayLabel.setPadding(0, pad, 0, 0);
        delayBar = new SeekBar(this);
        delayBar.setMax(100); // 0..1000 ms, mỗi nấc 10 ms
        delayBar.setProgress(delayMs / 10);
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
        Button minus = new Button(this);
        minus.setText("−10 ms");
        minus.setOnClickListener(v -> saveDelay(Math.max(0, delayMs - 10)));
        Button plus = new Button(this);
        plus.setText("+10 ms");
        plus.setOnClickListener(v -> saveDelay(Math.min(1000, delayMs + 10)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(minus);
        row.addView(plus);
        root.addView(delayLabel);
        root.addView(delayBar);
        root.addView(row);
        updateDelayUi();

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
        refresh();
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
        delayLabel.setText("Độ trễ âm thanh Tube for me: " + delayMs + " ms");
        if (delayBar.getProgress() != delayMs / 10) delayBar.setProgress(delayMs / 10);
    }

    private void refresh() {
        if (ScreenService.running) {
            String ip = ScreenService.getIp();
            if (ip == null) {
                info.setText("Đang chiếu, nhưng chưa thấy WiFi.\nHãy kết nối WiFi (cùng mạng với TV).");
            } else {
                info.setText("Đang chiếu.\n\nTrên TV mở trình duyệt và gõ:\n\nhttp://" + ip + ":" + ScreenService.PORT
                        + "\n\nChỉ hình trong Tube for me được chiếu, app khác không bao giờ hiện lên TV.");
            }
            btn.setText("Dừng chiếu");
        } else {
            info.setText("Điện thoại và TV phải dùng chung WiFi.\nBấm nút bên dưới để bắt đầu, app sẽ tự mở Tube for me.");
            btn.setText("Bắt đầu chiếu");
        }
    }

    private void openTube() {
        startActivity(new Intent().setClassName(getPackageName(), "com.ytbrowser.app.MainActivity"));
    }

    private void startShare() {
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
        openTube();
    }

    private void stopShare() {
        stopService(new Intent(this, ScreenService.class));
        ScreenService.running = false;
        refresh();
    }
}
