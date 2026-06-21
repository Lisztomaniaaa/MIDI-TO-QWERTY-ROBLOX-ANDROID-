package com.lisztomaniaaa.papiano;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Permission Gate — atomic activation flow.
 *
 * From the user's perspective there is ONE operation: "Activate".
 * Internally it chains: ping source → grant perm → enable accessibility → spawn daemon → wait binder.
 * User sees a single status line that progresses through steps.
 *
 * If ANY step fails → show which step failed + actionable message.
 * ALL pass → auto-navigate to Home.
 */
public class PermissionGateActivity extends Activity {

    private static final String TAG = "PermGate";
    private static final long BINDER_TIMEOUT_MS = 10_000; // max wait for daemon binder

    private TextView tvStatus, tvDetail;
    private ProgressBar progressBar;
    private Button btnActivate, btnContinue;
    private View cardRoot;

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "activation-bg");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean activating = false;
    private volatile boolean binderArrived = false;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver binderReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("intent.tuoluoyi.sendBinder".equals(intent.getAction())) {
                binderArrived = true;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_gate);

        sp = getSharedPreferences("data", 0);

        tvStatus = findViewById(R.id.tv_gate_status);
        tvDetail = findViewById(R.id.tv_gate_detail);
        progressBar = findViewById(R.id.progress_gate);
        btnActivate = findViewById(R.id.btn_activate);
        btnContinue = findViewById(R.id.btn_continue);

        btnActivate.setOnClickListener(v -> startActivation());
        btnContinue.setOnClickListener(v -> navigateHome());

        // Listen for daemon binder broadcast
        try {
            IntentFilter f = new IntentFilter("intent.tuoluoyi.sendBinder");
            ContextCompat.registerReceiver(this, binderReceiver, f,
                    ContextCompat.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver", t);
        }

        // Auto-check: if already fully activated, skip straight to Home
        bg.execute(() -> {
            if (isFullyActivated()) {
                ui.post(this::navigateHome);
            }
        });
    }

    // ═══════════ ATOMIC ACTIVATION ═══════════

    private void startActivation() {
        if (activating) return;
        activating = true;
        binderArrived = false;

        btnActivate.setEnabled(false);
        btnContinue.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        String method = sp.getString("activation_method", null);

        if (method == null) {
            // First time — ask user to choose
            showMethodChoice();
            return;
        }

        bg.execute(() -> runActivation(method));
    }

    private void showMethodChoice() {
        activating = false;
        btnActivate.setEnabled(true);
        progressBar.setVisibility(View.GONE);

        BrutalPopup.dialog(this,
                "Activation Method",
                "Choose how to activate Papiano:",
                "Root",
                () -> {
                    sp.edit().putString("activation_method", "root").apply();
                    startActivation();
                },
                "Shizuku",
                () -> {
                    sp.edit().putString("activation_method", "shizuku").apply();
                    startActivation();
                },
                null, null,
                true);
    }

    /**
     * Atomic activation — runs ALL steps sequentially on bg thread.
     * Posts status updates to UI. Stops at first failure.
     */
    private void runActivation(String method) {
        try {
            // ── Step 1: Ping activation source ──
            postStatus("Checking " + method + "...", "");

            boolean sourceAlive = pingSource(method);
            if (!sourceAlive) {
                postFailed(capitalize(method) + " is not running.",
                        method.equals("shizuku")
                                ? "Open Shizuku app and make sure it's active."
                                : "Root access not available.");
                return;
            }

            // ── Step 2: Grant WRITE_SECURE_SETTINGS ──
            postStatus("Granting permission...", "");

            boolean permGranted = grantPermission(method);
            // Verify
            Thread.sleep(300);
            if (!hasWriteSecureSettings()) {
                postFailed("Permission grant failed.",
                        "Could not grant WRITE_SECURE_SETTINGS.");
                return;
            }

            // ── Step 3: Enable Accessibility Service ──
            postStatus("Enabling service...", "");

            enableAccessibility();
            Thread.sleep(500);
            if (!isAccessibilityEnabled()) {
                postFailed("Could not enable accessibility.",
                        "Try enabling manually in Settings > Accessibility.");
                return;
            }

            // ── Step 4: Spawn daemon ──
            postStatus("Starting daemon...", "Waiting for connection...");

            spawnDaemon(method);

            // ── Step 5: Wait for binder (max BINDER_TIMEOUT_MS) ──
            long deadline = System.currentTimeMillis() + BINDER_TIMEOUT_MS;
            while (!binderArrived && System.currentTimeMillis() < deadline) {
                // Also check GamePadBridge directly (might have been set by tuoluoyiService)
                if (GamePadBridge.gamePad != null) {
                    try {
                        if (GamePadBridge.gamePad.asBinder().pingBinder()) {
                            binderArrived = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                Thread.sleep(500);

                long remaining = (deadline - System.currentTimeMillis()) / 1000;
                postStatus("Starting daemon...", "Waiting... (" + remaining + "s)");
            }

            if (!binderArrived && GamePadBridge.gamePad == null) {
                postFailed("Daemon did not respond.",
                        "Timeout waiting for daemon binder. Try again.");
                return;
            }

            // ── ALL STEPS PASSED ──
            postSuccess();

        } catch (InterruptedException e) {
            postFailed("Activation interrupted.", "");
        } catch (Throwable t) {
            Log.e(TAG, "runActivation", t);
            postFailed("Unexpected error: " + t.getMessage(), "");
        } finally {
            activating = false;
        }
    }

    // ═══════════ SUB-STEPS ═══════════

    private boolean pingSource(String method) {
        switch (method) {
            case "shizuku":
                try {
                    return rikka.shizuku.Shizuku.pingBinder();
                } catch (Throwable t) {
                    return false;
                }
            case "root":
                try {
                    Process p = Runtime.getRuntime().exec("su -c id");
                    int exit = p.waitFor();
                    return exit == 0;
                } catch (Throwable t) {
                    return false;
                }
            default:
                return false;
        }
    }

    private boolean grantPermission(String method) {
        String cmd = "pm grant " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
        try {
            switch (method) {
                case "shizuku": {
                    if (rikka.shizuku.Shizuku.checkSelfPermission()
                            != PackageManager.PERMISSION_GRANTED) {
                        // Request Shizuku permission synchronously-ish
                        ui.post(() -> rikka.shizuku.Shizuku.requestPermission(0));
                        Thread.sleep(2000); // wait for user to grant
                        if (rikka.shizuku.Shizuku.checkSelfPermission()
                                != PackageManager.PERMISSION_GRANTED) {
                            return false;
                        }
                    }
                    Process p = rikka.shizuku.Shizuku.newProcess(
                            new String[]{"sh", "-c", cmd}, null, null);
                    p.waitFor();
                    return true;
                }
                case "root": {
                    Process p = Runtime.getRuntime().exec("su");
                    OutputStream o = p.getOutputStream();
                    o.write((cmd + "\nexit\n").getBytes());
                    o.flush();
                    o.close();
                    p.waitFor();
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "grantPermission", t);
            return false;
        }
    }

    private void enableAccessibility() {
        try {
            String svcName = new ComponentName(getPackageName(),
                    tuoluoyiService.class.getName()).flattenToString();
            String current = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) current = "";
            if (!current.contains(svcName)) {
                String next = current.isEmpty() ? svcName : svcName + ":" + current;
                Settings.Secure.putInt(getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            }
        } catch (Throwable t) {
            Log.e(TAG, "enableAccessibility", t);
        }
    }

    private void spawnDaemon(String method) {
        java.io.File extDir = getExternalFilesDir(null);
        String base = extDir != null ? extDir.getPath() : getFilesDir().getPath();
        String cmd = "sh " + base + "/starter.sh";

        try {
            switch (method) {
                case "shizuku": {
                    Process p = rikka.shizuku.Shizuku.newProcess(
                            new String[]{"sh", "-c", cmd}, null, null);
                    p.waitFor();
                    break;
                }
                case "root": {
                    Process p = Runtime.getRuntime().exec("su");
                    OutputStream o = p.getOutputStream();
                    o.write((cmd + "\nexit\n").getBytes());
                    o.flush();
                    o.close();
                    p.waitFor();
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "spawnDaemon", t);
        }
    }

    // ═══════════ STATE CHECKS ═══════════

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessibilityEnabled() {
        try {
            String svcName = new ComponentName(getPackageName(),
                    tuoluoyiService.class.getName()).flattenToString();
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(svcName);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Full activation = permission + accessibility + daemon binder alive */
    private boolean isFullyActivated() {
        if (!hasWriteSecureSettings()) return false;
        if (!isAccessibilityEnabled()) return false;
        IGamePad gp = GamePadBridge.gamePad;
        if (gp == null) return false;
        try { return gp.asBinder().pingBinder(); }
        catch (Throwable t) { return false; }
    }

    // ═══════════ UI HELPERS ═══════════

    private void postStatus(String status, String detail) {
        ui.post(() -> {
            tvStatus.setText(status);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            tvDetail.setText(detail);
            tvDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            btnActivate.setEnabled(false);
        });
    }

    private void postFailed(String status, String detail) {
        ui.post(() -> {
            tvStatus.setText(status);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
            tvDetail.setText(detail);
            tvDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            btnActivate.setEnabled(true);
            btnActivate.setText("Retry");
            btnContinue.setVisibility(View.GONE);
        });
    }

    private void postSuccess() {
        ui.post(() -> {
            tvStatus.setText("Activated!");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
            tvDetail.setText("All systems ready. Tap Continue.");
            tvDetail.setVisibility(View.VISIBLE);
            tvDetail.setTextColor(ContextCompat.getColor(this, R.color.green));
            progressBar.setVisibility(View.GONE);
            btnActivate.setVisibility(View.GONE);
            btnContinue.setVisibility(View.VISIBLE);
        });
    }

    private void navigateHome() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ═══════════ LIFECYCLE ═══════════

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try { unregisterReceiver(binderReceiver); } catch (Throwable ignored) {}
        }
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        getSharedPreferences("data", 0).edit()
                .putBoolean("session_active", false).apply();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}
