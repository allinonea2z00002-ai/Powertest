package com.purelock.app.admin

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.purelock.app.domain.ModuleSnapshot

/**
 * Optional stronger protection, used ONLY when the user has provisioned PureLock as Device Owner
 * themselves (see README). While a lock is active it applies the restrictions for the modules
 * that are on; every restriction is lifted when the lock ends. Without Device Owner every
 * function is a no-op.
 *
 * Power-menu blocking via Lock Task: when the power-menu module is on, the lock task is limited
 * to PureLock, the launcher and the dialer, and the global-actions (power menu) feature is
 * excluded — so the power menu cannot open, the phone cannot be restarted or powered off from
 * it, and safe boot is disallowed. Emergency calls keep working: the dialer stays whitelisted
 * and the emergency override lives inside PureLock itself. The UI must say plainly that this
 * mode limits the phone to Home / Phone / PureLock until the lock ends.
 */
object DevicePolicyHelper {

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) = ComponentName(context, PureLockAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    /** Applies the Device Owner restrictions for the modules that are on. No-op without Device Owner. */
    fun applyLockRestrictions(context: Context, modules: ModuleSnapshot = ModuleSnapshot.ALL_ON) {
        if (!isDeviceOwner(context)) return
        runCatching {
            val policy = dpm(context)
            val admin = admin(context)
            if (modules.uninstall) {
                policy.setUninstallBlocked(admin, context.packageName, true)
                policy.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
            }
            if (modules.appInfo) {
                policy.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
            }
            if (modules.apkInstall) {
                policy.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
                policy.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }
            if (modules.powerMenu) {
                policy.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                policy.setLockTaskPackages(admin, lockTaskPackages(context))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Every lock-task feature EXCEPT global actions (the power menu).
                    policy.setLockTaskFeatures(admin, LOCK_TASK_FEATURES_NO_GLOBAL_ACTIONS)
                }
            }
        }
    }

    /** Lifts every restriction [applyLockRestrictions] may have set. No-op without Device Owner. */
    fun releaseLockRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching {
            val policy = dpm(context)
            val admin = admin(context)
            policy.setUninstallBlocked(admin, context.packageName, false)
            policy.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
            policy.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
            policy.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            policy.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            policy.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            policy.setLockTaskPackages(admin, emptyArray())
        }
    }

    /** Packages allowed while the lock task is active: PureLock, launchers and dialers. */
    fun lockTaskPackages(context: Context): Array<String> {
        val pm = context.packageManager
        val set = hashSetOf(context.packageName, "com.android.systemui")
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        return set.toTypedArray()
    }

    /** True when this activity's task is currently in lock task mode. */
    fun isInLockTaskMode(activity: Activity): Boolean {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * Enters lock task mode for this activity's task. Only call from a resumed activity, and
     * only after [applyLockRestrictions] whitelisted this package (otherwise Android shows a
     * one-time prompt instead of entering silently). Returns false when it could not start.
     */
    fun startLockTask(activity: Activity): Boolean {
        if (isInLockTaskMode(activity)) return true
        return runCatching {
            activity.startLockTask()
            true
        }.getOrDefault(false)
    }

    /** Leaves lock task mode. Only call from a resumed activity. */
    fun stopLockTask(activity: Activity) {
        if (!isInLockTaskMode(activity)) return
        runCatching { activity.stopLockTask() }
    }

    /** The exact command a user runs once from a computer to make PureLock the Device Owner. */
    fun provisioningCommand(context: Context): String =
        "adb shell dpm set-device-owner ${context.packageName}/com.purelock.app.admin.PureLockAdminReceiver"

    private const val LOCK_TASK_FEATURES_NO_GLOBAL_ACTIONS: Int =
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
}
