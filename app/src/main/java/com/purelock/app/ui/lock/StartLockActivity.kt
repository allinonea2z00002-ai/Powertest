package com.purelock.app.ui.lock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.admin.DevicePolicyHelper
import com.purelock.app.data.db.SafeZone
import com.purelock.app.databinding.ActivityStartLockBinding
import com.purelock.app.domain.CombineRule
import com.purelock.app.domain.LocationKind
import com.purelock.app.domain.LockEvaluator
import com.purelock.app.domain.LockManager
import com.purelock.app.domain.LockMode
import com.purelock.app.domain.ModuleSnapshot
import com.purelock.app.location.LocationHelper
import com.purelock.app.service.image.ImageClassifier
import com.purelock.app.service.image.ImageDetectionController
import com.purelock.app.service.vpn.DnsVpnController
import com.purelock.app.ui.ThemeManager
import com.purelock.app.ui.location.MapActivity
import com.purelock.app.util.DurationFormat
import kotlinx.coroutines.launch

/**
 * The unified lock, start to finish.
 *
 * No lock active: the wizard — duration (30 min, 1/3/6/12/24 h, custom), location
 * (none, travel X km, arrive at a saved zone), ANY/ALL when both are set, the eight
 * module switches (snapshotted into the lock), incognito, DNS VPN and the
 * experimental image detection, plus the emergency override word (required the
 * first time). Consents the lock needs (location permission, VPN, MediaProjection)
 * are asked in order before the lock starts.
 *
 * Lock active: remaining time (ticking), the unlock conditions, and the emergency
 * override — typing the word breaks the lock and is always logged.
 */
class StartLockActivity : AppCompatActivity() {

