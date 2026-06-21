package com.lisztomaniaaa.papiano;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simplified health monitor — checks activation health continuously.
 *
 * Two states only:
 *   HEALTHY: activation source alive + binder alive
 *   UNHEALTHY: something dropped
 *
 * On health loss: fires callback with reason. NO blocking modal.
 * The callback receiver (MainActivity) shows a soft warning banner
 * and the monitor keeps polling. If conditions recover (user restarts
 * Shizuku, daemon auto-respawns), fires healthy callback → banner dismissed.
 *
 * Auto-recovery: when transitioning UNHEALTHY → HEALTHY, triggers
 * re-activation attempt (spawn daemon if binder is missing but source is alive).
 */
public class PermissionHealthMonitor {

    private static final String TAG = "HealthMon";
    private static final long CHECK_INTERVAL_MS = 3000;

    public enum Status { HEALTHY, UNHEALTHY, RECOVERING }

    public interface HealthCallback {
        void onHealthChanged(Status status, String message, List<MidiDeviceEntry> devices);
    }

    public static class MidiDeviceEntry {
        public final int id;
        public final String name;
        public final int type;
        public boolean enabled;

        public MidiDeviceEntry(int id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.enabled = true; // hardware always on
        }

        public String getTypeBadge() {
            switch (type) {
                case MidiDeviceInfo.TYPE_USB: return "USB";
                case MidiDeviceInfo.TYPE_BLUETOOTH: return "BT";
                default: return "?";
            }
        }
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "health-monitor");
        t.setDaemon(true);
        return t;
    });

    private HealthCallback callback;
    private volatile boolean running = false;
    private volatile Status lastStatus = null;

    public PermissionHealthMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(HealthCallback callback) {
        this.callback = callback;
    }

    public void start() {
        if (running) return;
        running = true;
        scheduleCheck();
    }

    public void stop() {
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
    }

    public void forceCheck() {
        mainHandler.removeCallbacksAndMessages(null);
        bg.execute(this::runCheck);
    }

    public void destroy() {
        stop();
        bg.shutdownNow();
    }

    public Status getLastStatus() {
        return lastStatus;
    }

    // ═══════════ INTERNAL ═══════════

    private void scheduleCheck() {
        mainHandler.postDelayed(() -> {
            if (!running) return;
            bg.execute(this::runCheck);
        }, CHECK_INTERVAL_MS);
    }

    private void runCheck() {
        boolean sourceAlive = checkSourceAlive();
        boolean binderAlive = checkBinderAlive();
        List<MidiDeviceEntry> devices = scanHardwareMidiDevices();

        Status newStatus;
        String message;

        if (sourceAlive && binderAlive) {
            newStatus = Status.HEALTHY;
            message = "Active";
        } else if (sourceAlive && !binderAlive) {
            // Source alive but binder dead — daemon probably crashed, can auto-recover
            newStatus = Status.RECOVERING;
            message = "Daemon disconnected. Auto-recovering...";
            attemptAutoRecovery();
        } else {
            newStatus = Status.UNHEALTHY;
            String method = getMethod();
            message = capitalize(method) + " is not running. Please restart it.";
        }

        final Status fStatus = newStatus;
        final String fMessage = message;
        final List<MidiDeviceEntry> fDevices = devices;

        mainHandler.post(() -> {
            lastStatus = fStatus;
            if (callback != null) {
                callback.onHealthChanged(fStatus, fMessage, fDevices);
            }
        });

        if (running) scheduleCheck();
    }

    private boolean checkSourceAlive() {
        String method = getMethod();
        switch (method) {
            case "shizuku":
                try { return rikka.shizuku.Shizuku.pingBinder(); }
                catch (Throwable t) { return false; }
            case "root":
                try {
                    Process p = Runtime.getRuntime().exec("su -c id");
                    return p.waitFor() == 0;
                } catch (Throwable t) { return false; }
            default:
                return false;
        }
    }

    private boolean checkBinderAlive() {
        IGamePad gp = GamePadBridge.gamePad;
        if (gp == null) return false;
        try { return gp.asBinder().pingBinder(); }
        catch (Throwable t) { return false; }
    }

    /**
     * Scan MIDI devices — ONLY hardware (USB/BT). Virtual devices filtered out.
     */
    private List<MidiDeviceEntry> scanHardwareMidiDevices() {
        List<MidiDeviceEntry> result = new ArrayList<>();
        try {
            MidiManager mm = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
            if (mm == null) return result;
            MidiDeviceInfo[] devices = mm.getDevices();
            if (devices == null) return result;

            for (MidiDeviceInfo info : devices) {
                // Skip virtual devices entirely
                if (info.getType() == MidiDeviceInfo.TYPE_VIRTUAL) continue;
                if (info.getOutputPortCount() <= 0) continue;

                String name = info.getProperties()
                        .getString(MidiDeviceInfo.PROPERTY_NAME);
                if (name == null || name.isEmpty()) name = "Unknown Device";
                result.add(new MidiDeviceEntry(info.getId(), name, info.getType()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanMidi", t);
        }
        return result;
    }

    /**
     * Auto-recovery: re-spawn daemon if source is alive but binder is dead.
     * Non-blocking — fires and forgets, next health check will verify.
     */
    private void attemptAutoRecovery() {
        String method = getMethod();
        java.io.File extDir = context.getExternalFilesDir(null);
        String base = extDir != null ? extDir.getPath() : context.getFilesDir().getPath();
        String cmd = "sh " + base + "/starter.sh";

        try {
            switch (method) {
                case "shizuku": {
                    if (rikka.shizuku.Shizuku.checkSelfPermission()
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
                    Process p = rikka.shizuku.Shizuku.newProcess(
                            new String[]{"sh", "-c", cmd}, null, null);
                    p.waitFor();
                    break;
                }
                case "root": {
                    Process p = Runtime.getRuntime().exec("su");
                    java.io.OutputStream o = p.getOutputStream();
                    o.write((cmd + "\nexit\n").getBytes());
                    o.flush();
                    o.close();
                    p.waitFor();
                    break;
                }
            }
            Log.d(TAG, "Auto-recovery: daemon respawn attempted via " + method);
        } catch (Throwable t) {
            Log.w(TAG, "autoRecovery failed", t);
        }
    }

    private String getMethod() {
        return context.getSharedPreferences("data", 0)
                .getString("activation_method", "shizuku");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
