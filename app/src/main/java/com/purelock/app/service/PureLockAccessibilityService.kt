package com.purelock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.ProfileRepository
import com.purelock.app.data.content.ContentWatch
import com.purelock.app.data.content.FeatureRule
import com.purelock.app.data.content.FeatureRuleMatcher
import com.purelock.app.data.content.FeatureRulePack
import com.purelock.app.data.db.SecurityEventType
import com.purelock.app.data.prefs.ContentFilterStore
import com.purelock.app.domain.ModuleSnapshot
import com.purelock.app.location.Fix
import com.purelock.app.location.LocationHelper
import com.purelock.app.ui.block.BlockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * App blocker and protection enforcer. It only runs after the user turns it on in Android's
 * Accessibility settings, having been shown what it does (see the disclosure dialog in the
 * Protection screen). It reads only the package name of the app that came to the foreground, plus
 * - while a lock is active - whether a Settings/installer screen shows this app's name, whether a
 * Vivo / MIUI system screen (security centre, permission manager) is open, and whether the power
 * menu is showing. Nothing it sees is stored or sent anywhere.
 *
 * Which protections are enforced comes from the effective [ModuleSnapshot]: the lock's own
 * snapshot while a lock is active, the live settings otherwise — so flipping a setting
 * mid-lock cannot weaken the lock (see [com.purelock.app.domain.ModuleSwitchGuard]).
 *
 * Guards (all best-effort, all gated by their module switch):
 * - Power menu: without Device Owner the power dialog is closed as soon as it is recognised.
 *   This path is partial by nature (the dialog flashes briefly) and the UI says so.
 * - APK install: Play Store / Package Installer screens send the user home during a lock.
 * - Uninstall / app info: Settings screens for PureLock (uninstall dialog, app info with
 *   "Force stop") send the user home before anything can be tapped.
 * - URL watch: when site blocking is on, the address bar shown on screen is checked against
 *   the blocklist (allowlist wins) and a match sends the user home.
 * - Keyword block: in Chrome, Firefox, Samsung Internet and Edge only, the address bar and
 *   search box are read; a URL is judged on its host + path only.
 * - Incognito: when the incognito switch is on, a private tab during a lock sends the user home.
 * - Feature block: the JSON rule pack (assets) recognises distracting in-app parts
 *   (Reels, Shorts, …) from view IDs, text and content descriptions. First hit goes Back,
 *   a repeat hit within 30 seconds goes Home. When the pack cannot load, the older
 *   hardcoded feed detection is used as a fallback.
 * A periodic re-check (about every 60 seconds) also re-applies the app-block policy to the
 * foreground app, so time-based rules take effect without an app switch.
 */
class PureLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var policy: AppBlockPolicy
    private lateinit var profileRepo: ProfileRepository

    @Volatile private var lockActive = false
    /** Effective protection modules: the lock snapshot while active, live settings otherwise. */
    @Volatile private var modules = ModuleSnapshot.ALL_OFF
    @Volatile private var featureRules: List<FeatureRule> = emptyList()
    private var lastBlockedPackage = ""
    private var lastBlockedAt = 0L
    private var lastSettingsScan = 0L
    private var lastGuardAt = 0L
    private var lastPowerMenuAt = 0L
    private var lastForegroundPackage: String? = null
    private val lastContentScanAt = HashMap<String, Long>()
    /** Feature-rule hits (rule id -> last hit epoch) for the Back-first, Home-on-repeat rule. */
    private val featureHits = HashMap<String, Long>()

    private val recheckHandler = Handler(Looper.getMainLooper())
    private val recheckRunnable = object : Runnable {
        override fun run() {
            recheckForeground()
            recheckHandler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        policy = AppBlockPolicy(this, Graph.usage)
        profileRepo = ProfileRepository(this)
        // The blocklist is also used by the URL watch, so load it here too.
        scope.launch { runCatching { Graph.blocklist.reload() } }
        scope.launch {
            featureRules = runCatching { FeatureRulePack.load(this@PureLockAccessibilityService) }
                .getOrDefault(emptyList())
            if (featureRules.isEmpty() && FeatureRulePack.loadError(this@PureLockAccessibilityService)) {
                runCatching { Graph.eventLog.log(SecurityEventType.FEATURE_RULES_BROKEN, "") }
            }
        }
        scope.launch {
            combine(
                Graph.db.lockDao().observe(),
                Graph.db.blockedAppDao().observeAll(),
                Graph.db.allowedAppDao().observeAll(),
                Graph.settings.flow
            ) { lock, blockedApps, allowedApps, settings ->
                lockActive = lock?.active == true
                modules = if (lockActive && lock != null) ModuleSnapshot.fromLock(lock)
                else ModuleSnapshot.fromSettings(settings)
                policy.lockBlocksApps = lockActive && modules.appBlock
                policy.blocked = blockedApps.associateBy { it.packageName }
                policy.allowed = allowedApps.map { it.packageName }.toHashSet()
            }.collect { }
        }
        scope.launch {
            combine(
                Graph.db.safeZoneDao().observeAll(),
                Graph.db.locationDao().observeLatest()
            ) { zones, latestLocation ->
                // Location-gate inputs for AppBlockPolicy (zones + latest recorded fix).
                policy.zones = zones.associateBy { it.id }
                policy.lastFix = latestLocation?.let {
                    Fix(it.latitude, it.longitude, it.accuracyM, it.epoch)
                }
            }.collect { }
        }
        // Profile enforcement (Area C): the active profiles' rules are rebuilt into
        // the policy whenever they change — see ProfileEnforcer for the semantics.
        scope.launch {
            Graph.db.profileDao().observeActive().collect { activeProfiles ->
                val launcherPackages = runCatching {
                    packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                    ).map { it.activityInfo.packageName }
                        .filter { it != packageName }
                        .distinct()
                }.getOrDefault(emptyList())
                val enforcement = ProfileEnforcer.build(
                    activeProfiles,
                    launcherPackages,
                    profileRepo::isCombined,
                    profileRepo::quickBlockUntilEpoch
                )
                policy.profileBlocked = enforcement.rules
                policy.profileMotivations = enforcement.motivations
                policy.combinedLimit = enforcement.combined
                policy.profileSiteRules = enforcement.siteRules
            }
        }
        recheckHandler.postDelayed(recheckRunnable, RECHECK_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::policy.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Power menu (no Device Owner): the system UI package is never blocked as an app,
        // but its power dialog is closed while a lock is active. Checked before the
        // never-block list, because System UI is on it.
        if (pkg == SYSTEM_UI_PACKAGE) {
            if (lockActive && modules.powerMenu &&
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                guardPowerMenu()
            }
            return
        }

        if (pkg in SETTINGS_PACKAGES) {
            if (lockActive) {
                // APK install block: the installer itself is off-limits during a lock.
                if (pkg in INSTALLER_PACKAGES && modules.apkInstall) {
                    block(pkg, BlockReason.SELF_PROTECT)
                    return
                }
                if (modules.uninstall || modules.appInfo) guardSettingsScreen()
            }
            return
        }
        if (pkg == PLAY_STORE_PACKAGE && lockActive && modules.apkInstall) {
            block(pkg, BlockReason.SELF_PROTECT)
            return
        }
        if (isGuardedSystemApp(pkg)) {
            if (lockActive && modules.uninstall) guardSystemApp(pkg)
            return
        }
        if (policy.isNeverBlocked(pkg)) return

        // Take a Break (SPEC S02/S03, Area A): while a break is active, only
        // whitelisted apps may come to the foreground — everything else is blocked,
        // before the normal app-block policy and content checks run.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            BreakManager.shouldBlockNow(this) && pkg !in BreakManager.whitelist(this)
        ) {
            lastForegroundPackage = pkg
            block(pkg, BlockReason.LOCK)
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastForegroundPackage = pkg
                // The app-block policy wins: a fully blocked app never reaches the content checks.
                policy.reason(pkg)?.let { reason ->
                    maybeArmWaitTimer(pkg, reason)
                    block(pkg, reason)
                    return
                }
                checkContent(pkg, throttled = false)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkContent(pkg, throttled = true)
            }
        }
    }

    /**
     * URL watch, browser keyword match, incognito detection and feature detection for the
     * foreground app. Content-changed events are throttled per package; window changes always
     * check. Stops at the first match.
     */
    private fun checkContent(pkg: String, throttled: Boolean) {
        if (throttled) {
            val now = System.currentTimeMillis()
            if (now - (lastContentScanAt[pkg] ?: 0L) < CONTENT_SCAN_THROTTLE_MS) return
            lastContentScanAt[pkg] = now
        }
        val root = rootInActiveWindow ?: return
        // Only judge the app that is actually in front; a background app's content change must
        // never block the foreground app.
        if (root.packageName?.toString() != pkg) return

        // (a) URL watch: when site blocking is on, the address bar shown on screen is checked
        //   against the blocklist (allowlist wins) and a match sends the user home.
        //   Profile sites are checked the same way, with the profile's condition
        //   evaluated for the foreground browser (see AppBlockPolicy.evaluateSiteRule).
        val host = ContentWatch.findAddressBarHost(root)
        if (host != null) {
            if (modules.siteBlock && Graph.blocklist.isBlocked(host)) {
                blockContent(pkg, recordWebsiteBlock = true)
                return
            }
            val siteRule = ProfileEnforcer.siteRuleFor(host, policy.profileSiteRules)
            if (siteRule != null && policy.evaluateSiteRule(siteRule.rule, pkg) != null) {
                blockContent(pkg, recordWebsiteBlock = true, motivation = siteRule.motivation)
                return
            }
        }

        // (b) Keyword block: browsers only. The address bar / search box is read; a URL is
        //   judged on its host + path only (never the query string), search text as-is.
        if (modules.keywordBlock && ContentWatch.isBrowser(pkg) &&
            ContentFilterStore.keywordsEnabled(this)
        ) {
            val keywords = ContentFilterStore.keywords(this)
            if (keywords.isNotEmpty()) {
                val addressBar = ContentWatch.findAddressBarText(root)
                val hit = when {
                    addressBar == null -> null
                    else -> {
                        val hostAndPath = ContentWatch.extractHostAndPath(addressBar)
                        if (hostAndPath != null) {
                            ContentWatch.findKeywordInUrl(hostAndPath.first, hostAndPath.second, keywords)
                        } else {
                            ContentWatch.findKeyword(listOf(addressBar), keywords)
                        }
                    }
                }
                if (hit != null) {
                    blockContent(pkg, recordWebsiteBlock = false)
                    return
                }
            }
        }

        val texts = ArrayList<String>()
        ContentWatch.collectTexts(root, texts)
        if (texts.isEmpty()) return

        // (c) Incognito: a private tab during a lock, when the incognito switch is on.
        if (lockActive && modules.blockIncognito && ContentWatch.isBrowser(pkg) &&
            ContentWatch.looksIncognito(texts)
        ) {
            blockContent(pkg, recordWebsiteBlock = false)
            return
        }

        // (d) Feature block: the JSON rule pack, Back first and Home on a repeat hit.
        //   When the pack failed to load, the older hardcoded feed detection is the fallback.
        if (modules.featureBlock) {
            val rules = featureRules
            if (rules.isNotEmpty()) {
                val nodes = FeatureRuleMatcher.collectNodes(root)
                FeatureRuleMatcher.match(rules, pkg, nodes)?.let { rule ->
                    handleFeatureHit(rule, pkg)
                    return
                }
            } else if (ContentFilterStore.reelsEnabled(this)) {
                val feed = ContentWatch.detectFeed(pkg, texts)
                if (feed != null && ContentFilterStore.feedEnabled(this, feed.storeKey)) {
                    blockContent(pkg, recordWebsiteBlock = false)
                }
            }
        }
    }

    /**
     * Feature-rule response: the first hit goes Back (out of the feed), a repeat hit within
     * [FEATURE_REPEAT_WINDOW_MS] goes Home with the block screen. Rules whose action is "home"
     * always go Home.
     */
    private fun handleFeatureHit(rule: FeatureRule, pkg: String) {
        if (rule.action == FeatureRule.ACTION_HOME) {
            blockContent(pkg, recordWebsiteBlock = false)
            return
        }
        val now = System.currentTimeMillis()
        val lastHit = featureHits[rule.id] ?: 0L
        if (lastHit > 0 && now - lastHit < FEATURE_REPEAT_WINDOW_MS) {
            featureHits.remove(rule.id)
            blockContent(pkg, recordWebsiteBlock = false)
        } else {
            featureHits[rule.id] = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /** Re-applies the app-block policy to the foreground app, so time-based rules fire in place. */
    private fun recheckForeground() {
        if (!::policy.isInitialized) return
        refreshLiveFixIfNeeded()
        val pkg = lastForegroundPackage ?: return
        if (pkg == packageName || policy.isNeverBlocked(pkg)) return
        // Take a Break (SPEC S02/S03, Area A): the periodic re-check also enforces
        // the break whitelist, so a break that starts while an app is already open
        // still blocks it.
        if (BreakManager.shouldBlockNow(this) && pkg !in BreakManager.whitelist(this)) {
            block(pkg, BlockReason.LOCK)
            return
        }
        val reason = policy.reason(pkg) ?: return
        maybeArmWaitTimer(pkg, reason)
        block(pkg, reason)
    }

    /** Arms a due Wait Timer exactly once and persists it; a running wait is never re-armed. */
    private fun maybeArmWaitTimer(pkg: String, reason: BlockReason) {
        if (reason != BlockReason.WAIT_TIMER) return
        val rule = policy.blocked[pkg]
        if (rule != null) {
            if (rule.waitUntilEpochMs > System.currentTimeMillis()) return // wait already running
            val armed = policy.armWaitTimer(pkg) ?: return
            scope.launch { runCatching { Graph.db.blockedAppDao().upsert(armed) } }
            return
        }
        // Profile-derived rule: arm in memory only (never persisted as an app rule).
        val profileRule = policy.profileBlocked[pkg] ?: return
        if (profileRule.waitUntilEpochMs > System.currentTimeMillis()) return
        policy.armProfileWaitTimer(pkg)
    }

    /** Keeps the location gate fresh, but only while a location-based rule exists. */
    private fun refreshLiveFixIfNeeded() {
        val hasLocationRule = policy.blocked.values.any { it.locationZoneId > 0 } ||
            policy.profileBlocked.values.any { it.locationZoneId > 0 } ||
            policy.profileSiteRules.any { it.rule.locationZoneId > 0 }
        if (!hasLocationRule) return
        scope.launch {
            runCatching {
                LocationHelper.currentFix(
                    this@PureLockAccessibilityService,
                    highAccuracy = false,
                    timeoutMs = 10_000
                )
            }.getOrNull()?.let { policy.lastFix = it }
        }
    }

    /**
     * While a lock is active, a Settings or installer screen that shows this app's name (its App
     * info page, uninstall confirmation) sends the user home before anything can be tapped.
     * "Force stop" on its own is also a match, so the app-info page is caught even before the
     * app name is rendered. VPN / Accessibility / Private DNS settings screens are deliberately
     * NOT blocked — if one of those is switched off, the integrity check warns instead.
     * The emergency override always works.
     */
    private fun guardSettingsScreen() {
        val now = System.currentTimeMillis()
        if (now - lastSettingsScan < 700) return
        lastSettingsScan = now
        val root = rootInActiveWindow ?: return
        val found = root.findAccessibilityNodeInfosByText(getString(R.string.app_name))
        if (!found.isNullOrEmpty()) {
            block(root.packageName?.toString() ?: "", BlockReason.SELF_PROTECT)
            return
        }
        val texts = ArrayList<String>()
        ContentWatch.collectTexts(root, texts)
        if (texts.any { t -> FORCE_STOP_TEXTS.any { t.equals(it, ignoreCase = true) } }) {
            block(root.packageName?.toString() ?: "", BlockReason.SELF_PROTECT)
        }
    }

    /**
     * Power-menu guard (no Device Owner): when the system power dialog is recognised, close it
     * with Back. Partial by nature — the dialog flashes briefly — and the Protection screen
     * says so. Only fires on the global-actions view id or the power menu's own labels, never
     * on the lock screen or notification shade.
     */
    private fun guardPowerMenu() {
        val now = System.currentTimeMillis()
        if (now - lastPowerMenuAt < 1000) return
        val root = rootInActiveWindow ?: return
        if (!looksLikePowerMenu(root)) return
        lastPowerMenuAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        scope.launch {
            runCatching { Graph.eventLog.log(SecurityEventType.POWER_MENU_BLOCKED, "") }
        }
    }

    private fun looksLikePowerMenu(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        stack.addLast(root)
        var nodes = 0
        while (stack.isNotEmpty() && nodes < 200) {
            val node = stack.removeLast()
            nodes++
            if (node.viewIdResourceName?.contains("global_actions") == true) return true
            val text = node.text?.toString()
            if (text != null && POWER_MENU_TEXTS.any { text.equals(it, ignoreCase = true) }) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    /**
     * Vivo / MIUI system screens that can disable or uninstall PureLock (security centre,
     * permission manager). Unlike Settings screens, the package match alone is enough here — the
     * same rule PowerLock uses for these packages — because these screens never show PureLock's
     * name. Fires only while a lock is active and "Uninstall protection" is on.
     */
    private fun isGuardedSystemApp(pkg: String): Boolean =
        pkg.contains("securitycenter") || pkg.contains("permissioncontroller")

    private fun guardSystemApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastGuardAt < 1_500) return
        lastGuardAt = now
        block(pkg, BlockReason.SELF_PROTECT)
    }

    /** Shared 1.5-second de-duplication for every block path, so one screen cannot spam blocks. */
    private fun tryBlock(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPackage && now - lastBlockedAt < 1_500) return false
        lastBlockedPackage = pkg
        lastBlockedAt = now
        return true
    }

    private fun block(pkg: String, reason: BlockReason) {
        if (!tryBlock(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startBlockActivity(pkg, reason, policy.motivationFor(pkg))
        if (reason != BlockReason.SELF_PROTECT) {
            scope.launch { Graph.stats.recordAppBlock() }
        }
    }

    /**
     * Content block (blocked site, keyword hit, distracting feed). Shown like any other block,
     * but it is NOT counted as an app block: a site block is recorded as a website block instead,
     * and keyword/feed blocks are not counted in either total. There is no content-specific
     * [BlockReason] (its enum lives in the read-only [AppBlockPolicy] file), so the block screen
     * shows its generic "blocked" title for these.
     */
    private fun blockContent(pkg: String, recordWebsiteBlock: Boolean, motivation: String? = null) {
        if (!tryBlock(pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startBlockActivity(pkg, BlockReason.LOCK, motivation)
        if (recordWebsiteBlock) {
            scope.launch { Graph.stats.recordWebsiteBlock() }
        }
    }

    private fun startBlockActivity(pkg: String, reason: BlockReason, motivation: String? = null) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_LABEL, label)
                .putExtra(BlockActivity.EXTRA_REASON, reason.name)
                .apply { motivation?.let { putExtra(BlockActivity.EXTRA_MOTIVATION, it) } }
        )
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        recheckHandler.removeCallbacks(recheckRunnable)
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var connected: Boolean = false
            private set

        private const val CONTENT_SCAN_THROTTLE_MS = 2_000L
        private const val RECHECK_INTERVAL_MS = 60_000L
        /** Repeat feature-rule hit inside this window goes Home instead of Back. */
        private const val FEATURE_REPEAT_WINDOW_MS = 30_000L

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller"
        )

        /** Installer packages (subset of [SETTINGS_PACKAGES]) for the APK-install block. */
        private val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller"
        )

        /** Labels of the power menu itself (view-id match is the locale-independent one). */
        private val POWER_MENU_TEXTS = setOf("Power off", "Restart", "Shut down", "Reboot", "Power")

        /** App-info page marker: catching it blocks before Force stop is reachable. */
        private val FORCE_STOP_TEXTS = setOf("Force stop", "Force Stop")
    }
}
