package com.purelock.app

import com.purelock.app.service.vpn.DnsPacket
import com.purelock.app.service.vpn.SafeSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {

    /** Builds a minimal DNS query packet for [name] / [qtype] with the given [id]. */
    private fun queryPacket(id: Int, name: String, qtype: Int): ByteArray {
        val labels = name.split(".")
        val questionSize = labels.sumOf { it.length + 1 } + 1 + 4
        val out = ByteArray(12 + questionSize)
        DnsPacket.putU16(out, 0, id)
        DnsPacket.putU16(out, 2, 0x0100) // RD
        DnsPacket.putU16(out, 4, 1) // QDCOUNT
        var pos = 12
        for (label in labels) {
            out[pos++] = label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).copyInto(out, pos)
            pos += label.length
        }
        out[pos++] = 0
        DnsPacket.putU16(out, pos, qtype)
        DnsPacket.putU16(out, pos + 2, DnsPacket.CLASS_IN)
        return out
    }

    @Test
    fun parseQuery_readsNameTypeAndId() {
        val packet = queryPacket(0x1234, "Example.COM", DnsPacket.TYPE_A)
        val query = DnsPacket.parseQuery(packet)
        assertNotNull(query)
        assertEquals(0x1234, query!!.id)
        assertEquals("example.com", query.name)
        assertEquals(DnsPacket.TYPE_A, query.qtype)
        assertEquals(DnsPacket.CLASS_IN, query.qclass)
        assertEquals(packet.size, query.questionEnd)
    }

    @Test
    fun parseQuery_rejectsResponsesAndGarbage() {
        assertNull(DnsPacket.parseQuery(ByteArray(0)))
        assertNull(DnsPacket.parseQuery(ByteArray(11)))
        // QR bit set: this is a response, not a query.
        val response = queryPacket(0x0001, "example.com", DnsPacket.TYPE_A)
        DnsPacket.putU16(response, 2, 0x8180)
        assertNull(DnsPacket.parseQuery(response))
    }

    @Test
    fun nxDomain_hasRcode3AndNoAnswers() {
        val packet = queryPacket(0xABCD, "blocked.example", DnsPacket.TYPE_A)
        val query = DnsPacket.parseQuery(packet)!!
        val response = DnsPacket.buildNxDomain(query, packet)
        assertEquals(0xABCD, DnsPacket.u16(response, 0))
        assertEquals(0x8183, DnsPacket.u16(response, 2))
        assertEquals(1, DnsPacket.u16(response, 4)) // QDCOUNT
        assertEquals(0, DnsPacket.u16(response, 6)) // ANCOUNT
        // The question section is echoed unchanged.
        assertTrue(response.copyOfRange(12, response.size).contentEquals(packet.copyOfRange(12, packet.size)))
    }

    @Test
    fun addressResponse_carriesOneARecord() {
        val packet = queryPacket(0x0007, "example.com", DnsPacket.TYPE_A)
        val query = DnsPacket.parseQuery(packet)!!
        val ip = byteArrayOf(1, 2, 3, 4)
        val response = DnsPacket.buildAddressResponse(query, packet, ip)
        assertEquals(0x8180, DnsPacket.u16(response, 2))
        assertEquals(1, DnsPacket.u16(response, 6)) // ANCOUNT
        val answerAt = query.questionEnd
        assertEquals(0xC0, response[answerAt].toInt() and 0xFF)
        assertEquals(0x0C, response[answerAt + 1].toInt() and 0xFF)
        assertEquals(DnsPacket.TYPE_A, DnsPacket.u16(response, answerAt + 2))
        assertTrue(response.copyOfRange(answerAt + 12, answerAt + 16).contentEquals(ip))
    }

    @Test
    fun safeSearch_onlyRewritesExactSearchHosts() {
        assertNotNull(SafeSearch.vipFor("www.google.com"))
        assertNotNull(SafeSearch.vipFor("bing.com"))
        assertNotNull(SafeSearch.vipFor("duckduckgo.com"))
        assertNotNull(SafeSearch.vipFor("www.youtube.com"))
        // Subdomains that are NOT search must keep working.
        assertNull(SafeSearch.vipFor("mail.google.com"))
        assertNull(SafeSearch.vipFor("drive.google.com"))
        assertNull(SafeSearch.vipFor("example.com"))
    }
}
