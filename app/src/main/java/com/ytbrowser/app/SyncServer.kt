package com.ytbrowser.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Máy chủ đồng bộ trên điện thoại: phát beacon "SYNC1|ip|port" qua UDP để TV tự tìm,
 * mở cổng TCP 8083 để TV kết nối. Dữ liệu là các dòng JSON ngắn, mỗi dòng một lệnh.
 * Lệnh "ping" được trả lời ngay tại đây để TV đo độ lệch đồng hồ; các lệnh khác chuyển
 * lên luồng chính qua onMessage.
 */
class SyncServer(private val onMessage: (JSONObject) -> Unit) {

    companion object {
        const val SYNC_PORT = 8083
        const val BEACON_PORT = 8081
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: BufferedWriter? = null

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "sync-accept").apply { isDaemon = true }.start()
        Thread({ beaconLoop() }, "sync-beacon").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { client?.close() } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        client = null
        out = null
        server = null
    }

    fun send(obj: JSONObject) {
        val line = obj.toString() + "\n"
        writer.execute {
            val w = out ?: return@execute
            try {
                w.write(line)
                w.flush()
            } catch (e: Exception) { }
        }
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(SYNC_PORT))
            server = ss
            while (running) {
                val s = ss.accept()
                s.tcpNoDelay = true
                try { client?.close() } catch (e: Exception) { }
                client = s
                out = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                Thread({ readLoop(s) }, "sync-read").apply { isDaemon = true }.start()
            }
        } catch (e: Exception) { }
    }

    private fun readLoop(s: Socket) {
        try {
            val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running) {
                val line = br.readLine() ?: break
                val m = try { JSONObject(line) } catch (e: Exception) { continue }
                if (m.optString("cmd") == "ping") {
                    send(JSONObject().put("cmd", "pong").put("c", m.optLong("c")).put("p", System.currentTimeMillis()))
                } else {
                    main.post { onMessage(m) }
                }
            }
        } catch (e: Exception) { }
        finally {
            if (client === s) {
                out = null
                client = null
                main.post { onMessage(JSONObject().put("cmd", "disconnected")) }
            }
            try { s.close() } catch (e: Exception) { }
        }
    }

    private fun beaconLoop() {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.broadcast = true
            val target = InetAddress.getByName("255.255.255.255")
            while (running) {
                val ip = localIp()
                if (ip != null) {
                    val data = "SYNC1|$ip|$SYNC_PORT".toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(data, data.size, target, BEACON_PORT))
                }
                Thread.sleep(1000)
            }
        } catch (e: Exception) {
        } finally {
            try { sock?.close() } catch (e: Exception) { }
        }
    }

    // Ưu tiên giao diện Wi-Fi (wlan) để TV ở cùng mạng nhận đúng địa chỉ
    private fun localIp(): String? {
        return try {
            val nics = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            for (ni in nics) {
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
