package com.purelock.app.service.vpn

/**
 * Pure IPv4/UDP packet helpers (no Android dependencies): parse a UDP packet out of an IPv4
 * packet read from the VPN tunnel, and build the IPv4/UDP response packet that carries a DNS
 * answer back. Covered by plain JVM unit tests ([IpPacketTest]).
 *
 * Only IPv4 UDP is handled: the tunnel only routes the virtual DNS address, and Android sends
 * DNS over UDP/IPv4 to it. TCP DNS and IPv6 are not carried by this tunnel (documented limit).
 */
object IpPacket {

    const val UDP_PROTOCOL = 17
    const val DNS_PORT = 53

    data class UdpPacket(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    /** Parses an IPv4 packet into its UDP datagram, or null when it is not IPv4/UDP. */
    fun parseUdp(ipPacket: ByteArray): UdpPacket? {
        if (ipPacket.size < 20) return null
        val version = (ipPacket[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        val ihl = (ipPacket[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ipPacket.size < ihl + 8) return null
        val totalLength = DnsPacket.u16(ipPacket, 2)
        if (totalLength < ihl + 8 || totalLength > ipPacket.size) return null
        if ((ipPacket[9].toInt() and 0xFF) != UDP_PROTOCOL) return null
        val srcIp = ipPacket.copyOfRange(12, 16)
        val dstIp = ipPacket.copyOfRange(16, 20)
        val srcPort = DnsPacket.u16(ipPacket, ihl)
        val dstPort = DnsPacket.u16(ipPacket, ihl + 2)
        val udpLength = DnsPacket.u16(ipPacket, ihl + 4)
        if (udpLength < 8 || ihl + udpLength > totalLength) return null
        val payload = ipPacket.copyOfRange(ihl + 8, ihl + udpLength)
        return UdpPacket(srcIp, dstIp, srcPort, dstPort, payload)
    }

    /**
     * Builds the IPv4/UDP packet that answers [request] with [payload]: source and destination
     * are swapped, the IPv4 header checksum is computed, and the UDP checksum is left 0
     * (optional for IPv4).
     */
    fun buildUdpResponse(request: UdpPacket, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45.toByte() // IPv4, 20-byte header
        out[1] = 0
        DnsPacket.putU16(out, 2, total)
        DnsPacket.putU16(out, 4, 0) // identification
        DnsPacket.putU16(out, 6, 0x4000) // don't fragment
        out[8] = 64 // TTL
        out[9] = UDP_PROTOCOL.toByte()
        // Header checksum (bytes 10-11) is filled in after the header is complete.
        request.dstIp.copyInto(out, 12) // source = the virtual DNS server
        request.srcIp.copyInto(out, 16) // destination = the original sender
        DnsPacket.putU16(out, 10, ipChecksum(out, 0, 20))
        DnsPacket.putU16(out, 20, request.dstPort)
        DnsPacket.putU16(out, 22, request.srcPort)
        DnsPacket.putU16(out, 24, 8 + payload.size)
        DnsPacket.putU16(out, 26, 0) // UDP checksum: 0 = not computed (valid for IPv4)
        payload.copyInto(out, 28)
        return out
    }

    /** Standard IPv4 header checksum (one's complement of the one's-complement sum). */
    fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i + 1 < offset + length) {
            sum += DnsPacket.u16(data, i).toLong()
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }
}
