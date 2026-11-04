package com.screencast.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * App nhận trên TV: tự tìm điện thoại đang chiếu trong cùng Wi-Fi (UDP broadcast + mDNS),
 * rồi mở thẳng trang nhận hình - không cần gõ đường link.
 */
public class MainActivity extends Activity {
    private static final int BEACON_PORT = 8081;
    private static final String NSD_TYPE = "_screencast._tcp.";
    private static final long LOST_AFTER_MS = 15000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private WifiManager.MulticastLock mcLock;
    private Thread udpThread;
    private volatile boolean udpRun;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving;

    private String curHost = null;
    private int curPort = 0;
    private long lastSeen = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setVisibility(View.INVISIBLE);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    // Không vào được trang -> quay lại tìm điện thoại
                    ui.post(() -> goSearching());
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(26);
        status.setGravity(Gravity.CENTER);
        status.setPadding(60, 0, 60, 0);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        hideBars();
        goSearching();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void goSearching() {
        curHost = null;
        web.setVisibility(View.INVISIBLE);
        web.loadUrl("about:blank");
        status.setVisibility(View.VISIBLE);
        status.setText("Đang tìm điện thoại...\n\nMở app trên điện thoại và bật chiếu\n(điện thoại và TV dùng chung Wi-Fi)" + webViewWarning());
    }

    // TV Android 8 hay có WebView cũ; WebRTC cần WebView tương đối mới
    private String webViewWarning() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null && pi.versionName != null) {
                    int major = Integer.parseInt(pi.versionName.split("\\.")[0]);
                    if (major < 70) {
                        return "\n\n⚠ Android System WebView trên TV đang cũ (" + pi.versionName + ").\nNếu không lên hình, hãy cập nhật WebView.";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void onPhoneFound(String host, int port) {
        lastSeen = System.currentTimeMillis();
        if (host.equals(curHost) && port == curPort) return;
        curHost = host;
        curPort = port;
        status.setText("Đã thấy điện thoại, đang kết nối...");
        web.loadUrl("http://" + host + ":" + port + "/");
        web.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> { if (curHost != null) status.setVisibility(View.GONE); }, 1500);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (curHost != null && System.currentTimeMillis() - lastSeen > LOST_AFTER_MS) goSearching();
            ui.postDelayed(this, 3000);
        }
    };

    // ---------- Tìm điện thoại ----------

    private void startUdp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mcLock = wm.createMulticastLock("screencast");
                mcLock.setReferenceCounted(false);
                mcLock.acquire();
            }
        } catch (Exception ignored) {
        }
        udpRun = true;
        udpThread = new Thread(() -> {
            while (udpRun) {
                DatagramSocket s = null;
                try {
                    s = new DatagramSocket(null);
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(BEACON_PORT));
                    s.setSoTimeout(2000);
                    byte[] buf = new byte[256];
                    while (udpRun) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            s.receive(p);
                        } catch (java.net.SocketTimeoutException t) {
                            continue;
                        }
                        String m = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                        String[] parts = m.split("\\|");
                        if (parts.length == 3 && parts[0].equals("SCREENCAST1")) {
                            final String host = parts[1];
                            final int port = Integer.parseInt(parts[2]);
                            ui.post(() -> onPhoneFound(host, port));
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                } finally {
                    if (s != null) s.close();
                }
            }
        }, "udp-listen");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    private void startNsd() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            discovery = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) {}
                public void onStopDiscoveryFailed(String t, int e) {}
                public void onDiscoveryStarted(String t) {}
                public void onDiscoveryStopped(String t) {}
                public void onServiceLost(NsdServiceInfo i) {}

                public void onServiceFound(NsdServiceInfo info) {
                    if (resolving) return;
                    resolving = true;
                    try {
                        nsd.resolveService(info, new NsdManager.ResolveListener() {
                            public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; }
                            public void onServiceResolved(NsdServiceInfo i) {
                                resolving = false;
                                if (i.getHost() == null) return;
                                final String host = i.getHost().getHostAddress();
                                final int port = i.getPort();
                                ui.post(() -> onPhoneFound(host, port));
                            }
                        });
                    } catch (Exception e) {
                        resolving = false;
                    }
                }
            };
            nsd.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        startUdp();
        startNsd();
        ui.postDelayed(watchdog, 3000);
    }

    @Override
    protected void onStop() {
        udpRun = false;
        ui.removeCallbacks(watchdog);
        try {
            if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (Exception ignored) {
        }
        discovery = null;
        resolving = false;
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
