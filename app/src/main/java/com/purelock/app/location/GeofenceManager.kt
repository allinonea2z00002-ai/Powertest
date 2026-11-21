package com.purelock.app.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.purelock.app.data.db.LockConfiguration
import com.purelock.app.data.db.SafeZone
import com.purelock.app.domain.LocationKind
import com.purelock.app.domain.LockMode
import kotlinx.coroutines.tasks.await

/**
 * Registers geofences with Google Play services:
 *  - the active Distance lock: an EXIT geofence around the starting point (radius = chosen distance);
 *  - the active Zone lock: an ENTER geofence around the lock's unlock zone (arrival unlocks);
 *  - safe zones that have an automatic lock: an ENTER geofence each.
 * Geofences do not survive a reboot, so [sync] is called again on boot.
 */
class GeofenceManager(private val context: Context) {

    private val client = LocationServices.getGeofencingClient(context)

    private fun pendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, GeofenceReceiver::class.java),
            // Play services adds the transition data to this intent, so it has to be mutable.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    /** Replaces all registered geofences. Returns false if they could not be registered. */
    @SuppressLint("MissingPermission")
    suspend fun sync(lock: LockConfiguration?, zones: List<SafeZone>): Boolean {
        val geofences = ArrayList<Geofence>()

        if (lock != null && lock.active && LockMode.fromName(lock.mode).usesDistance &&
            !lock.distanceSatisfied
        ) {
            if (LocationKind.fromName(lock.locationKind) == LocationKind.ZONE) {
                val zone = zones.firstOrNull { it.id == lock.unlockZoneId }
                if (zone != null) {
                    geofences += Geofence.Builder()
                        .setRequestId("$UNLOCK_ZONE_PREFIX${zone.id}")
                        .setCircularRegion(zone.latitude, zone.longitude, zone.radiusM.coerceAtLeast(100).toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                }
            } else if (lock.startLat != null && lock.startLng != null) {
                geofences += Geofence.Builder()
                    .setRequestId(LOCK_ID)
                    .setCircularRegion(lock.startLat, lock.startLng, lock.distanceLimitM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
        }
        // The unlock zone of an active Zone lock must not ALSO carry its auto-lock
        // geofence: entering it would unlock and immediately re-lock at once.
        val unlockZoneId =
            if (lock?.active == true && LocationKind.fromName(lock.locationKind) == LocationKind.ZONE) {
                lock.unlockZoneId
            } else 0L
        for (zone in zones.filter { it.autoLockMinutes > 0 && it.id != unlockZoneId }) {
            geofences += Geofence.Builder()
                .setRequestId("$ZONE_PREFIX${zone.id}")
                .setCircularRegion(zone.latitude, zone.longitude, zone.radiusM.coerceAtLeast(100).toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        return try {
            client.removeGeofences(pendingIntent()).await()
            if (geofences.isEmpty()) return true
            if (!LocationHelper.hasForegroundPermission(context) || !LocationHelper.hasBackgroundPermission(context)) {
                return false
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0) // only report real transitions, not "already inside"
                .addGeofences(geofences)
                .build()
            client.addGeofences(request, pendingIntent()).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val LOCK_ID = "lock_distance"
        const val ZONE_PREFIX = "zone_"
        const val UNLOCK_ZONE_PREFIX = "unlock_zone_"
    }
}
