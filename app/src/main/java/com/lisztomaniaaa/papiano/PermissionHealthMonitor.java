package com.lisztomaniaaa.papiano;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
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
 * Continuous permission/system health monitor.
 *
 * Polls every CHECK_INTERVAL_MS (2.5s) on a background thread.
 * When health transitions (healthy → unhealthy), fires callback on main thread.
 * UI components observe via {@link HealthCallback}.
 *
 * Checks:
 *   1. WRITE_SECURE_SETTINGS granted (Root/Shizuku alive)
 *   2. Accessibility Service enabled
 *   3. Daemon binder alive (GamePadBridge.gamePad pingable)
 *   4. MIDI device(s) connected (informational, not blocking)
 */
public class PermissionHealthMonitor {

    private static final String TAG = "HealthMon";
    private static final long CHECK_INTERVAL_MS = 2500;

    public interface HealthCallback {
        /** Called on main thread when health state changes. */
        void onHealthChanged(HealthState state);
    }

    public static class MidiDeviceEntry {
        public final int id;
        public final String name;
        public final int type; // MidiDeviceInfo.TYPE_USB / TYPE_BLUETOOTH / TYPE_VIRTUAL
        public boolean enabled; // user toggle

        public MidiDeviceEntry(int id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
            // Default: hardware ON, virtual OFF
            this.enabled = (type != MidiDeviceInfo.TYPE_VIRTUAL);
        }

        public String getTypeBadge() {
            switch (type) {
                case MidiDeviceInfo.TYPE_USB: return "USB";
                case MidiDeviceInfo.TYPE_BLUETOOTH: return "BT";
                case MidiDeviceInfo.TYPE_VIRTUAL: return "Virtual";
                default: return "?";
            }
        }
    }

    public static class HealthState {
        public final boolean hasPermission;
        public final boolean hasAccessibility;
        public final boolean hasDaemon;
        public final List<MidiDeviceEntry> midiDevices;
        public final String activationMethod; // "shizuku" / "root" / "adb"

        public HealthState(boolean perm, boolean acc, boolean daemon,
                           List<MidiDeviceEntry> devices, String method) {
            this.hasPermission = perm;
            this.hasAccessibility = acc;
            this.hasDaemon = daemon;
            this.midiDevices = devices;
            this.activationMethod = method;
        }

        /** All mandatory conditions met. */
        public boolean isHealthy() {
            return hasPermission && hasAccessibility && hasDaemon;
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
    private volatile HealthState lastState = null;

    // Track user's device enable/disable choices across refreshes
    private final List<MidiDeviceEntry> trackedDevices = new ArrayList<>();

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

    /** Force an immediate check (e.g., after user taps Refresh). */
    public void forceCheck() {
        mainHandler.removeCallbacksAndMessages(null);
        bg.execute(() -> {
            HealthState state = performCheck();
            deliverResult(state);
            if (running) scheduleCheck();
        });
    }

    /** Get last known state without triggering a new check. */
    public HealthState getLastState() {
        return lastState;
    }

    /** Get tracked MIDI device list (preserves user toggles). */
    public List<MidiDeviceEntry> getTrackedDevices() {
        synchronized (trackedDevices) {
            return new ArrayList<>(trackedDevices);
        }
    }

    /** Toggle a device on/off by its ID. */
    public void toggleDevice(int deviceId, boolean enabled) {
        synchronized (trackedDevices) {
            for (MidiDeviceEntry entry : trackedDevices) {
                if (entry.id == deviceId) {
                    entry.enabled = enabled;
                    break;
                }
            }
        }
    }

    public void destroy() {
        stop();
        bg.shutdownNow();
    }

    // ═══════════ INTERNAL ═══════════

    private void scheduleCheck() {
        mainHandler.postDelayed(() -> {
            if (!running) return;
            bg.execute(() -> {
                HealthState state = performCheck();
                deliverResult(state);
                if (running) scheduleCheck();
            });
        }, CHECK_INTERVAL_MS);
    }

    private void deliverResult(HealthState state) {
        boolean changed = (lastState == null)
                || (lastState.isHealthy() != state.isHealthy())
                || (lastState.hasPermission != state.hasPermission)
                || (lastState.hasAccessibility != state.hasAccessibility)
                || (lastState.hasDaemon != state.hasDaemon)
                || (lastState.midiDevices.size() != state.midiDevices.size());
        lastState = state;
        // Always deliver (UI may need to update MIDI list even if health unchanged)
        mainHandler.post(() -> {
            if (callback != null) callback.onHealthChanged(state);
        });
    }

    private HealthState performCheck() {
        boolean perm = checkPermission();
        boolean acc = checkAccessibility();
        boolean daemon = checkDaemon();
        List<MidiDeviceEntry> devices = scanMidiDevices();
        String method = context.getSharedPreferences("data", 0)
                .getString("activation_method", "shizuku");
        return new HealthState(perm, acc, daemon, devices, method);
    }

    private boolean checkPermission() {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkAccessibility() {
        try {
            String svcName = new ComponentName(context.getPackageName(),
                    tuoluoyiService.class.getName()).flattenToString();
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(svcName);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean checkDaemon() {
        IGamePad gp = GamePadBridge.gamePad;
        if (gp == null) return false;
        try {
            return gp.asBinder().pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private List<MidiDeviceEntry> scanMidiDevices() {
        List<MidiDeviceEntry> result = new ArrayList<>();
        try {
            MidiManager mm = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
            if (mm == null) return result;
            MidiDeviceInfo[] devices = mm.getDevices();
            if (devices == null) return result;

            for (MidiDeviceInfo info : devices) {
                if (info.getOutputPortCount() > 0) {
                    String name = info.getProperties()
                            .getString(MidiDeviceInfo.PROPERTY_NAME);
                    if (name == null || name.isEmpty()) name = "Unknown Device";
                    result.add(new MidiDeviceEntry(info.getId(), name, info.getType()));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanMidi", t);
        }

        // Merge with tracked list (preserve user toggles)
        synchronized (trackedDevices) {
            // Remove devices no longer present
            trackedDevices.removeIf(tracked ->
                    result.stream().noneMatch(r -> r.id == tracked.id));
            // Add new devices, preserve existing toggle states
            for (MidiDeviceEntry newDev : result) {
                boolean found = false;
                for (MidiDeviceEntry tracked : trackedDevices) {
                    if (tracked.id == newDev.id) {
                        newDev.enabled = tracked.enabled; // preserve toggle
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    trackedDevices.add(newDev);
                }
            }
            // Update enabled states in result from tracked
            for (MidiDeviceEntry r : result) {
                for (MidiDeviceEntry t : trackedDevices) {
                    if (r.id == t.id) {
                        r.enabled = t.enabled;
                        break;
                    }
                }
            }
        }

        return result;
    }
}
