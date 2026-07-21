package com.panpanpan.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * Every accessibility-enable call site in this app used to just check
 * "does the Settings.Secure string already contain our service name" before
 * deciding whether to write it — MainActivity, PermissionGateActivity, and
 * MidiBridgeService's auto-grant all did this same check independently.
 *
 * That string reflects what Android was TOLD, not what it actually bound.
 * On Android 13+, a sideloaded app's Accessibility Service can be blocked by
 * "Restricted settings" — the write to Settings.Secure still succeeds (it's
 * a raw content-provider write, not gated), but system_server's
 * AccessibilityManagerService silently refuses to actually bind the service.
 * Android only re-evaluates binding on a fresh OFF->ON transition of that
 * string; if our service is already listed (even from an earlier blocked
 * attempt, before the user allowed restricted settings), every one of those
 * call sites sees "already contains it" and never writes again — so a
 * service that's listed-but-never-bound stays that way forever, with no
 * visible error anywhere, which matches a device sitting in "Recovering..."
 * indefinitely despite Shizuku being alive and the daemon files being fine.
 *
 * This checks the REAL bound state via AccessibilityManager and forces a
 * remove-then-add cycle whenever the string and reality disagree.
 */
object AccessibilityGate {
    private const val TAG = "PapianoMidi"

    private fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, MidiBridgeService::class.java.name)

    /** True only if Android actually bound the service, not just "listed in settings". */
    @JvmStatic
    fun isActuallyBound(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val svc = serviceComponent(context)
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si != null && si.packageName == svc.packageName && si.name == svc.className
                }
        } catch (t: Throwable) {
            Log.w(TAG, "isActuallyBound", t)
            false
        }
    }

    /**
     * Ensure the service is both listed AND actually bound. If it's listed
     * but not bound (the restricted-settings trap above), force a fresh
     * OFF->ON transition instead of treating "already listed" as done.
     */
    @JvmStatic
    fun ensureEnabled(context: Context) {
        try {
            val svcName = serviceComponent(context).flattenToString()
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val listed = current.contains(svcName)
            val bound = isActuallyBound(context)

            if (listed && bound) return // genuinely fine, nothing to do

            if (listed && !bound) {
                Log.w(TAG, "Accessibility service listed in settings but NOT " +
                        "actually bound (restricted settings?) — forcing OFF->ON cycle")
                val without = stripService(current, svcName)
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
            }

            val base = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val next = if (base.isEmpty()) svcName else "$svcName:$base"
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        } catch (t: Throwable) {
            Log.e(TAG, "ensureEnabled", t)
        }
    }

    private fun stripService(current: String, svcName: String): String =
        current.split(":").filter { it.isNotEmpty() && it != svcName }.joinToString(":")
}
