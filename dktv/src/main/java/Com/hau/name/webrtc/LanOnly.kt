package Com.hau.name.webrtc

/**
 * CHẾ ĐỘ CHỈ CÙNG MẠNG (LAN).
 *
 * ENABLED = true  : chỉ kết nối khi điện thoại và máy tính cùng Wi-Fi / cùng hotspot.
 *                   Không dùng STUN/TURN, bỏ mọi đường đi qua Internet
 *                   (vẫn dùng Firebase để 2 máy tìm thấy nhau).
 * ENABLED = false : như bản cũ (STUN + TURN, kết nối được cả khi khác mạng).
 */
object LanOnly {
    const val ENABLED = true

    private val CANDIDATE = Regex(
        """^(?:a=)?candidate:\S+\s+\d+\s+\S+\s+\d+\s+(\S+)\s+\d+\s+typ\s+(\w+)""",
        RegexOption.IGNORE_CASE
    )

    /** true nếu ứng viên kết nối là địa chỉ nội bộ (host) và nên được dùng/gửi đi. */
    fun isLanCandidate(sdp: String): Boolean {
        if (!ENABLED) return true
        val m = CANDIDATE.find(sdp.trim()) ?: return false
        if (m.groupValues[2].lowercase() != "host") return false   // bỏ srflx / relay / prflx
        return isPrivateAddress(m.groupValues[1].lowercase())
    }

    private fun isPrivateAddress(a: String): Boolean {
        if (a.endsWith(".local")) return true                       // địa chỉ mDNS nội bộ
        if (a.contains(':')) {                                      // IPv6: link-local fe80::/10, ULA fc00::/7
            val s = a.substringBefore('%')
            return s.startsWith("fe8") || s.startsWith("fe9") || s.startsWith("fea") ||
                   s.startsWith("feb") || s.startsWith("fc") || s.startsWith("fd")
        }
        val parts = a.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 ||
               (n[0] == 172 && n[1] in 16..31) ||
               (n[0] == 192 && n[1] == 168) ||
               (n[0] == 169 && n[1] == 254)
    }
}
