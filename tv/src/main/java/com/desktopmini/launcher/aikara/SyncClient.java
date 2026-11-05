package com.desktopmini.launcher.aikara;

import android.os.Handler;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Phía TV của đồng bộ: giữ kết nối TCP tới điện thoại (cổng 8093), làm theo lệnh
 * load / pause / start / rate / seek trên trình phát YouTube của TV, và gửi trạng thái về điện thoại.
 *
 * Thay đổi so với bản trước:
 *  - Độ lệch đồng hồ lấy từ mẫu có RTT nhỏ nhất trong 16 mẫu gần nhất (không thay kết quả tốt mỗi 20 giây).
 *  - Heartbeat mỗi 2 giây. Im lặng quá 6 giây thì nối lại ngay, không đợi 2 giây như trước.
 *  - Nhận lệnh "seek": điện thoại đã tạm dừng, TV tua tới đúng vị trí đó rồi dừng và chờ lệnh "start".
 *    Lệnh "start" hẹn một mốc giờ chung để cả hai cùng phát.
 *  - Tần suất chạy JavaScript ép toàn màn hình giảm từ 250 ms xuống 1 giây.
 */
public class SyncClient {
    private static final int TICK_MS = 250;
    private static final int ENFORCE_EVERY_TICKS = 4;     // 4 * 250 ms = 1 giây
    private static final int HEARTBEAT_EVERY_TICKS = 8;   // 8 * 250 ms = 2 giây
    private static final int DEAD_AFTER_MS = 6000;        // im lặng quá mức này thì coi là rớt
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int RETRY_MIN_MS = 300;
    private static final int RETRY_MAX_MS = 2000;
    private static final int CLOCK_SAMPLES = 16;

    private static final String STATE_JS =
            "(function(){var v=document.querySelector('video');" +
            "var p=document.querySelector('.html5-video-player');" +
            "var ad=!!(p&&/ad-showing|ad-interrupting/.test(p.className||''));" +
            "if(ad){var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');if(b)b.click();}" +
            "return JSON.stringify({url:location.href,t:v?v.currentTime:-1,paused:v?v.paused:true," +
            "ready:!!(v&&v.readyState>=3),ad:ad});})()";
    // Khi YouTube báo đang tắt tiếng (hay gặp lúc vào video rồi lùi về video trước) thì tự bấm bật tiếng lại,
    // không đụng tới âm lượng. Đồng thời tự phóng to video
    // khi đang ở trang xem (watch/shorts): video phủ kín màn hình, nền đen.
    private static final String ENFORCE_JS =
            "(function(){try{var v=document.querySelector('video');" +
            "var p=document.querySelector('.html5-video-player');" +
            "if(!document.getElementById('__tvfs_css')){var s=document.createElement('style');s.id='__tvfs_css';" +
            "s.textContent='html.__tvfs video{position:fixed!important;left:0!important;top:0!important;" +
            "width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;" +
            "object-fit:contain!important;z-index:2147483647!important;background:#000!important;transform:none!important}" +
            "html.__tvfs body{overflow:hidden!important}" +
            "html.__tvfs .__tvfs_anc{transform:none!important;filter:none!important;contain:none!important;" +
            "perspective:none!important;overflow:visible!important}';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "var on=!!(v&&/^\\/(watch|shorts)/.test(location.pathname));" +
            "document.documentElement.classList.toggle('__tvfs',on);" +
            "if(v&&on){for(var e=v.parentElement;e&&e!==document.body;e=e.parentElement)e.classList.add('__tvfs_anc');}" +
            "var muted=!!((v&&v.muted)||(p&&p.isMuted&&p.isMuted()));" +
            "if(muted){var q=document.querySelectorAll('[class*=unmute],[aria-label],[title]');" +
            "for(var i=0;i<q.length;i++){var x=q[i];" +
            "var t=((x.getAttribute('aria-label')||'')+' '+(x.getAttribute('title')||'')+' '+(typeof x.className==='string'?x.className:'')).toLowerCase();" +
            "if(/unmute|bật tiếng|bật âm|bỏ tắt tiếng/.test(t)&&x.offsetParent!==null){x.click();break;}}" +
            "if(p&&p.unMute&&p.isMuted&&p.isMuted())p.unMute();if(v&&v.muted)v.muted=false;}" +
            "}catch(e){}})()";
    private static final String PAUSE_JS =
            "(function(){var v=document.querySelector('video');if(v){v.pause();}})()";
    private static final String PLAY_JS =
            "(function(){var v=document.querySelector('video');if(v){v.play();}})()";

