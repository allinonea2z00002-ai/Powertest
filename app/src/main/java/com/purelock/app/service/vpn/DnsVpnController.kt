package com.purelock.app.service.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

/**
 * Starts, stops and reports the state of the DNS-only VPN ([DnsVpnService]).
 *
 * The service writes a heartbeat while it runs; [isRunning] is only true when the flag is set
 * *and* the heartbeat is fresh, so a process killed without [DnsVpnService.onDestroy] does not
 * leave a stale "running" state behind.
 */
object DnsVpnController {

    private const val PREFS = "purelock_dns_vpn"
    private const val KEY_RUNNING = "running"
    private const val KEY_HEARTBEAT = "heartbeat_epoch"
    private const val KEY_WANTED = "wanted"

    /** The service is considered dead when no heartbeat arrived for this long. */
    const val HEARTBEAT_STALE_MS = 90_000L

    /** The VPN consent intent, or null when the user has already consented. */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    /** True when the user has already granted VPN consent (no dialog needed to start). */
    fun hasConsent(context: Context): Boolean = prepareIntent(context) == null

    fun start(context: Context) {
        val intent = Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        // startForegroundService: this is also called from a background worker when a lock
        // ends, where startService would throw. The service is already foreground, so the
        // 5-second startForeground window is satisfied immediately.
        val intent = Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** The user wants the site block on (used by the integrity check to warn when it is off). */
    fun setWanted(context: Context, wanted: Boolean) {
        prefs(context).edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    fun isWanted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WANTED, false)

    /** True only when the service is flagged running and its heartbeat is fresh. */
    fun isRunning(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_RUNNING, false)) return false
        return System.currentTimeMillis() - p.getLong(KEY_HEARTBEAT, 0L) < HEARTBEAT_STALE_MS
    }

    internal fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    internal fun heartbeat(context: Context) {
        prefs(context).edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
