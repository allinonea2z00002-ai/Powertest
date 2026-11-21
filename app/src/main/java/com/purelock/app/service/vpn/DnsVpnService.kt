package com.purelock.app.service.vpn

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.util.Notifier
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Site blocking as a DNS-only VPN tunnel.
 *
 * Only DNS goes through the tunnel: the VPN routes a single virtual DNS address
 * ([VIRTUAL_DNS]) and nothing else, so ordinary traffic never enters the tunnel and no
 * browsing data is proxied. For each DNS query the service either answers locally
 * (NXDOMAIN for a blocked domain, SafeSearch VIP for a search host when SafeSearch is on)
 * or forwards the raw query to the upstream resolver ([UPSTREAM_DNS]) and returns its
 * answer unchanged. HTTPS is never intercepted and no certificate is installed.
 *
 * Honest limits: only IPv4 UDP DNS is carried (TCP DNS and IPv6 DNS bypass the tunnel);
 * a browser with its own encrypted DNS, another VPN or a proxy can bypass this layer, and
 * the blocklist cannot know about every site. The accessibility URL-watch remains as a
 * second layer for addresses the browser shows.
 */
class DnsVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var safeSearchOn = true
    private var tun: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var executor: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            else -> startVpn()
        }
        return Service.START_STICKY
    }

    private fun startVpn() {
        if (!running.compareAndSet(false, true)) return
        val fd = try {
            Builder()
                .setSession(getString(R.string.vpn_session_name))
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(VIRTUAL_DNS)
                .addRoute(VIRTUAL_DNS, 32)
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            running.set(false)
            return
        } ?: run {
            running.set(false)
            return
        }
        tun = fd
        startForegroundNotification()
        DnsVpnController.setRunning(this, true)
        executor = Executors.newFixedThreadPool(4)
        scope.launch {
            runCatching { Graph.blocklist.reload() }
            // SafeSearch follows the live setting; the packet path reads the cached flag.
            Graph.settings.flow.collect { safeSearchOn = it.safeSearch }
        }
        readerThread = thread(name = "purelock-dns-reader", isDaemon = true) { readLoop(fd) }
    }

    private fun stopVpn() {
        if (!running.compareAndSet(true, false)) {
            stopSelf()
            return
        }
        runCatching { tun?.close() }
        tun = null
        executor?.shutdownNow()
        executor = null
        DnsVpnController.setRunning(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { tun?.close() }
        executor?.shutdownNow()
        DnsVpnController.setRunning(this, false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // The system (or another VPN app) took the tunnel: stop cleanly so the
        // integrity check can warn instead of pretending the site block still works.
        stopVpn()
        super.onRevoke()
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32_767)
        var lastHeartbeat = 0L
        while (running.get()) {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat > 30_000L) {
                lastHeartbeat = now
                DnsVpnController.heartbeat(this)
            }
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (length <= 0) continue
            val packet = buffer.copyOf(length)
            val pool = executor ?: break
            pool.execute { handlePacket(packet, output) }
        }
    }

    private fun handlePacket(ipPacket: ByteArray, output: FileOutputStream) {
        val udp = IpPacket.parseUdp(ipPacket) ?: return
        if (udp.dstPort != IpPacket.DNS_PORT) return
        val query = DnsPacket.parseQuery(udp.payload) ?: return
        val response: ByteArray = when {
            Graph.blocklist.isBlocked(query.name) ->
                DnsPacket.buildNxDomain(query, udp.payload)

            safeSearchOn && query.qtype == DnsPacket.TYPE_A &&
                SafeSearch.vipFor(query.name) != null ->
                DnsPacket.buildAddressResponse(query, udp.payload, SafeSearch.vipFor(query.name)!!)

            safeSearchOn && query.qtype == DnsPacket.TYPE_AAAA &&
                SafeSearch.vipFor(query.name) != null ->
                DnsPacket.buildEmptyResponse(query, udp.payload)

            else -> forwardQuery(udp.payload)
        } ?: return
        val ip = IpPacket.buildUdpResponse(udp, response)
        synchronized(output) {
            runCatching {
                output.write(ip)
                output.flush()
            }
        }
    }

    /** Forwards one raw DNS query to the upstream resolver and returns its raw answer. */
    private fun forwardQuery(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val address = InetAddress.getByName(UPSTREAM_DNS)
                socket.send(DatagramPacket(query, query.size, address, UPSTREAM_DNS_PORT))
                val buffer = ByteArray(4096)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                // Only accept an answer to this query: the transaction ID must match.
                if (reply.length < 2 || buffer[0] != query[0] || buffer[1] != query[1]) return null
                buffer.copyOf(reply.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, Notifier.CH_PROTECTION)
            .setSmallIcon(R.drawable.ic_nav_shield)
            .setContentTitle(getString(R.string.vpn_notif_title))
            .setContentText(getString(R.string.vpn_notif_text))
            .setOngoing(true)
            .build()
        // The special-use type only exists on Android 14+; below that the service runs
        // as a plain foreground service (the manifest type is ignored there too).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.purelock.app.vpn.START"
        const val ACTION_STOP = "com.purelock.app.vpn.STOP"

        /** The virtual DNS server address: the only route the tunnel carries. */
        const val VIRTUAL_DNS = "10.8.0.1"
        const val TUN_ADDRESS = "10.8.0.2"

        /** Upstream resolver for queries that are neither blocked nor SafeSearch-rewritten. */
        const val UPSTREAM_DNS = "1.1.1.1"
        const val UPSTREAM_DNS_PORT = 53
        const val UPSTREAM_TIMEOUT_MS = 3_000

        private const val NOTIFICATION_ID = 1101
    }
}
