package com.purelock.app

import com.purelock.app.service.vpn.DnsPacket
import com.purelock.app.service.vpn.IpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketTest {

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): ByteArray =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** Builds a minimal IPv4/UDP packet carrying [payload] from src to dst. */
    private fun udpPacket(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        protocol: Int = 17
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45.toByte()
        DnsPacket.putU16(out, 2, total)
        out[8] = 64
        out[9] = protocol.toByte()
        src.copyInto(out, 12)
        dst.copyInto(out, 16)
        DnsPacket.putU16(out, 10, IpPacket.ipChecksum(out, 0, 20))
        DnsPacket.putU16(out, 20, srcPort)
        DnsPacket.putU16(out, 22, dstPort)
        DnsPacket.putU16(out, 24, 8 + payload.size)
        payload.copyInto(out, 28)
        return out
    }

    @Test
    fun parseUdp_readsPortsAndPayload() {
        val payload = "hello-dns".toByteArray()
        val packet = udpPacket(ipv4(10, 8, 0, 2), ipv4(10, 8, 0, 1), 45678, 53, payload)
        val udp = IpPacket.parseUdp(packet)
        assertNotNull(udp)
        assertEquals(45678, udp!!.srcPort)
        assertEquals(53, udp.dstPort)
        assertArrayEquals(ipv4(10, 8, 0, 2), udp.srcIp)
        assertArrayEquals(ipv4(10, 8, 0, 1), udp.dstIp)
        assertArrayEquals(payload, udp.payload)
    }

    @Test
    fun parseUdp_rejectsNonUdp() {
        val tcp = udpPacket(ipv4(10, 0, 0, 1), ipv4(10, 0, 0, 2), 1234, 80, byteArrayOf(1), protocol = 6)
        assertNull(IpPacket.parseUdp(tcp))
        assertNull(IpPacket.parseUdp(ByteArray(10)))
        val v6 = ByteArray(40)
        v6[0] = 0x60.toByte()
        assertNull(IpPacket.parseUdp(v6))
    }

    @Test
    fun buildUdpResponse_swapsAddressesAndParsesBack() {
        val request = IpPacket.UdpPacket(
            srcIp = ipv4(10, 8, 0, 2),
            dstIp = ipv4(10, 8, 0, 1),
            srcPort = 40000,
            dstPort = 53,
            payload = byteArrayOf(9, 9, 9)
        )
        val answer = byteArrayOf(1, 2, 3, 4, 5)
        val built = IpPacket.buildUdpResponse(request, answer)
        val parsed = IpPacket.parseUdp(built)
        assertNotNull(parsed)
        assertEquals(53, parsed!!.srcPort)
        assertEquals(40000, parsed.dstPort)
        assertArrayEquals(ipv4(10, 8, 0, 1), parsed.srcIp)
        assertArrayEquals(ipv4(10, 8, 0, 2), parsed.dstIp)
        assertArrayEquals(answer, parsed.payload)
    }

    @Test
    fun ipChecksum_validatesItself() {
        val header = ByteArray(20)
        header[0] = 0x45.toByte()
        DnsPacket.putU16(header, 2, 60)
        ipv4(10, 0, 0, 1).copyInto(header, 12)
        ipv4(10, 0, 0, 2).copyInto(header, 16)
        val checksum = IpPacket.ipChecksum(header, 0, 20)
        DnsPacket.putU16(header, 10, checksum)
        // A header carrying its own correct checksum sums to zero.
        assertEquals(0, IpPacket.ipChecksum(header, 0, 20))
    }
}
