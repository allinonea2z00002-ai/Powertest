package com.purelock.app.service.vpn

/**
 * Pure DNS packet helpers (no Android dependencies): parse a DNS query and build
 * NXDOMAIN / fixed-answer / empty responses. Used by [DnsVpnService] and covered by
 * plain JVM unit tests ([DnsPacketTest]).
 *
 * Only the question section is ever inspected: the queried name and type. Query contents
 * are never stored or sent anywhere except to the upstream resolver when forwarding.
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_ANY = 255
    const val CLASS_IN = 1

    /** One parsed DNS question. [questionEnd] is the offset just past the question section. */
    data class Query(
        val id: Int,
        val name: String,
        val qtype: Int,
        val qclass: Int,
        val questionEnd: Int
    )

    /** Parses the first (and only) question of a DNS query packet, or null when malformed. */
    fun parseQuery(packet: ByteArray): Query? {
        if (packet.size < 12) return null
        val id = u16(packet, 0)
        val flags = u16(packet, 2)
        if (flags and 0x8000 != 0) return null // QR must be 0: this must be a query, not a response
        if (u16(packet, 4) != 1) return null // exactly one question
        val (name, afterName) = readName(packet, 12) ?: return null
        if (afterName + 4 > packet.size) return null
        val qtype = u16(packet, afterName)
        val qclass = u16(packet, afterName + 2)
        return Query(id, name.lowercase(), qtype, qclass, afterName + 4)
    }

    /**
     * Reads a DNS name starting at [start], following compression pointers (with a jump guard).
     * Returns the dotted name and the offset just past the name field in the original packet.
     */
    fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var pos = start
        var next = -1
        var jumps = 0
        while (true) {
            if (pos < 0 || pos >= packet.size) return null
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) {
                if (next == -1) next = pos + 1
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= packet.size) return null
                val pointer = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                if (next == -1) next = pos + 2
                if (++jumps > 8 || pointer >= packet.size) return null
                pos = pointer
                continue
            }
            if (len > 63 || pos + 1 + len > packet.size) return null
            labels += String(packet, pos + 1, len, Charsets.US_ASCII)
            pos += 1 + len
            if (labels.size > 127) return null
        }
        return labels.joinToString(".") to next
    }

    /** NXDOMAIN response (RCODE 3): "this name does not exist". Used for blocked domains. */
    fun buildNxDomain(query: Query, packet: ByteArray): ByteArray {
        val header = responseHeader(query, 0x8183, 0)
        val question = packet.copyOfRange(12, query.questionEnd)
        return header + question
    }

    /** NOERROR response with zero answers. Used for AAAA queries of SafeSearch hosts (forces IPv4). */
    fun buildEmptyResponse(query: Query, packet: ByteArray): ByteArray {
        val header = responseHeader(query, 0x8180, 0)
        val question = packet.copyOfRange(12, query.questionEnd)
        return header + question
    }

    /** NOERROR response with a single A record pointing at [ipv4] (exactly 4 bytes). */
    fun buildAddressResponse(query: Query, packet: ByteArray, ipv4: ByteArray): ByteArray {
        require(ipv4.size == 4) { "IPv4 address must be 4 bytes" }
        val header = responseHeader(query, 0x8180, 1)
        val question = packet.copyOfRange(12, query.questionEnd)
        val answer = ByteArray(16)
        // NAME as a compression pointer to the question name (offset 12).
        answer[0] = 0xC0.toByte()
        answer[1] = 0x0C.toByte()
        putU16(answer, 2, TYPE_A)
        putU16(answer, 4, CLASS_IN)
        putU32(answer, 6, 300) // TTL: 5 minutes
        putU16(answer, 10, 4)
        ipv4.copyInto(answer, 12)
        return header + question + answer
    }

    private fun responseHeader(query: Query, flags: Int, anCount: Int): ByteArray {
        val out = ByteArray(12)
        putU16(out, 0, query.id)
        putU16(out, 2, flags)
        putU16(out, 4, 1) // QDCOUNT
        putU16(out, 6, anCount)
        putU16(out, 8, 0) // NSCOUNT
        putU16(out, 10, 0) // ARCOUNT
        return out
    }

    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
    }
}

/**
 * SafeSearch by DNS rewriting: well-known search hosts are answered with the search provider's
 * own SafeSearch virtual IP, so searches on those hosts run with SafeSearch forced. Only the
 * exact hosts listed here are rewritten — never a whole suffix, so mail/drive subdomains of the
 * same provider keep working.
 */
object SafeSearch {

    private fun ip(a: Int, b: Int, c: Int, d: Int): ByteArray =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** forcesafesearch.google.com */
    private val GOOGLE_VIP = ip(216, 239, 38, 120)

    /** strict.bing.com */
    private val BING_VIP = ip(204, 79, 197, 220)

    /** safe.duckduckgo.com */
    private val DUCKDUCKGO_VIP = ip(40, 88, 21, 12)

    /** restrict.youtube.com (YouTube Restricted Mode) */
    private val YOUTUBE_VIP = ip(216, 239, 38, 120)

    private val HOSTS: Map<String, ByteArray> = mapOf(
        "google.com" to GOOGLE_VIP,
        "www.google.com" to GOOGLE_VIP,
        "bing.com" to BING_VIP,
        "www.bing.com" to BING_VIP,
        "duckduckgo.com" to DUCKDUCKGO_VIP,
        "www.duckduckgo.com" to DUCKDUCKGO_VIP,
        "youtube.com" to YOUTUBE_VIP,
        "www.youtube.com" to YOUTUBE_VIP,
        "m.youtube.com" to YOUTUBE_VIP
    )

    /** The SafeSearch VIP for this exact host, or null when the host is not a search host. */
    fun vipFor(host: String): ByteArray? = HOSTS[host.lowercase().trimEnd('.')]
}
