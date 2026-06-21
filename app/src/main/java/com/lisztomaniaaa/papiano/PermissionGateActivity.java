package com.lisztomaniaaa.papiano;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Permission Gate — blocks entry to Home until ALL preconditions are met.
 *
 * Checks (auto-retry every 2s):
 *   1. Root/Shizuku permission (WRITE_SECURE_SETTINGS granted)
 *   2. Accessibility Service enabled
 *   3. Daemon bridge alive (binder received + pingable)
 *   4. MIDI device detected (optional — warns but doesn't block)
 *
 * If all mandatory checks pass → Continue button appears → navigate to Home.
 * If any fail → show actionable "Fix" buttons where possible.
 *
 * This screen is NOT skippable. The only way past is having all conditions met.
 */
public class PermissionGateActivity extends Activity {

    private static final String TAG = "PermGate";
    private static final long CHECK_INTERVAL_MS = 2000;

    private TextView checkPermIcon, checkPermStatus;
    private TextView checkAccIcon, checkAccStatus;
    private TextView checkDaemonIcon, checkDaemonStatus;
    private TextView checkMidiIcon, checkMidiStatus;
    private TextView tvGateMessage;
    private Button btnFixPermission, btnFixAccessibility;
    private Button btnRetry, btnContinue;

    private SharedPreferences sp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "perm-gate-bg");
        t.setDaemon(true);
        return t;
    });

    // Cached states
    private volatile boolean hasPermission = false;
    private volatile boolean hasAccessibility = false;
    private volatile boolean hasDaemon = false;
    private volatile boolean hasMidi = false;
    private volatile String midiDeviceName = "";

    private boolean receiverRegistered = false;

    private final BroadcastReceiver binderReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("intent.tuoluoyi.sendBinder".equals(intent.getAction())) {
                // Daemon just sent its binder — trigger immediate recheck
                handler.removeCallbacks(checkRunnable);
                handler.post(checkRunnable);
            }
        }
    };

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            bg.execute(() -> {
                performChecks();
                handler.post(() -> updateUI());
            });
            // Schedule next check
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_gate);

        sp = getSharedPreferences("data", 0);

        bindViews();
        setupListeners();
        registerBroadcast();

        // Start checking immediately
        handler.post(checkRunnable);
    }

    private void bindViews() {
        checkPermIcon = findViewById(R.id.check_permission_icon);
        checkPermStatus = findViewById(R.id.check_permission_status);
        checkAccIcon = findViewById(R.id.check_accessibility_icon);
        checkAccStatus = findViewById(R.id.check_accessibility_status);
        checkDaemonIcon = findViewById(R.id.check_daemon_icon);
        checkDaemonStatus = findViewById(R.id.check_daemon_status);
        checkMidiIcon = findViewById(R.id.check_midi_icon);
        checkMidiStatus = findViewById(R.id.check_midi_status);
        tvGateMessage = findViewById(R.id.tv_gate_message);
        btnFixPermission = findViewById(R.id.btn_fix_permission);
        btnFixAccessibility = findViewById(R.id.btn_fix_accessibility);
        btnRetry = findViewById(R.id.btn_retry);
        btnContinue = findViewById(R.id.btn_continue);
    }

    private void setupListeners() {
        btnFixPermission.setOnClickListener(v -> showActivationDialog());

        btnFixAccessibility.setOnClickListener(v -> {
            // Try to enable via WRITE_SECURE_SETTINGS (if we have it)
            if (hasPermission) {
                bg.execute(() -> {
                    enableAccessibilityService();
                    handler.post(() -> {
                        handler.removeCallbacks(checkRunnable);
                        handler.post(checkRunnable);
                    });
                });
            } else {
                BrutalPopup.toast(this, "Fix permission first.", BrutalPopup.LENGTH_SHORT);
            }
        });

        btnRetry.setOnClickListener(v -> {
            handler.removeCallbacks(checkRunnable);
            handler.post(checkRunnable);
        });

        btnContinue.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        });
    }

    private void registerBroadcast() {
        try {
            IntentFilter filter = new IntentFilter("intent.tuoluoyi.sendBinder");
            ContextCompat.registerReceiver(this, binderReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver", t);
        }
    }

    // ═══════════ BACKGROUND CHECKS (run on bg thread) ═══════════

    private void performChecks() {
        hasPermission = checkPermission();
        hasAccessibility = checkAccessibility();
        hasDaemon = checkDaemon();
        hasMidi = checkMidiDevice();
    }

    private boolean checkPermission() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkAccessibility() {
        try {
            String svcName = new ComponentName(getPackageName(),
                    tuoluoyiService.class.getName()).flattenToString();
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
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

    private boolean checkMidiDevice() {
        try {
            MidiManager mm = (MidiManager) getSystemService(MIDI_SERVICE);
            if (mm == null) return false;
            MidiDeviceInfo[] devices = mm.getDevices();
            if (devices == null) return false;
            for (MidiDeviceInfo info : devices) {
                if (info.getOutputPortCount() > 0) {
                    String name = info.getProperties()
                            .getString(MidiDeviceInfo.PROPERTY_NAME);
                    midiDeviceName = (name != null) ? name : "Unknown Device";
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "checkMidi", t);
        }
        midiDeviceName = "";
        return false;
    }

    // ═══════════ UI UPDATE (main thread) ═══════════

    private void updateUI() {
        // Permission
        setCheckState(checkPermIcon, checkPermStatus,
                hasPermission,
                hasPermission ? "Granted (" + sp.getString("activation_method", "shizuku") + ")"
                              : "Not granted");
        btnFixPermission.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        // Accessibility
        setCheckState(checkAccIcon, checkAccStatus,
                hasAccessibility,
                hasAccessibility ? "Enabled" : "Disabled");
        btnFixAccessibility.setVisibility(hasAccessibility ? View.GONE : View.VISIBLE);

        // Daemon
        setCheckState(checkDaemonIcon, checkDaemonStatus,
                hasDaemon,
                hasDaemon ? "Connected" : "Waiting for daemon...");

        // MIDI (optional — warn but don't block)
        if (hasMidi) {
            setCheckState(checkMidiIcon, checkMidiStatus, true, midiDeviceName);
        } else {
            checkMidiIcon.setText("!");
            checkMidiIcon.setTextColor(ContextCompat.getColor(this, R.color.accent_gold));
            checkMidiStatus.setText("No MIDI device (optional)");
            checkMidiStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }

        // Overall state — mandatory: permission + accessibility + daemon
        boolean allMandatoryPassed = hasPermission && hasAccessibility && hasDaemon;

        if (allMandatoryPassed) {
            tvGateMessage.setText("All systems ready!");
            tvGateMessage.setTextColor(ContextCompat.getColor(this, R.color.green));
            btnContinue.setVisibility(View.VISIBLE);
            btnRetry.setVisibility(View.GONE);
        } else {
            int failCount = 0;
            if (!hasPermission) failCount++;
            if (!hasAccessibility) failCount++;
            if (!hasDaemon) failCount++;
            tvGateMessage.setText(failCount + " condition(s) not met. Auto-retrying...");
            tvGateMessage.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            btnContinue.setVisibility(View.GONE);
            btnRetry.setVisibility(View.VISIBLE);
        }
    }

    private void setCheckState(TextView icon, TextView status, boolean passed, String text) {
        if (passed) {
            icon.setText("\u2713"); // checkmark
            icon.setTextColor(ContextCompat.getColor(this, R.color.green));
        } else {
            icon.setText("\u2717"); // X mark
            icon.setTextColor(ContextCompat.getColor(this, R.color.red));
        }
        status.setText(text);
        status.setTextColor(ContextCompat.getColor(this,
                passed ? R.color.green : R.color.text_muted));
    }

    // ═══════════ FIX ACTIONS ═══════════

    private void showActivationDialog() {
        java.io.File extDir = getExternalFilesDir(null);
        final String cmd = "sh " + (extDir != null ? extDir.getPath() : getFilesDir().getPath())
                + "/starter.sh";

        BrutalPopup.dialog(this,
                "Activate",
                "Choose activation method:",
                "Root",
                () -> {
                    sp.edit().putString("activation_method", "root").apply();
                    bg.execute(() -> activateRoot(cmd));
                },
                "Shizuku",
                () -> {
                    sp.edit().putString("activation_method", "shizuku").apply();
                    bg.execute(this::activateShizuku);
                },
                null, null,
                true);
    }

    private void activateRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.OutputStream o = p.getOutputStream();
            // Grant WRITE_SECURE_SETTINGS + run daemon
            String grantCmd = "pm grant " + getPackageName()
                    + " android.permission.WRITE_SECURE_SETTINGS\n";
            o.write(grantCmd.getBytes());
            o.write((cmd + "\nexit\n").getBytes());
            o.flush();
            o.close();
            p.waitFor();
            handler.post(() -> {
                BrutalPopup.toast(this, "Root activation sent.", BrutalPopup.LENGTH_SHORT);
                handler.removeCallbacks(checkRunnable);
                handler.postDelayed(checkRunnable, 500);
            });
        } catch (Exception e) {
            Log.e(TAG, "root activate", e);
            handler.post(() -> BrutalPopup.toast(this,
                    "Root not available.", BrutalPopup.LENGTH_SHORT));
        }
    }

    private void activateShizuku() {
        try {
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    != PackageManager.PERMISSION_GRANTED) {
                handler.post(() -> rikka.shizuku.Shizuku.requestPermission(0));
                return;
            }
            // Grant WRITE_SECURE_SETTINGS
            Process p = rikka.shizuku.Shizuku.newProcess(new String[]{"sh"}, null, null);
            java.io.OutputStream o = p.getOutputStream();
            String grantCmd = "pm grant " + getPackageName()
                    + " android.permission.WRITE_SECURE_SETTINGS\n";
            o.write(grantCmd.getBytes());
            o.flush();
            o.close();
            p.waitFor();

            // Launch daemon
            java.io.File extDir = getExternalFilesDir(null);
            String base = extDir != null ? extDir.getPath() : getFilesDir().getPath();
            Process p2 = rikka.shizuku.Shizuku.newProcess(new String[]{"sh"}, null, null);
            java.io.OutputStream o2 = p2.getOutputStream();
            o2.write(("sh " + base + "/starter.sh\nexit\n").getBytes());
            o2.flush();
            o2.close();
            p2.waitFor();

            handler.post(() -> {
                BrutalPopup.toast(this, "Shizuku activation sent.", BrutalPopup.LENGTH_SHORT);
                handler.removeCallbacks(checkRunnable);
                handler.postDelayed(checkRunnable, 1500);
            });
        } catch (Exception e) {
            Log.e(TAG, "shizuku activate", e);
            handler.post(() -> BrutalPopup.toast(this,
                    "Shizuku not running.", BrutalPopup.LENGTH_SHORT));
        }
    }

    private void enableAccessibilityService() {
        try {
            String svcName = new ComponentName(getPackageName(),
                    tuoluoyiService.class.getName()).flattenToString();
            String current = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) current = "";
            if (!current.contains(svcName)) {
                String next = current.isEmpty() ? svcName : svcName + ":" + current;
                Settings.Secure.putInt(getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            }
        } catch (Exception e) {
            Log.e(TAG, "enableAccessibility", e);
            handler.post(() -> BrutalPopup.toast(this,
                    "Failed. Grant permission first.", BrutalPopup.LENGTH_SHORT));
        }
    }

    // ═══════════ LIFECYCLE ═══════════

    @Override
    protected void onResume() {
        super.onResume();
        // Immediate recheck when returning from settings
        handler.removeCallbacks(checkRunnable);
        handler.post(checkRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try { unregisterReceiver(binderReceiver); } catch (Throwable ignored) {}
        }
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // Go back to login (clears session)
        getSharedPreferences("data", 0).edit()
                .putBoolean("session_active", false).apply();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
