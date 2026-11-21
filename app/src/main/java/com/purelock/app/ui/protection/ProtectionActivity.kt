package com.purelock.app.ui.protection

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.admin.DevicePolicyHelper
import com.purelock.app.data.content.FeatureRulePack
import com.purelock.app.data.prefs.SettingsKeys
import com.purelock.app.databinding.ActivityProtectionBinding
import com.purelock.app.domain.ModuleSnapshot
import com.purelock.app.domain.ModuleSwitchGuard
import com.purelock.app.service.image.ImageClassifier
import com.purelock.app.service.vpn.DnsVpnController
import com.purelock.app.ui.ThemeManager
import kotlinx.coroutines.launch

/**
 * Protection switches: the eight unified-lock modules, incognito, the DNS VPN and the
 * experimental image detection, plus Device Owner status, feature-rule status and the
 * live integrity status.
 *
 * Every switch goes through [ModuleSwitchGuard]: while a lock is active a switch can
 * only be turned on (strengthening), never off. While a lock is active the switches
 * show the lock's own snapshot, not the live settings — the snapshot is what is
 * enforced.
 */
class ProtectionActivity : AppCompatActivity() {

    private lateinit var b: ActivityProtectionBinding

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnNow()
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityProtectionBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }

        guardedSwitch(b.swModulePowerMenu, SettingsKeys.MODULE_POWER_MENU, "powerMenu")
        guardedSwitch(b.swModuleApkInstall, SettingsKeys.MODULE_APK_INSTALL, "apkInstall")
        guardedSwitch(b.swModuleUninstall, SettingsKeys.MODULE_UNINSTALL, "uninstall")
        guardedSwitch(b.swModuleAppInfo, SettingsKeys.MODULE_APP_INFO, "appInfo")
        guardedSwitch(b.swModuleAppBlock, SettingsKeys.MODULE_APP_BLOCK, "appBlock")
        guardedSwitch(b.swModuleFeatureBlock, SettingsKeys.MODULE_FEATURE_BLOCK, "featureBlock")
        guardedSwitch(b.swModuleSiteBlock, SettingsKeys.MODULE_SITE_BLOCK, "siteBlock")
        guardedSwitch(b.swModuleKeywordBlock, SettingsKeys.MODULE_KEYWORD_BLOCK, "keywordBlock")
        guardedSwitch(b.swBlockIncognito, SettingsKeys.BLOCK_INCOGNITO, "incognito")
        // The DNS VPN switch rides the site-block module: enabling it during a lock
        // strengthens site blocking (and starts the VPN); disabling is refused.
        guardedSwitch(b.swDnsVpn, SettingsKeys.DNS_VPN_ENABLED, "siteBlock") {
            if (b.swDnsVpn.isChecked) startVpnNow() else DnsVpnController.stop(this)
        }
        guardedSwitch(b.swImageDetection, SettingsKeys.IMAGE_DETECTION, "imageDetection") {
            updateImageVisibility()
        }
        b.rgSensitivity.setOnCheckedChangeListener { _, _ ->
            lifecycleScope.launch {
                Graph.settings.set(SettingsKeys.IMAGE_SENSITIVITY, selectedSensitivity())
            }
        }
        b.btnStartVpn.setOnClickListener { onStartVpnClicked() }
        b.btnChangeWord.setOnClickListener { onChangeWordClicked() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val settings = Graph.settings.get()
            val cfg = Graph.lockManager.current()
            val lockActive = cfg?.active == true
            // Effective modules: the lock snapshot while active, live settings otherwise.
            val modules = if (lockActive && cfg != null) ModuleSnapshot.fromLock(cfg)
            else ModuleSnapshot.fromSettings(settings)
            b.tvModulesLockedNote.isVisible = lockActive
            b.swModulePowerMenu.isChecked = modules.powerMenu
            b.swModuleApkInstall.isChecked = modules.apkInstall
            b.swModuleUninstall.isChecked = modules.uninstall
            b.swModuleAppInfo.isChecked = modules.appInfo
            b.swModuleAppBlock.isChecked = modules.appBlock
            b.swModuleFeatureBlock.isChecked = modules.featureBlock
            b.swModuleSiteBlock.isChecked = modules.siteBlock
            b.swModuleKeywordBlock.isChecked = modules.keywordBlock
            b.swBlockIncognito.isChecked = modules.blockIncognito
            b.swDnsVpn.isChecked = settings.dnsVpnEnabled
            b.swImageDetection.isChecked = settings.imageDetection
            when (settings.imageSensitivity) {
                1 -> b.rbSensLow.isChecked = true
                3 -> b.rbSensHigh.isChecked = true
                else -> b.rbSensMedium.isChecked = true
            }
            updateImageVisibility()

            val deviceOwner = DevicePolicyHelper.isDeviceOwner(this@ProtectionActivity)
            b.tvDeviceOwnerStatus.setText(
                if (deviceOwner) R.string.protection_device_owner_on
                else R.string.protection_device_owner_off
            )
            b.tvProvisionCommand.isVisible = !deviceOwner
            if (!deviceOwner) {
                b.tvProvisionCommand.text =
                    DevicePolicyHelper.provisioningCommand(this@ProtectionActivity)
            }

            val vpnRunning = DnsVpnController.isRunning(this@ProtectionActivity)
            b.tvVpnStatus.setText(
                if (vpnRunning) R.string.protection_vpn_running
                else R.string.protection_vpn_stopped
            )

            val rules = FeatureRulePack.load(this@ProtectionActivity)
            b.tvRulesStatus.text = if (rules.isEmpty() &&
                FeatureRulePack.loadError(this@ProtectionActivity)
            ) {
                getString(R.string.protection_rules_broken)
            } else {
                getString(R.string.protection_rules_loaded, rules.size)
            }

            val report = runCatching { Graph.integrity.check() }.getOrNull()
            b.tvIntegrityStatus.text = when {
                report == null -> getString(R.string.protection_status_unknown)
                report.issues.isEmpty() -> getString(R.string.protection_status_ok)
                else -> report.issues.joinToString("\n") { "• " + Graph.integrity.issueText(it) }
            }
        }
    }

    private fun updateImageVisibility() {
        val imageOn = b.swImageDetection.isChecked
        val modelExists = ImageClassifier.modelExists(this)
        b.tvImageNote.isVisible = imageOn
        b.tvModelMissing.isVisible = imageOn && !modelExists
        b.rgSensitivity.isVisible = imageOn && modelExists
    }

    private fun selectedSensitivity(): Int = when {
        b.rbSensLow.isChecked -> 1
        b.rbSensHigh.isChecked -> 3
        else -> 2
    }

    /**
     * Wires a switch through the lock gate. Click listeners (not checked-change) so
     * programmatic updates from [refresh] never write back. On refusal the switch
     * flips back and the user is told why.
     */
    private fun guardedSwitch(
        switch: MaterialSwitch,
        key: Preferences.Key<Boolean>,
        module: String,
        onApplied: (() -> Unit)? = null
    ) {
        switch.setOnClickListener {
            val enabled = switch.isChecked
            lifecycleScope.launch {
                val result = ModuleSwitchGuard.tryChangeModule(
                    Graph.lockManager, Graph.settings, key, module, enabled
                )
                if (result == ModuleSwitchGuard.ChangeResult.REFUSED) {
                    switch.isChecked = !enabled
                    Toast.makeText(
                        this@ProtectionActivity,
                        R.string.protection_refused,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    onApplied?.invoke()
                }
            }
        }
    }

    private fun onStartVpnClicked() {
        if (!DnsVpnController.hasConsent(this)) {
            val intent = DnsVpnController.prepareIntent(this)
            if (intent != null) {
                vpnConsentLauncher.launch(intent)
                return
            }
        }
        startVpnNow()
        refresh()
    }

    private fun startVpnNow() {
        DnsVpnController.setWanted(this, true)
        lifecycleScope.launch {
            Graph.settings.set(SettingsKeys.DNS_VPN_ENABLED, true)
        }
        DnsVpnController.start(this)
    }

    private fun onChangeWordClicked() {
        lifecycleScope.launch {
            if (Graph.lockManager.isActive()) {
                Toast.makeText(
                    this@ProtectionActivity,
                    R.string.protection_word_locked,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val input = EditText(this@ProtectionActivity).apply {
                hint = getString(R.string.start_lock_override_hint)
                setSingleLine(true)
            }
            AlertDialog.Builder(this@ProtectionActivity)
                .setTitle(R.string.protection_word_change)
                .setMessage(R.string.start_lock_override_desc)
                .setView(input)
                .setPositiveButton(R.string.action_save) { _, _ ->
                    val word = input.text?.toString().orEmpty().trim()
                    if (word.length < 4) {
                        Toast.makeText(
                            this@ProtectionActivity,
                            R.string.start_lock_word_short,
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Graph.secure.setOverrideWord(word)
                        Toast.makeText(
                            this@ProtectionActivity,
                            R.string.protection_word_changed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }
}
