package com.screencast.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;

    private TextView info;
    private Button btn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Chiếu màn hình lên TV");
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

        root.addView(title);
        root.addView(info);
        root.addView(btn);
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (ScreenService.running) {
            String ip = ScreenService.getIp();
            if (ip == null) {
                info.setText("Đang chiếu, nhưng chưa thấy WiFi.\nHãy kết nối WiFi (cùng mạng với TV).");
            } else {
                info.setText("Đang chiếu.\n\nTrên TV mở trình duyệt và gõ:\n\nhttp://" + ip + ":" + ScreenService.PORT);
            }
            btn.setText("Dừng chiếu");
        } else {
            info.setText("Điện thoại và TV phải dùng chung WiFi.\nBấm nút bên dưới để bắt đầu.");
            btn.setText("Bắt đầu chiếu");
        }
    }

    private void startShare() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else {
            requestCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIF) requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenService.class);
            i.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            ScreenService.running = true;
            refresh();
        }
    }

    private void stopShare() {
        stopService(new Intent(this, ScreenService.class));
        ScreenService.running = false;
        refresh();
    }
}
