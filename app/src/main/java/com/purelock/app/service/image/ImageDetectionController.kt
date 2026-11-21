package com.purelock.app.service.image

import android.content.Context
import android.content.Intent

/**
 * State for the experimental image detection, mirroring [com.purelock.app.service.vpn.DnsVpnController].
 *
 * The MediaProjection consent (result code + data Intent from the system dialog) can only come
 * from an Activity, so the UI hands it to [holdConsent] and the service picks it up from here.
 * It lives in memory only: if the process dies the user must grant it again.
 */
object ImageDetectionController {

    private const val PREFS = "image_detection"
    private const val KEY_WANTED = "wanted"
    private const val KEY_HEARTBEAT = "heartbeat_ms"
    private const val HEARTBEAT_STALE_MS = 90_000L

    /** The MediaProjection consent the UI collected, or null when none was granted yet. */
    @Volatile
    var consent: Consent? = null
        private set

    data class Consent(val resultCode: Int, val data: Intent)

    fun holdConsent(resultCode: Int, data: Intent) {
        consent = Consent(resultCode, data)
    }

    fun clearConsent() {
        consent = null
    }

    /** True when the user wants image detection on (the lock start sets this). */
    fun isWanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

    fun setWanted(context: Context, wanted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    /** Called by the service on every sample tick; the UI/integrity check reads staleness. */
    fun heartbeat(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    /** True when the service has ticked within the last 90 seconds. */
    fun isRunning(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_HEARTBEAT, 0L)
        return last > 0 && System.currentTimeMillis() - last < HEARTBEAT_STALE_MS
    }

    /**
     * Starts the service when detection is wanted, consent is held and a model exists.
     * Returns false (and does nothing) when any of those is missing.
     */
    fun startIfWanted(context: Context, sensitivity: Int): Boolean {
        if (!isWanted(context)) return false
        val consent = consent ?: return false
        if (!ImageClassifier.modelExists(context)) return false
        ImageDetectionService.start(context, consent.resultCode, consent.data, sensitivity)
        return true
    }
}
