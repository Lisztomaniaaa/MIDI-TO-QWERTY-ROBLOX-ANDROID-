package com.lisztomaniaaa.papiano

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single place that shells out to the Shizuku/root-spawned daemon process.
 *
 * Before this, respawn logic was duplicated in tuoluoyiService (bridge health
 * loop, tied to the AccessibilityService) AND PermissionHealthMonitor (tied to
 * MainActivity). Both loops could fire respawnDaemon() around the same time
 * with no shared state, stacking multiple app_process/uHID daemon instances
 * on top of each other — a likely cause of intermittent "can't connect".
 * Centralizing here gives both loops one cooldown + in-flight guard.
 */
object DaemonControl {
    private const val TAG = "DaemonControl"
    private const val RESPAWN_COOLDOWN_MS = 8_000L
    private const val DAEMON_CLASS = "com.lisztomaniaaa.papiano.GamePadNative"

    private val respawnInFlight = AtomicBoolean(false)
    @Volatile private var lastRespawnAttemptMs = 0L

    private fun method(context: Context): String =
        context.getSharedPreferences("data", 0)
            .getString("activation_method", "shizuku") ?: "shizuku"

    private fun starterCmd(context: Context): String {
        val extDir = context.getExternalFilesDir(null)
        val base = extDir?.path ?: context.filesDir.path
        return "sh $base/starter.sh"
    }

    /**
     * Fire-and-forget daemon (re)spawn, rate-limited so the service's health
     * loop and the activity's health monitor never stack duplicate daemons.
     * Safe to call as often as callers like — extra calls within the
     * cooldown window are dropped.
     */
    @JvmStatic
    fun respawn(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastRespawnAttemptMs < RESPAWN_COOLDOWN_MS) return
            if (!respawnInFlight.compareAndSet(false, true)) return
            lastRespawnAttemptMs = now
        }
        val appContext = context.applicationContext
        val m = method(appContext)
        val cmd = starterCmd(appContext)
        Thread {
            try { runShell(m, cmd) }
            catch (t: Throwable) { Log.e(TAG, "respawn", t) }
            finally { respawnInFlight.set(false) }
        }.start()
    }

    /**
     * Force-kill the daemon process even if its binder is already dead.
     * closeAndExit() over the binder can't help once the binder itself is
     * gone — without this fallback, the orphaned app_process (spawned by
     * Shizuku/root, outside the app's own process) keeps running forever,
     * holding the virtual keyboard open, until the device reboots.
     */
    @JvmStatic
    fun kill(context: Context) {
        val appContext = context.applicationContext
        val m = method(appContext)
        Thread {
            try { runShell(m, "pkill -f $DAEMON_CLASS") }
            catch (t: Throwable) { Log.e(TAG, "kill", t) }
        }.start()
    }

    /** MUST run off the main thread — blocks on Process.waitFor(). */
    private fun runShell(method: String, cmd: String) {
        when (method) {
            "root" -> {
                val p = Runtime.getRuntime().exec("su")
                p.outputStream.use {
                    it.write("$cmd\nexit\n".toByteArray())
                    it.flush()
                }
                p.waitFor()
            }
            "shizuku" -> {
                if (rikka.shizuku.Shizuku.checkSelfPermission()
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) return
                val p = rikka.shizuku.Shizuku.newProcess(arrayOf("sh"), null, null)
                p.outputStream.use {
                    it.write("$cmd\nexit\n".toByteArray())
                    it.flush()
                }
                p.waitFor()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    p.destroyForcibly()
                } else {
                    @Suppress("DEPRECATION") p.destroy()
                }
            }
            // "adb": user activated manually via adb shell, no channel to re-trigger.
        }
    }
}
