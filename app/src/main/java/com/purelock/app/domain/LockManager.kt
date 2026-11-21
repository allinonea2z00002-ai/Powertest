package com.purelock.app.domain

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.purelock.app.R
import com.purelock.app.admin.DevicePolicyHelper
import com.purelock.app.data.EventLog
import com.purelock.app.data.StatsRepository
import com.purelock.app.data.db.AppDatabase
import com.purelock.app.data.db.LocationRecord
import com.purelock.app.data.db.LockConfiguration
import com.purelock.app.data.db.ProtectionSession
import com.purelock.app.data.db.SecurityEventType
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.data.prefs.SettingsStore
import com.purelock.app.location.GeofenceManager
import com.purelock.app.location.LocationHelper
import com.purelock.app.service.WorkScheduler
import com.purelock.app.service.image.ImageDetectionController
import com.purelock.app.service.image.ImageDetectionService
import com.purelock.app.service.vpn.DnsVpnController
import com.purelock.app.util.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Smart Lock engine. All state lives in the database (see [LockConfiguration]); nothing is held
 * only in memory, so the lock survives app closure, process death, screen-off and reboot.
 */
class LockManager(
    private val context: Context,
    private val db: AppDatabase,
    private val stats: StatsRepository,
    private val log: EventLog,
    private val notifier: Notifier,
    private val settings: SettingsStore,
    private val geofences: GeofenceManager
) {
    private val mutex = Mutex()
    private val dao get() = db.lockDao()

    data class StartRequest(
        val mode: LockMode,
        val durationMs: Long,
        val distanceM: Int,
        val rule: CombineRule,
        val startLat: Double?,
        val startLng: Double?,
        val blockAdult: Boolean,
        val blockApps: Boolean,
        /** How the location condition is satisfied (travel X km, or arrive at a zone). */
        val locationKind: LocationKind = LocationKind.DISTANCE,
        /** The SafeZone whose arrival unlocks the lock (only when kind is ZONE). */
        val unlockZoneId: Long = 0,
        /** Per-module switches in force for this lock. Null = strongest (all on). */
        val modules: ModuleSnapshot? = null,
        val blockIncognito: Boolean = false,
        /** Experimental image detection sensitivity: 0 = off, else 1 (low) .. 3 (high). */
        val imageSensitivity: Int = 0
    )

    sealed interface StartResult {
        data object Started : StartResult
        data object AlreadyActive : StartResult
        data object InvalidDuration : StartResult
        data object InvalidDistance : StartResult
        data object NeedsStartLocation : StartResult
        data object NeedsUnlockZone : StartResult
    }

    fun observe(): Flow<LockConfiguration?> = dao.observe()

    suspend fun current(): LockConfiguration? = dao.get()

    suspend fun isActive(): Boolean = dao.get()?.active == true

    fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    // ---------------------------------------------------------------- start

    suspend fun start(req: StartRequest): StartResult = mutex.withLock {
        val existing = dao.get()
        if (existing?.active == true) return@withLock StartResult.AlreadyActive

        if (req.mode.usesTime) {
            val minutes = req.durationMs / 60_000
            val isPreset = LockEvaluator.TIME_PRESETS_MS.contains(req.durationMs)
            if (!isPreset && !LockEvaluator.isValidCustomMinutes(minutes)) return@withLock StartResult.InvalidDuration
        }
        if (req.mode.usesDistance) {
            if (req.locationKind == LocationKind.ZONE) {
                if (req.unlockZoneId <= 0 || db.safeZoneDao().get(req.unlockZoneId) == null) {
                    return@withLock StartResult.NeedsUnlockZone
                }
            } else {
                if (!LockEvaluator.isValidDistance(req.distanceM)) return@withLock StartResult.InvalidDistance
                if (req.startLat == null || req.startLng == null) return@withLock StartResult.NeedsStartLocation
            }
        }

        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val currentSettings = settings.get()
        // Snapshot the per-module switches: enforcement reads this snapshot while the lock is
        // active, so flipping a live setting cannot weaken the lock (see strengthenModule).
        val modules = req.modules ?: ModuleSnapshot(
            siteBlock = req.blockAdult,
            appBlock = req.blockApps
        )

        val sessionId = db.sessionDao().insert(
            ProtectionSession(
                mode = req.mode.name,
                startEpoch = now,
                plannedDurationMs = if (req.mode.usesTime) req.durationMs else 0,
                distanceLimitM = if (req.mode.usesDistance) req.distanceM else 0,
                combineRule = req.rule.name
            )
        )

        val config = LockConfiguration(
            active = true,
            mode = req.mode.name,
            combineRule = req.rule.name,
            durationMs = if (req.mode.usesTime) req.durationMs else 0,
            distanceLimitM = if (req.mode.usesDistance) req.distanceM else 0,
            startLat = req.startLat,
            startLng = req.startLng,
            startEpoch = now,
            remainingAtCheckpointMs = if (req.mode.usesTime) req.durationMs else 0,
            checkpointElapsed = elapsed,
            checkpointEpoch = now,
            bootCount = bootCount(),
            sessionId = sessionId,
            blockAdult = modules.siteBlock,
            blockApps = modules.appBlock,
            filterWasEnabled = currentSettings.filterEnabled,
            modulePowerMenu = modules.powerMenu,
            moduleApkInstall = modules.apkInstall,
            moduleUninstall = modules.uninstall,
            moduleAppInfo = modules.appInfo,
            moduleAppBlock = modules.appBlock,
            moduleFeatureBlock = modules.featureBlock,
            moduleSiteBlock = modules.siteBlock,
            moduleKeywordBlock = modules.keywordBlock,
            locationKind = req.locationKind.name,
            unlockZoneId = if (req.locationKind == LocationKind.ZONE) req.unlockZoneId else 0,
            blockIncognito = modules.blockIncognito,
            imageSensitivity = req.imageSensitivity.coerceIn(0, 3)
        )
        dao.upsert(config)

        if (req.mode.usesDistance && req.startLat != null && req.startLng != null) {
            db.locationDao().insert(
                LocationRecord(latitude = req.startLat, longitude = req.startLng, accuracyM = 0f, epoch = now, source = "START")
            )
        }

        if (modules.siteBlock) settings.set(SettingsKeys.FILTER_ENABLED, true)
        log.log(
            SecurityEventType.LOCK_STARTED,
            "${req.mode.name}/${req.rule.name}/${req.locationKind.name}"
        )

        geofences.sync(config, db.safeZoneDao().getAll())
        if (req.mode.usesTime) WorkScheduler.scheduleUnlock(context, req.durationMs)
        WorkScheduler.schedulePeriodic(context)
        DevicePolicyHelper.applyLockRestrictions(context, modules)

        // Site blocking rides the DNS VPN: start it when the lock owns site blocking and the
        // user has already consented. Without consent the service cannot start and the
        // integrity check warns instead of pretending the site block works.
        if (modules.siteBlock) {
            DnsVpnController.setWanted(context, true)
            if (!DnsVpnController.isRunning(context) && DnsVpnController.hasConsent(context)) {
                DnsVpnController.start(context)
                dao.upsert(config.copy(vpnStartedByLock = true))
            }
        }
        StartResult.Started
    }

    /**
     * Strengthens the active lock by turning one module on. Only ever sets a flag to true:
     * weakening is refused by the caller (see [ModuleSwitchGuard]) and never reaches here.
     * Re-applies the Device Owner restrictions so the new module takes effect at once.
     * Returns false when no lock is active.
     */
    suspend fun strengthenModule(module: String): Boolean = mutex.withLock {
        val cfg = dao.get() ?: return@withLock false
        if (!cfg.active) return@withLock false
        val updated = when (module) {
            "powerMenu" -> cfg.copy(modulePowerMenu = true)
            "apkInstall" -> cfg.copy(moduleApkInstall = true)
            "uninstall" -> cfg.copy(moduleUninstall = true)
            "appInfo" -> cfg.copy(moduleAppInfo = true)
            "appBlock" -> cfg.copy(moduleAppBlock = true, blockApps = true)
            "featureBlock" -> cfg.copy(moduleFeatureBlock = true)
            "siteBlock" -> cfg.copy(moduleSiteBlock = true, blockAdult = true)
            "keywordBlock" -> cfg.copy(moduleKeywordBlock = true)
            "incognito" -> cfg.copy(blockIncognito = true)
            else -> return@withLock false
        }
        dao.upsert(updated)
        DevicePolicyHelper.applyLockRestrictions(context, ModuleSnapshot.fromLock(updated))
        if (module == "siteBlock") {
            DnsVpnController.setWanted(context, true)
            if (!DnsVpnController.isRunning(context) && DnsVpnController.hasConsent(context)) {
                DnsVpnController.start(context)
                dao.upsert(updated.copy(vpnStartedByLock = true))
            }
        }
        log.log(SecurityEventType.MODULE_STRENGTHENED, module)
        true
    }

    // ----------------------------------------------------------- evaluation

    /**
     * Advances the checkpoint, credits protected time, latches time conditions and finishes the lock
     * when its unlock rule is satisfied. Safe to call at any moment and any number of times.
     */
    suspend fun checkpoint(): LockConfiguration? = mutex.withLock { checkpointLocked() }

    private suspend fun checkpointLocked(): LockConfiguration? {
        val cfg = dao.get() ?: return null
        if (!cfg.active) return cfg

        val mode = LockMode.fromName(cfg.mode)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val sameBoot = LockEvaluator.isSameBoot(cfg.bootCount, bootCount(), cfg.checkpointElapsed, nowElapsed)

        val passed = LockEvaluator.passedMs(cfg.checkpointElapsed, cfg.checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        val remaining =
            if (mode.usesTime) (cfg.remainingAtCheckpointMs - passed).coerceAtLeast(0) else 0L
        // Never credit more protected time than the lock actually had left.
        val credited = if (mode.usesTime) minOf(passed, cfg.remainingAtCheckpointMs) else passed
        stats.addProtected(nowEpoch - credited, credited)

        val updated = cfg.copy(
            remainingAtCheckpointMs = remaining,
            checkpointElapsed = nowElapsed,
            checkpointEpoch = nowEpoch,
            bootCount = bootCount(),
            timeSatisfied = cfg.timeSatisfied || (mode.usesTime && remaining == 0L)
        )
        return evaluateAndSave(updated)
    }

    private suspend fun evaluateAndSave(cfg: LockConfiguration): LockConfiguration {
        val unlocked = LockEvaluator.isUnlocked(
            LockMode.fromName(cfg.mode), CombineRule.fromName(cfg.combineRule),
            cfg.timeSatisfied, cfg.distanceSatisfied
        )
        if (unlocked) {
            return finishLocked(cfg, "COMPLETED")
        }
        dao.upsert(cfg)
        return cfg
    }

    // ------------------------------------------------------------- distance

    /** Feeds a real location fix into an active Distance/Combined lock. */
    suspend fun onLocation(lat: Double, lng: Double, accuracyM: Float, source: String): Unit = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.active || !LockMode.fromName(cfg.mode).usesDistance) return@withLock
        val now = System.currentTimeMillis()

        if (settings.get().storeLocationHistory) {
            val last = db.locationDao().latest()
            if (source != "APP" || last == null || now - last.epoch >= 5 * 60_000L) {
                db.locationDao().insert(LocationRecord(latitude = lat, longitude = lng, accuracyM = accuracyM, epoch = now, source = source))
            }
            db.locationDao().deleteOlderThan(now - 30L * 86_400_000L)
        }

        // Zone arrival is normally delivered by the geofence, but a fix is a second chance:
        // if the geofence transition was missed, the fix itself can satisfy the condition.
        if (LocationKind.fromName(cfg.locationKind) == LocationKind.ZONE) {
            val zone = if (cfg.unlockZoneId > 0) db.safeZoneDao().get(cfg.unlockZoneId) else null
            if (zone != null && !cfg.distanceSatisfied) {
                val toZone = LocationHelper.distanceMeters(zone.latitude, zone.longitude, lat, lng)
                if (LockEvaluator.zoneReached(toZone, accuracyM, zone.radiusM)) {
                    onUnlockZoneEnteredLocked(cfg, zone.id)
                    return@withLock
                }
                dao.upsert(cfg.copy(lastLocationEpoch = now, lastLocationAccuracyM = accuracyM))
            }
            return@withLock
        }

        val startLat = cfg.startLat ?: return@withLock
        val startLng = cfg.startLng ?: return@withLock
        val distance = LocationHelper.distanceMeters(startLat, startLng, lat, lng)
        val reached = LockEvaluator.distanceReached(distance, accuracyM, cfg.distanceLimitM)

        val updated = cfg.copy(
            lastDistanceM = distance,
            lastLocationEpoch = now,
            lastLocationAccuracyM = accuracyM,
            distanceSatisfied = cfg.distanceSatisfied || reached
        )
        if (reached && !cfg.distanceSatisfied) {
            // Update the time part too so the unlock decision uses fresh numbers.
            evaluateAndSave(updated)
        } else {
            dao.upsert(updated)
        }
    }

    /** Called by the geofence EXIT transition around the starting point (distance locks only). */
    suspend fun onGeofenceExit(): Unit = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.active || !LockMode.fromName(cfg.mode).usesDistance || cfg.distanceSatisfied) return@withLock
        if (LocationKind.fromName(cfg.locationKind) != LocationKind.DISTANCE) return@withLock
        val limit = cfg.distanceLimitM.toFloat()
        evaluateAndSave(
            cfg.copy(
                distanceSatisfied = true,
                lastDistanceM = maxOf(cfg.lastDistanceM, limit),
                lastLocationEpoch = System.currentTimeMillis()
            )
        )
    }

    /** Called by the geofence ENTER transition for the lock's unlock zone (zone locks only). */
    suspend fun onUnlockZoneEntered(zoneId: Long): Unit = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        onUnlockZoneEnteredLocked(cfg, zoneId)
    }

    private suspend fun onUnlockZoneEnteredLocked(cfg: LockConfiguration, zoneId: Long) {
        if (!cfg.active || !LockMode.fromName(cfg.mode).usesDistance || cfg.distanceSatisfied) return
        if (LocationKind.fromName(cfg.locationKind) != LocationKind.ZONE) return
        if (cfg.unlockZoneId != zoneId) return
        log.log(SecurityEventType.ZONE_UNLOCK, "zone=$zoneId")
        evaluateAndSave(
            cfg.copy(
                distanceSatisfied = true,
                lastLocationEpoch = System.currentTimeMillis()
            )
        )
    }

    // ----------------------------------------------------------- zone locks

    /** A safe zone with an automatic lock was entered: start a time lock if none is active. */
    suspend fun startZoneLock(zoneId: Long) {
        val zone = db.safeZoneDao().get(zoneId) ?: return
        if (zone.autoLockMinutes <= 0 || isActive()) return
        // A zone lock blocks with all modules on; start() also starts the DNS VPN
        // when consent already exists, otherwise the URL watch covers site blocking
        // and the integrity check warns.
        val result = start(
            StartRequest(
                mode = LockMode.TIME,
                durationMs = zone.autoLockMinutes * 60_000L,
                distanceM = 0,
                rule = CombineRule.ANY,
                startLat = null,
                startLng = null,
                blockAdult = true,
                blockApps = true
            )
        )
        if (result is StartResult.Started) {
            log.log(SecurityEventType.ZONE_LOCK, zone.name)
            notifier.event(
                Notifier.ID_ZONE,
                context.getString(R.string.notif_zone_lock_title),
                context.getString(R.string.notif_zone_lock_text, zone.name, zone.autoLockMinutes)
            )
        }
    }

    // ---------------------------------------------------------------- finish

    /**
     * Ends the lock at once. Always available and always logged: a lock must never trap someone in a
     * genuine emergency. The UI asks for an explicit confirmation first.
     */
    suspend fun emergencyOverride(): Unit = mutex.withLock {
        val cfg = dao.get() ?: return@withLock
        if (!cfg.active) return@withLock
        // Credit the time that really passed before ending.
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val sameBoot = LockEvaluator.isSameBoot(cfg.bootCount, bootCount(), cfg.checkpointElapsed, nowElapsed)
        val passed = LockEvaluator.passedMs(cfg.checkpointElapsed, cfg.checkpointEpoch, nowElapsed, nowEpoch, sameBoot)
        val credited = if (LockMode.fromName(cfg.mode).usesTime) minOf(passed, cfg.remainingAtCheckpointMs) else passed
        stats.addProtected(nowEpoch - credited, credited)
        stats.recordEmergencyOverride(nowEpoch)
        log.log(SecurityEventType.EMERGENCY_OVERRIDE, "")
        finishLocked(cfg, "EMERGENCY_OVERRIDE")
    }

    private suspend fun finishLocked(cfg: LockConfiguration, reason: String): LockConfiguration {
        val now = System.currentTimeMillis()
        db.sessionDao().get(cfg.sessionId)?.let {
            db.sessionDao().update(it.copy(endEpoch = now, endReason = reason))
        }
        val finished = cfg.copy(active = false)
        dao.upsert(finished)

        geofences.sync(null, db.safeZoneDao().getAll())
        WorkScheduler.cancelUnlock(context)
        DevicePolicyHelper.releaseLockRestrictions(context)
        if (cfg.vpnStartedByLock) DnsVpnController.stop(context)
        // Image detection only ever runs for a lock: every end path stops it.
        ImageDetectionController.setWanted(context, false)
        ImageDetectionService.stop(context)

        val keep = settings.get().keepFilterAfterLock || cfg.filterWasEnabled
        if (!keep) {
            settings.set(SettingsKeys.FILTER_ENABLED, false)
        }

        if (reason == "COMPLETED") {
            log.log(SecurityEventType.LOCK_COMPLETED, cfg.mode)
            notifier.event(
                Notifier.ID_UNLOCKED,
                context.getString(R.string.notif_completed_title),
                context.getString(R.string.notif_completed_text)
            )
        }
        return finished
    }

    // ------------------------------------------------------ system recovery

    /** Wall-clock or time-zone change. The monotonic clock is unaffected; just record it and re-anchor. */
    suspend fun onSystemTimeChanged() {
        val cfg = dao.get()
        if (cfg?.active == true) log.log(SecurityEventType.TIME_CHANGED, "")
        checkpoint()
    }

    /** After reboot or app update: re-anchor time and re-register geofences. */
    suspend fun recoverAfterRestart() {
        val before = dao.get()
        val rebooted = before?.active == true && before.bootCount != bootCount()
        val cfg = checkpoint()
        val zones = db.safeZoneDao().getAll()
        val geofencesOk = geofences.sync(cfg, zones)

        if (cfg?.active == true) {
            if (rebooted) log.log(SecurityEventType.REBOOT_RECOVERY, "")
            // Device Owner restrictions survive a reboot, but re-applying them is harmless and
            // covers the case where the device admin was re-provisioned. The DNS VPN is NOT
            // restarted here: a background receiver cannot start a foreground service on
            // Android 12+, so the integrity check warns instead until the user re-enables it.
            DevicePolicyHelper.applyLockRestrictions(context, ModuleSnapshot.fromLock(cfg))
            val remaining = LockEvaluator.snapshot(cfg, SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount()).remainingMs
            if (LockMode.fromName(cfg.mode).usesTime && remaining > 0) WorkScheduler.scheduleUnlock(context, remaining)
            if (!geofencesOk && LockMode.fromName(cfg.mode).usesDistance) {
                notifier.alert(
                    Notifier.ID_BOOT,
                    context.getString(R.string.notif_geofence_title),
                    context.getString(R.string.notif_geofence_text)
                )
            }
        }
        WorkScheduler.schedulePeriodic(context)
    }
}