    private final Handler ui;
    private final WebView web;
    private final String host;
    private final int port;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Runnable tickRunnable;
    private final Runnable playRunnable;

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile BufferedWriter out;
    private volatile Socket sock;
    private volatile long offsetMs = 0;   // điện thoại = TV + offsetMs

    // Bộ lọc đồng hồ: giữ 16 mẫu gần nhất, dùng mẫu có độ trễ nhỏ nhất (chỉ dùng trong synchronized)
    private final long[] sampleRtt = new long[CLOCK_SAMPLES];
    private final long[] sampleOff = new long[CLOCK_SAMPLES];
    private int sampleCount = 0;
    private int sampleNext = 0;

    private int retryMs = RETRY_MIN_MS;   // chỉ dùng trên luồng mạng
    private boolean hold = false;         // chỉ dùng trên luồng chính
    private int ticks = 0;                // chỉ dùng trên luồng chính

    public SyncClient(Handler ui, WebView web, String host, int port) {
        this.ui = ui;
        this.web = web;
        this.host = host;
        this.port = port;
        this.tickRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                ticks++;
                if (ticks % HEARTBEAT_EVERY_TICKS == 0) sendPing();
                if (ticks % ENFORCE_EVERY_TICKS == 0) web.evaluateJavascript(ENFORCE_JS, null);
                web.evaluateJavascript(STATE_JS, raw -> onState(parseJs(raw)));
                ui.postDelayed(this, TICK_MS);
            }
        };
        this.playRunnable = () -> {
            hold = false;
            web.evaluateJavascript(PLAY_JS, null);
        };
    }

    public void start() {
        running = true;
        Thread t = new Thread(this::netLoop, "sync-client");
        t.setDaemon(true);
        t.start();
        ui.post(tickRunnable);
    }

    public void stop() {
        running = false;
        connected = false;
        ui.removeCallbacks(tickRunnable);
        ui.removeCallbacks(playRunnable);
        closeSocket();
        writer.shutdownNow();
    }

    /** true khi kết nối TCP tới điện thoại còn sống. Dùng để quyết định có ngắt khi mất tín hiệu tìm hay không. */
    public boolean isConnected() {
        return connected;
    }

    // ---- Luồng mạng: kết nối, nối lại ngay khi rớt (chờ tăng dần nếu không nối được) ----

    private void netLoop() {
        while (running) {
            try {
                connectOnce();
            } catch (Exception ignored) {
            }
            connected = false;
            closeSocket();
            if (!running) break;
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                break;
            }
            retryMs = Math.min(RETRY_MAX_MS, retryMs * 2);
        }
    }

    private void connectOnce() throws Exception {
        Socket s = new Socket();
        sock = s;
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.setSoTimeout(DEAD_AFTER_MS);   // im lặng quá lâu thì readLine ném lỗi và nối lại ngay
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        connected = true;
        retryMs = RETRY_MIN_MS;
        for (int i = 0; i < 10; i++) sendPing();

        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while (running && (line = br.readLine()) != null) {
            JSONObject m;
            try {
                m = new JSONObject(line);
            } catch (Exception e) {
                continue;
            }
            if ("pong".equals(m.optString("cmd"))) {
                onPong(m);
            } else {
                final JSONObject cmd = m;
                ui.post(() -> onCommand(cmd));
            }
        }
    }

    private void closeSocket() {
        try {
            Socket s = sock;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
        sock = null;
        out = null;
    }

    // ---- Đồng hồ ----

    private void sendPing() {
        try {
            JSONObject m = new JSONObject();
            m.put("cmd", "ping");
            m.put("c", System.currentTimeMillis());
            send(m);
        } catch (Exception ignored) {
        }
    }

    // Lọc theo độ trễ nhỏ nhất: mẫu chậm (lúc mạng nghẽn) không thể thay mẫu tốt,
    // nên không cần xoá kết quả cũ định kỳ.
    private synchronized void onPong(JSONObject m) {
        long t4 = System.currentTimeMillis();
        long c = m.optLong("c");
        long p = m.optLong("p");
        long rtt = t4 - c;
        if (rtt < 0) return;
        sampleRtt[sampleNext] = rtt;
        sampleOff[sampleNext] = p - (c + rtt / 2);
        sampleNext = (sampleNext + 1) % CLOCK_SAMPLES;
        if (sampleCount < CLOCK_SAMPLES) sampleCount++;
        int best = 0;
        for (int i = 1; i < sampleCount; i++) {
            if (sampleRtt[i] < sampleRtt[best]) best = i;
        }
        offsetMs = sampleOff[best];
    }

    // ---- Trạng thái gửi về điện thoại (gọi trên luồng chính) ----

    private void onState(JSONObject s) {
        if (s == null || !running) return;
        boolean paused = s.optBoolean("paused", true);
        boolean ad = s.optBoolean("ad", false);
        if (hold && !paused && !ad) {
            web.evaluateJavascript(PAUSE_JS, null);
        }
        try {
            JSONObject m = new JSONObject();
            m.put("cmd", "state");
            m.put("url", s.optString("url"));
            m.put("t", s.optDouble("t", -1));
            m.put("paused", paused);
            m.put("ready", s.optBoolean("ready", false));
            m.put("ad", ad);
            m.put("ts", System.currentTimeMillis());
            m.put("off", offsetMs);
            send(m);
        } catch (Exception ignored) {
        }
    }

    // ---- Lệnh từ điện thoại (gọi trên luồng chính) ----

    private void onCommand(JSONObject m) {
        switch (m.optString("cmd")) {
            case "load": {
                ui.removeCallbacks(playRunnable);
                hold = true;
                String vid = m.optString("vid", "");
                if (!vid.isEmpty()) web.loadUrl("https://m.youtube.com/watch?v=" + vid);
                break;
            }
            case "pause": {
                ui.removeCallbacks(playRunnable);
                hold = true;
                web.evaluateJavascript(PAUSE_JS, null);
                break;
            }
            case "start": {
                ui.removeCallbacks(playRunnable);
                hold = true;
                double pos = m.optDouble("pos", 0);
                long atLocal = m.optLong("at") - offsetMs;
                web.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.pause();if(Math.abs(v.currentTime-" + pos + ")>0.02)v.currentTime=" + pos + ";}})()",
                        null);
                long delay = Math.max(0, atLocal - System.currentTimeMillis());
                ui.postDelayed(playRunnable, delay);
                break;
            }
            case "seek": {
                // Điện thoại đã tạm dừng và chờ. TV tua tới đúng vị trí đó rồi dừng lại, không tự phát.
                double pos = m.optDouble("pos", -1);
                if (pos < 0 || Double.isNaN(pos)) break;
                ui.removeCallbacks(playRunnable);
                hold = true;
                web.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.pause();if(Math.abs(v.currentTime-" + pos + ")>0.02)v.currentTime=" + pos + ";}})()",
                        null);
                break;
            }
            default:
                break;
        }
    }

    // ---- Tiện ích ----

    private void send(JSONObject m) {
        final String line = m.toString() + "\n";
        try {
            writer.execute(() -> {
                BufferedWriter w = out;
                if (w == null) return;
                try {
                    w.write(line);
                    w.flush();
                } catch (Exception ignored) {
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static JSONObject parseJs(String raw) {
        if (raw == null || raw.equals("null")) return null;
        try {
            Object v = new JSONTokener(raw).nextValue();
            if (!(v instanceof String)) return null;
            return new JSONObject((String) v);
        } catch (Exception e) {
            return null;
        }
    }
}
