package com.screencast.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

public class ScreenService extends Service {
    public static final int PORT = 8080;
    public static volatile boolean running = false;

    // Đổi 2 số này nếu muốn nét hơn / mượt hơn
    private static final int MAX_SIDE = 1280; // cạnh dài nhất của video (px)
    private static final int FPS = 30;
    private static final int MAX_BITRATE = 8_000_000; // 8 Mbps
    private static final int MIN_BITRATE = 1_000_000; // 1 Mbps

    private static final String CHANNEL = "screencast";

    private EglBase egl;
    private PeerConnectionFactory factory;
    private VideoCapturer capturer;
    private SurfaceTextureHelper helper;
    private VideoSource source;
    private VideoTrack track;
    private WebServer server;
    private WifiManager.WifiLock wifiLock;
    private final List<PeerConnection> peers = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (factory != null) return START_STICKY;
        // intent == null: hệ thống tự khởi động lại service sau khi bị dọn (vd vuốt thoát app) ->
        // vẫn bật chiếu lại như bình thường thay vì tự dừng.
        running = true;
        startForegroundNotification();
        try {
            startAll();
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    // Vuốt thoát app khỏi danh sách gần đây: KHÔNG dừng chiếu. Service vẫn chạy, mở lại
    // Tube for me là hình tiếp tục được chiếu.
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Chiếu màn hình", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = nb.setContentTitle("Đang chiếu Tube for me")
                .setContentText("TV: http://" + getIp() + ":" + PORT + " • mở lại Tube for me để chiếu tiếp")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, n);
        }
    }

    private void startAll() throws Exception {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(
                    Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "screencast");
            wifiLock.acquire();
        }

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
        egl = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();

        source = factory.createVideoSource(false); // nội dung là video, không phải màn hình tĩnh -> ưu tiên mượt
        track = factory.createVideoTrack("screen", source);

        // Chỉ chụp cửa sổ Tube for me, không chụp toàn màn hình
        capturer = new WindowCapturer(MAX_SIDE);
        helper = SurfaceTextureHelper.create("capture", egl.getEglBaseContext());
        capturer.initialize(helper, getApplicationContext(), source.getCapturerObserver());
        capturer.startCapture(1280, 720, FPS);

        server = new WebServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    // ---------- WebRTC ----------

    private abstract static class SdpBase implements SdpObserver {
        public void onCreateSuccess(SessionDescription d) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String e) {}
        public void onSetFailure(String e) {}
    }

    private abstract static class ObsBase implements PeerConnection.Observer {
        public void onSignalingChange(PeerConnection.SignalingState s) {}
        public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
        public void onIceConnectionReceivingChange(boolean b) {}
        public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
        public void onIceCandidate(IceCandidate c) {}
        public void onIceCandidatesRemoved(IceCandidate[] c) {}
        public void onAddStream(MediaStream s) {}
        public void onRemoveStream(MediaStream s) {}
        public void onDataChannel(DataChannel d) {}
        public void onRenegotiationNeeded() {}
    }

    private void closePeers() {
        for (PeerConnection pc : peers) {
            try {
                pc.close();
                pc.dispose();
            } catch (Exception ignored) {
            }
        }
        peers.clear();
    }

    private String handleOffer(String offer) throws Exception {
        closePeers();
        final CountDownLatch gathered = new CountDownLatch(1);
        final CountDownLatch local = new CountDownLatch(1);
        final String[] error = new String[1];

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(new ArrayList<PeerConnection.IceServer>());
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;

        final PeerConnection pc = factory.createPeerConnection(cfg, new ObsBase() {
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown();
            }
        });
        if (pc == null) throw new Exception("Không tạo được PeerConnection");

        final RtpSender sender = pc.addTrack(track, Collections.singletonList("screen"));

        pc.setRemoteDescription(new SdpBase() {
            @Override
            public void onSetSuccess() {
                pc.createAnswer(new SdpBase() {
                    @Override
                    public void onCreateSuccess(SessionDescription d) {
                        pc.setLocalDescription(new SdpBase() {
                            @Override
                            public void onSetSuccess() {
                                local.countDown();
                            }

                            @Override
                            public void onSetFailure(String e) {
                                error[0] = e;
                                local.countDown();
                                gathered.countDown();
                            }
                        }, d);
                    }

                    @Override
                    public void onCreateFailure(String e) {
                        error[0] = e;
                        local.countDown();
                        gathered.countDown();
                    }
                }, new MediaConstraints());
            }

            @Override
            public void onSetFailure(String e) {
                error[0] = e;
                local.countDown();
                gathered.countDown();
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, offer));

        if (!local.await(5, TimeUnit.SECONDS)) throw new Exception("Hết thời gian chờ");
        if (error[0] != null) throw new Exception(error[0]);
        gathered.await(3, TimeUnit.SECONDS);

        SessionDescription ld = pc.getLocalDescription();
        if (ld == null) throw new Exception("Không có answer");

        try {
            RtpParameters p = sender.getParameters();
            for (RtpParameters.Encoding e : p.encodings) {
                e.maxBitrateBps = MAX_BITRATE;
                e.minBitrateBps = MIN_BITRATE;
            }
            sender.setParameters(p);
        } catch (Exception ignored) {
        }

        peers.add(pc);
        return ld.description;
    }

    // ---------- Web server (TV mở trang này) ----------

    private class WebServer extends NanoHTTPD {
        WebServer() {
            super(PORT);
        }

        @Override
        public Response serve(IHTTPSession s) {
            try {
                String uri = s.getUri();
                if (s.getMethod() == Method.POST && "/offer".equals(uri)) {
                    Map<String, String> files = new HashMap<>();
                    s.parseBody(files);
                    String body = files.get("postData");
                    if (body == null) throw new Exception("empty");
                    if (!body.endsWith("\n")) body += "\r\n";
                    Response r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", handleOffer(body));
                    r.addHeader("Cache-Control", "no-store");
                    return r;
                }
                if ("/".equals(uri) || "/index.html".equals(uri)) {
                    InputStream in = getAssets().open("index.html");
                    Response r = newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", in);
                    r.addHeader("Cache-Control", "no-store");
                    return r;
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", String.valueOf(e));
            }
        }
    }

    // ---------- Tiện ích ----------

    public static String getIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (name.contains("wlan") || name.contains("ap") || name.contains("eth")) {
                            return a.getHostAddress();
                        }
                        if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (server != null) server.stop();
        } catch (Exception ignored) {
        }
        closePeers();
        try {
            if (capturer != null) {
                capturer.stopCapture();
                capturer.dispose();
            }
        } catch (Exception ignored) {
        }
        if (helper != null) helper.dispose();
        if (track != null) track.dispose();
        if (source != null) source.dispose();
        if (factory != null) factory.dispose();
        if (egl != null) egl.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(true);
        super.onDestroy();
    }
}