    private lateinit var b: ActivityStartLockBinding
    private var zones: List<SafeZone> = emptyList()
    private var startPending = false
    private var ticker: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (startPending) continueStart()
        }

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Proceed either way: without consent the lock still starts, site
            // blocking falls back to the URL watch and Home warns about the VPN.
            if (startPending) continueStart()
        }

    private val projectionConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ImageDetectionController.holdConsent(result.resultCode, result.data!!)
            }
            if (startPending) continueStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityStartLockBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }

        b.chipGroupDuration.setOnCheckedStateChangeListener { _, _ -> updateSetupVisibility() }
        b.rgLocation.setOnCheckedChangeListener { _, _ -> updateSetupVisibility() }
        b.swModulePowerMenu.setOnCheckedChangeListener { _, _ -> updateSetupVisibility() }
        b.swImageDetection.setOnCheckedChangeListener { _, _ -> updateSetupVisibility() }
        b.btnManageZones.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        b.btnStart.setOnClickListener { onStartClicked() }
        b.btnOverride.setOnClickListener { onOverrideClicked() }

        lifecycleScope.launch {
            val settings = Graph.settings.get()
            b.swModulePowerMenu.isChecked = settings.modulePowerMenu
            b.swModuleApkInstall.isChecked = settings.moduleApkInstall
            b.swModuleUninstall.isChecked = settings.moduleUninstall
            b.swModuleAppInfo.isChecked = settings.moduleAppInfo
            b.swModuleAppBlock.isChecked = settings.moduleAppBlock
            b.swModuleFeatureBlock.isChecked = settings.moduleFeatureBlock
            b.swModuleSiteBlock.isChecked = settings.moduleSiteBlock
            b.swModuleKeywordBlock.isChecked = settings.moduleKeywordBlock
            b.swBlockIncognito.isChecked = settings.blockIncognito
            b.swDnsVpn.isChecked = settings.dnsVpnEnabled
            b.swImageDetection.isChecked = settings.imageDetection
            when (settings.imageSensitivity) {
                1 -> b.rbSensLow.isChecked = true
                3 -> b.rbSensHigh.isChecked = true
                else -> b.rbSensMedium.isChecked = true
            }
            zones = Graph.db.safeZoneDao().getAll()
            val adapter = ArrayAdapter(
                this@StartLockActivity,
                android.R.layout.simple_spinner_item,
                zones.map { it.name }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            b.spinnerZone.adapter = adapter
            updateSetupVisibility()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val cfg = Graph.lockManager.current()
            if (cfg?.active == true) showActive() else showSetup()
        }
    }

    override fun onPause() {
        super.onPause()
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    // ------------------------------------------------------------------ setup

    private fun showSetup() {
        b.tvTitle.setText(R.string.start_lock_title)
        b.layoutSetup.visibility = View.VISIBLE
        b.layoutActive.visibility = View.GONE
        // The override word is set once; after that the field is hidden (it can be
        // changed from the Protection screen, never during a lock).
        val hasWord = Graph.secure.hasOverrideWord()
        b.layoutOverrideWord.isVisible = !hasWord
    }

    private fun updateSetupVisibility() {
        val customDuration = b.chipDurCustom.isChecked
        b.layoutCustomDuration.isVisible = customDuration
        val distance = b.rbLocDistance.isChecked
        val zone = b.rbLocZone.isChecked
        b.layoutDistance.isVisible = distance
        b.layoutZone.isVisible = zone
        b.tvNoZones.isVisible = zone && zones.isEmpty()
        b.spinnerZone.isVisible = zone && zones.isNotEmpty()
        // ANY/ALL only matters when both a time and a location condition exist.
        val hasTime = b.chipGroupDuration.checkedChipId != View.NO_ID
        b.layoutRule.isVisible = hasTime && (distance || zone)
        // Power-menu note: without Device Owner the accessibility path is partial.
        b.tvPowerMenuNote.isVisible = b.swModulePowerMenu.isChecked &&
            !DevicePolicyHelper.isDeviceOwner(this)
        val imageOn = b.swImageDetection.isChecked
        val modelExists = ImageClassifier.modelExists(this)
        b.tvImageNote.isVisible = imageOn
        b.tvModelMissing.isVisible = imageOn && !modelExists
        b.rgSensitivity.isVisible = imageOn && modelExists
    }

    private fun selectedDurationMs(): Long = when (b.chipGroupDuration.checkedChipId) {
        R.id.chipDur30 -> 30L * 60_000L
        R.id.chipDur1h -> 3_600_000L
        R.id.chipDur3h -> 3L * 3_600_000L
        R.id.chipDur6h -> 6L * 3_600_000L
        R.id.chipDur12h -> 12L * 3_600_000L
        R.id.chipDur24h -> 24L * 3_600_000L
        R.id.chipDurCustom -> {
            val hours = b.etCustomHours.text?.toString()?.toLongOrNull() ?: 0L
            val minutes = b.etCustomMinutes.text?.toString()?.toLongOrNull() ?: 0L
            (hours * 60 + minutes) * 60_000L
        }
        else -> 0L
    }

    private fun selectedDistanceM(): Int {
        val km = b.etDistanceKm.text?.toString()?.toDoubleOrNull() ?: 0.0
        return (km * 1000).toInt()
    }

    private fun selectedModules() = ModuleSnapshot(
        powerMenu = b.swModulePowerMenu.isChecked,
        apkInstall = b.swModuleApkInstall.isChecked,
        uninstall = b.swModuleUninstall.isChecked,
        appInfo = b.swModuleAppInfo.isChecked,
        appBlock = b.swModuleAppBlock.isChecked,
        featureBlock = b.swModuleFeatureBlock.isChecked,
        siteBlock = b.swModuleSiteBlock.isChecked,
        keywordBlock = b.swModuleKeywordBlock.isChecked,
        blockIncognito = b.swBlockIncognito.isChecked
    )

    private fun selectedSensitivity(): Int = when {
        !b.swImageDetection.isChecked -> 0
        !ImageClassifier.modelExists(this) -> 0
        b.rbSensLow.isChecked -> 1
        b.rbSensHigh.isChecked -> 3
        else -> 2
    }

    private fun onStartClicked() {
        val durationMs = selectedDurationMs()
        val hasTime = durationMs > 0
        val hasLocation = b.rbLocDistance.isChecked || b.rbLocZone.isChecked
        when {
            !hasTime && !hasLocation -> {
                toast(R.string.start_lock_needs_condition)
                return
            }
            hasTime && !LockEvaluator.isValidCustomMinutes(durationMs / 60_000L) -> {
                toast(R.string.start_lock_bad_duration)
                return
            }
            b.rbLocDistance.isChecked && !LockEvaluator.isValidDistance(selectedDistanceM()) -> {
                toast(R.string.start_lock_bad_distance)
                return
            }
            b.rbLocZone.isChecked && zones.isEmpty() -> {
                toast(R.string.start_lock_no_zones)
                return
            }
            !Graph.secure.hasOverrideWord() &&
                (b.etOverrideWord.text?.toString().orEmpty().trim().length < 4) -> {
                toast(R.string.start_lock_word_short)
                return
            }
        }
        startPending = true
        continueStart()
    }

    /**
     * Walks the prerequisites in order, launching a consent flow when one is missing;
     * each launcher's result comes back here until everything is ready, then starts.
     */
    private fun continueStart() {
        // 1. Override word (first lock only).
        if (!Graph.secure.hasOverrideWord()) {
            val word = b.etOverrideWord.text?.toString().orEmpty().trim()
            if (word.length < 4) {
                startPending = false
                toast(R.string.start_lock_word_short)
                return
            }
            Graph.secure.setOverrideWord(word)
        }
        val hasLocation = b.rbLocDistance.isChecked || b.rbLocZone.isChecked
        // 2. Location permission, when a location condition is used.
        if (hasLocation && !LocationHelper.hasForegroundPermission(this)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            )
            return
        }
        if (hasLocation && !LocationHelper.hasForegroundPermission(this)) {
            // Permission denied: a location lock cannot start without a fix.
            startPending = false
            toast(R.string.start_lock_location_denied)
            return
        }
        // 3. VPN consent, when site blocking rides the DNS VPN.
        val modules = selectedModules()
        if (modules.siteBlock && b.swDnsVpn.isChecked && !DnsVpnController.hasConsent(this)) {
            val intent = DnsVpnController.prepareIntent(this)
            if (intent != null) {
                vpnConsentLauncher.launch(intent)
                return
            }
        }
        // 4. MediaProjection consent, when image detection is on and a model exists.
        if (selectedSensitivity() > 0 && ImageDetectionController.consent == null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionConsentLauncher.launch(manager.createScreenCaptureIntent())
            return
        }
        startPending = false
        doStart()
    }

    private fun doStart() {
        lifecycleScope.launch {
            val durationMs = selectedDurationMs()
            val hasTime = durationMs > 0
            val distanceKind = b.rbLocDistance.isChecked
            val zoneKind = b.rbLocZone.isChecked
            val hasLocation = distanceKind || zoneKind
            val mode = when {
                hasTime && hasLocation -> LockMode.COMBINED
                hasTime -> LockMode.TIME
                else -> LockMode.DISTANCE
            }
            val rule = if (b.rbRuleAll.isChecked) CombineRule.ALL else CombineRule.ANY
            val modules = selectedModules()
            val sensitivity = selectedSensitivity()

            var startLat: Double? = null
            var startLng: Double? = null
            if (hasLocation) {
                val fix = LocationHelper.currentFix(this@StartLockActivity, highAccuracy = true)
                if (fix == null) {
                    toast(R.string.start_lock_no_fix)
                    return@launch
                }
                startLat = fix.latitude
                startLng = fix.longitude
            }

            // The wizard's switches become the stored defaults for the next lock.
            val settings = Graph.settings
            val keys = com.purelock.app.data.prefs.SettingsKeys
            settings.set(keys.MODULE_POWER_MENU, modules.powerMenu)
            settings.set(keys.MODULE_APK_INSTALL, modules.apkInstall)
            settings.set(keys.MODULE_UNINSTALL, modules.uninstall)
            settings.set(keys.MODULE_APP_INFO, modules.appInfo)
            settings.set(keys.MODULE_APP_BLOCK, modules.appBlock)
            settings.set(keys.MODULE_FEATURE_BLOCK, modules.featureBlock)
            settings.set(keys.MODULE_SITE_BLOCK, modules.siteBlock)
            settings.set(keys.MODULE_KEYWORD_BLOCK, modules.keywordBlock)
            settings.set(keys.BLOCK_INCOGNITO, modules.blockIncognito)
            settings.set(keys.DNS_VPN_ENABLED, b.swDnsVpn.isChecked)
            settings.set(keys.IMAGE_DETECTION, b.swImageDetection.isChecked)
            settings.set(keys.IMAGE_SENSITIVITY, if (sensitivity == 0) 2 else sensitivity)

            val zoneId = if (zoneKind) {
                zones.getOrNull(b.spinnerZone.selectedItemPosition)?.id ?: 0L
            } else 0L
            val result = Graph.lockManager.start(
                LockManager.StartRequest(
                    mode = mode,
                    durationMs = durationMs,
                    distanceM = if (distanceKind) selectedDistanceM() else 0,
                    rule = rule,
                    startLat = startLat,
                    startLng = startLng,
                    blockAdult = modules.siteBlock,
                    blockApps = modules.appBlock,
                    locationKind = if (zoneKind) LocationKind.ZONE else LocationKind.DISTANCE,
                    unlockZoneId = zoneId,
                    modules = modules,
                    blockIncognito = modules.blockIncognito,
                    imageSensitivity = sensitivity
                )
            )
            when (result) {
                LockManager.StartResult.Started -> {
                    if (sensitivity > 0) {
                        ImageDetectionController.setWanted(this@StartLockActivity, true)
                        ImageDetectionController.startIfWanted(this@StartLockActivity, sensitivity)
                    }
                    // Device Owner power-menu block: pin the task now, while resumed.
                    if (modules.powerMenu && DevicePolicyHelper.isDeviceOwner(this@StartLockActivity)) {
                        DevicePolicyHelper.startLockTask(this@StartLockActivity)
                    }
                    toast(R.string.start_lock_started)
                    finish()
                }
                LockManager.StartResult.AlreadyActive -> toast(R.string.start_lock_already_active)
                LockManager.StartResult.InvalidDuration -> toast(R.string.start_lock_bad_duration)
                LockManager.StartResult.InvalidDistance -> toast(R.string.start_lock_bad_distance)
                LockManager.StartResult.NeedsStartLocation -> toast(R.string.start_lock_no_fix)
                LockManager.StartResult.NeedsUnlockZone -> toast(R.string.start_lock_no_zones)
            }
        }
    }

    // ------------------------------------------------------------------ active

    private fun showActive() {
        b.tvTitle.setText(R.string.start_lock_active_title)
        b.layoutSetup.visibility = View.GONE
        b.layoutActive.visibility = View.VISIBLE
        val tick = object : Runnable {
            override fun run() {
                lifecycleScope.launch { refreshActive() }
                handler.postDelayed(this, 1000L)
            }
        }
        ticker = tick
        handler.post(tick)
    }

    private suspend fun refreshActive() {
        val cfg = Graph.lockManager.current()
        if (cfg?.active != true) {
            showSetup()
            return
        }
        val snapshot = LockEvaluator.snapshot(
            cfg,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis(),
            Graph.lockManager.bootCount()
        )
        val mode = LockMode.fromName(cfg.mode)
        b.tvActiveRemaining.text = if (mode.usesTime) {
            DurationFormat.formatHrsMins(snapshot.remainingMs)
        } else {
            getString(R.string.start_lock_active_location_only)
        }
        val conditions = buildString {
            if (mode.usesTime) append(getString(R.string.start_lock_cond_time)).append("\n")
            when (snapshot.locationKind) {
                LocationKind.ZONE -> {
                    val zone = zones.firstOrNull { it.id == snapshot.unlockZoneId }
                        ?: Graph.db.safeZoneDao().get(snapshot.unlockZoneId)
                    append(getString(R.string.start_lock_cond_zone, zone?.name ?: ""))
                }
                LocationKind.DISTANCE -> if (mode.usesDistance) {
                    append(
                        getString(
                            R.string.start_lock_cond_distance,
                            snapshot.distanceLimitM / 1000f
                        )
                    )
                }
            }
            if (mode == LockMode.COMBINED) {
                append("\n").append(
                    getString(
                        if (snapshot.rule == CombineRule.ALL) R.string.start_lock_rule_all
                        else R.string.start_lock_rule_any
                    )
                )
            }
        }.trim()
        b.tvActiveConditions.text = conditions
    }

    private fun onOverrideClicked() {
        val word = b.etOverride.text?.toString().orEmpty()
        if (!Graph.secure.overrideWordMatches(word)) {
            toast(R.string.start_lock_override_wrong)
            b.etOverride.text?.clear()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.start_lock_override_title)
            .setMessage(R.string.start_lock_override_confirm)
            .setPositiveButton(R.string.start_lock_override_button) { _, _ ->
                lifecycleScope.launch {
                    Graph.lockManager.emergencyOverride()
                    toast(R.string.start_lock_override_done)
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }
}
