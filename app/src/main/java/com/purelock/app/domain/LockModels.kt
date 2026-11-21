package com.purelock.app.domain

enum class LockMode {
    TIME, DISTANCE, COMBINED;

    val usesTime get() = this == TIME || this == COMBINED
    val usesDistance get() = this == DISTANCE || this == COMBINED
    /** Alias: a "distance" lock is a location lock (travel X km, or arrive at a zone). */
    val usesLocation get() = usesDistance

    companion object {
        fun fromName(name: String?): LockMode = values().firstOrNull { it.name == name } ?: TIME
    }
}

enum class CombineRule {
    /** Unlock when either condition is met. */
    ANY,
    /** Unlock only when both conditions are met. */
    ALL;

    companion object {
        fun fromName(name: String?): CombineRule = values().firstOrNull { it.name == name } ?: ANY
    }
}

/** How a location condition is satisfied: travel away from the start, or arrive at a saved zone. */
enum class LocationKind {
    DISTANCE,
    ZONE;

    companion object {
        fun fromName(name: String?): LocationKind =
            values().firstOrNull { it.name == name } ?: DISTANCE
    }
}

/** The eight per-module protection switches of the unified lock, plus the incognito switch. */
data class ModuleSnapshot(
    val powerMenu: Boolean = true,
    val apkInstall: Boolean = true,
    val uninstall: Boolean = true,
    val appInfo: Boolean = true,
    val appBlock: Boolean = true,
    val featureBlock: Boolean = true,
    val siteBlock: Boolean = true,
    val keywordBlock: Boolean = true,
    val blockIncognito: Boolean = false
) {
    /** Every module on: the strongest lock. */
    val allOn: Boolean
        get() = powerMenu && apkInstall && uninstall && appInfo && appBlock &&
            featureBlock && siteBlock && keywordBlock

    companion object {
        val ALL_ON = ModuleSnapshot()
        val ALL_OFF = ModuleSnapshot(
            powerMenu = false, apkInstall = false, uninstall = false, appInfo = false,
            appBlock = false, featureBlock = false, siteBlock = false, keywordBlock = false,
            blockIncognito = false
        )

        fun fromLock(cfg: com.purelock.app.data.db.LockConfiguration): ModuleSnapshot =
            ModuleSnapshot(
                powerMenu = cfg.modulePowerMenu,
                apkInstall = cfg.moduleApkInstall,
                uninstall = cfg.moduleUninstall,
                appInfo = cfg.moduleAppInfo,
                appBlock = cfg.moduleAppBlock,
                featureBlock = cfg.moduleFeatureBlock,
                siteBlock = cfg.moduleSiteBlock,
                keywordBlock = cfg.moduleKeywordBlock,
                blockIncognito = cfg.blockIncognito
            )

        fun fromSettings(s: com.purelock.app.data.prefs.AppSettings): ModuleSnapshot =
            ModuleSnapshot(
                powerMenu = s.modulePowerMenu,
                apkInstall = s.moduleApkInstall,
                uninstall = s.moduleUninstall,
                appInfo = s.moduleAppInfo,
                appBlock = s.moduleAppBlock,
                featureBlock = s.moduleFeatureBlock,
                // Site blocking needs the adult filter itself to be on, not just its module switch.
                siteBlock = s.moduleSiteBlock && s.filterEnabled,
                keywordBlock = s.moduleKeywordBlock,
                blockIncognito = s.blockIncognito
            )
    }
}

/** What the UI shows for the current lock. Computed from stored state; never from fake values. */
data class LockSnapshot(
    val active: Boolean,
    val mode: LockMode,
    val rule: CombineRule,
    val durationMs: Long,
    val remainingMs: Long,
    val timeSatisfied: Boolean,
    val distanceSatisfied: Boolean,
    val distanceLimitM: Int,
    val distanceM: Float,
    /** Epoch of the last real location fix used for [distanceM]; 0 if none yet. */
    val lastLocationEpoch: Long,
    val blockAdult: Boolean,
    val blockApps: Boolean,
    /** How the location condition is satisfied (distance travelled vs zone arrival). */
    val locationKind: LocationKind = LocationKind.DISTANCE,
    /** The SafeZone whose arrival unlocks the lock (0 when the kind is DISTANCE). */
    val unlockZoneId: Long = 0,
    /** The per-module switches in force for this lock (snapshot while active). */
    val modules: ModuleSnapshot = ModuleSnapshot.ALL_ON
) {
    /** 0f..1f progress toward unlocking, for the countdown ring. */
    val progress: Float
        get() {
            val timeProgress =
                if (durationMs > 0) (1f - remainingMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            val distanceProgress =
                if (distanceLimitM > 0) (distanceM / distanceLimitM.toFloat()).coerceIn(0f, 1f) else 0f
            val t = if (timeSatisfied) 1f else timeProgress
            val d = if (distanceSatisfied) 1f else distanceProgress
            return when (mode) {
                LockMode.TIME -> t
                LockMode.DISTANCE -> d
                LockMode.COMBINED -> if (rule == CombineRule.ANY) maxOf(t, d) else minOf(t, d)
            }
        }

    companion object {
        val INACTIVE = LockSnapshot(
            active = false, mode = LockMode.TIME, rule = CombineRule.ANY,
            durationMs = 0, remainingMs = 0, timeSatisfied = false, distanceSatisfied = false,
            distanceLimitM = 0, distanceM = 0f, lastLocationEpoch = 0,
            blockAdult = false, blockApps = false
        )
    }
}
