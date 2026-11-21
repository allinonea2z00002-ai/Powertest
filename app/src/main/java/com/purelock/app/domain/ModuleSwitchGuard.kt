package com.purelock.app.domain

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.purelock.app.Graph
import com.purelock.app.data.db.SecurityEventType
import com.purelock.app.data.prefs.ContentFilterStore
import com.purelock.app.data.prefs.SettingsStore

/**
 * The single gate for changing protection while a lock is active.
 *
 * - Turning a module OFF (weakening) during an active lock is refused and logged.
 * - Turning a module ON (strengthening) during an active lock is allowed: the live setting
 *   changes and the lock's own snapshot is strengthened at once (see
 *   [LockManager.strengthenModule]), so the new protection takes effect immediately.
 * - Outside a lock, switches change freely.
 * - Removing a keyword during a lock is refused the same way; adding one is always allowed.
 */
object ModuleSwitchGuard {

    enum class ChangeResult {
        /** The change was applied (and the live lock strengthened, when relevant). */
        APPLIED,
        /** The change would weaken an active lock, so it was refused and logged. */
        REFUSED
    }

    /**
     * Applies a module-switch change through the lock gate. [module] is the
     * [LockManager.strengthenModule] key ("powerMenu", "apkInstall", "uninstall",
     * "appInfo", "appBlock", "featureBlock", "siteBlock", "keywordBlock", "incognito").
     */
    suspend fun tryChangeModule(
        lockManager: LockManager,
        settings: SettingsStore,
        key: Preferences.Key<Boolean>,
        module: String,
        enabled: Boolean
    ): ChangeResult {
        if (lockManager.isActive() && !enabled) {
            Graph.eventLog.log(SecurityEventType.MODULE_WEAKEN_REFUSED, module)
            return ChangeResult.REFUSED
        }
        settings.set(key, enabled)
        if (enabled) lockManager.strengthenModule(module)
        return ChangeResult.APPLIED
    }

    /** Removes a keyword, unless a lock is active (removing would weaken the lock). */
    suspend fun tryRemoveKeyword(
        lockManager: LockManager,
        context: Context,
        keyword: String
    ): Boolean {
        if (lockManager.isActive()) {
            Graph.eventLog.log(SecurityEventType.MODULE_WEAKEN_REFUSED, "keyword")
            return false
        }
        ContentFilterStore.removeKeyword(context, keyword)
        return true
    }
}
