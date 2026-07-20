package com.lisztomaniaaa.papiano;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

        // Auto-check: if already fully activated, skip straight to Home.
        //
        // Permission + accessibility state survives reboots, but the daemon
        // itself does NOT — it's a separate Shizuku/root-spawned process,
        // not an Android service, so a reboot (or Shizuku restarting) kills
        // it silently. Without a check here, reopening the app after that
        // skipped straight to a dead connection and left the user staring
        // at "Recovering..." for however long MainActivity's health-monitor
        // loop took to notice and retry. Kick a respawn immediately instead.
        bg.execute(() -> {
            if (isFullyActivated()) {
                if (!isDaemonAlive()) {
                    DaemonControl.respawn(getApplicationContext());
                }
                ui.post(this::navigateHome);
            }
        });
    }

    private boolean isDaemonAlive() {
        IGamePad gp = GamePadBridge.gamePad;
        if (gp == null) return false;
        try { return gp.asBinder().pingBinder(); } catch (Throwable t) { return false; }
    }

    // ═══════════ ATOMIC ACTIVATION ═══════════

    private void startActivation() {
        if (activating) return;
        activating = true;

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
     *
     * NOTE: Daemon spawn is FIRE-AND-FORGET. We do NOT wait for binder.
     * The daemon connects asynchronously via sticky broadcast → tuoluoyiService
     * handles it → GamePadBridge gets set. Home screen shows live status.
     * Original app never blocked on daemon — neither should we.
     */
    private void runActivation(String method) {
        try {
            // ── Step 0: Unzip daemon files (starter.sh, .dex, .so) ──
            // Must happen BEFORE spawning daemon — otherwise starter.sh doesn't exist.
            postStatus("Preparing files...", "");
            unzipFiles();

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

            // ── Step 4: Spawn daemon (fire-and-forget) ──
            postStatus("Starting daemon...", "");

            DaemonControl.respawn(getApplicationContext());
            // Give it a moment to start, but DON'T wait for binder.
            // tuoluoyiService will receive the binder via sticky broadcast
            // asynchronously. Home screen monitors connection status.
            Thread.sleep(1000);

            // ── ALL STEPS PASSED → go to Home ──
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

    /** Activation = permission + accessibility. Daemon is async. */
    private boolean isFullyActivated() {
        if (!hasWriteSecureSettings()) return false;
        if (!isAccessibilityEnabled()) return false;
        return true;
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

    // ═══════════ UNZIP DAEMON FILES ═══════════

    /**
     * Extract starter.sh, GyroNative.dex, libtuoluoyi.so to external files dir.
     * These are required for the daemon to start. Must run before DaemonControl.respawn().
     */
    private void unzipFiles() {
        java.io.File extDir = getExternalFilesDir(null);
        if (extDir == null) return;
        String base = extDir.getPath();

        try {
            java.io.InputStream is = getAssets().open("starter.sh");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(base + "/starter.sh");
            byte[] buf = new byte[4096]; int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            is.close(); fos.close();
        } catch (Exception ignored) {}

        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(getPackageResourcePath());
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("classes4.dex")) {
                    java.io.InputStream in = zip.getInputStream(entry);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(base + "/GyroNative.dex");
                    byte[] buf = new byte[4096]; int l;
                    while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
                    in.close(); out.close();
                    break;
                }
            }
            zip.close();
        } catch (Exception ignored) {}

        try {
            java.io.FileInputStream in = new java.io.FileInputStream(
                    getApplicationInfo().nativeLibraryDir + "/libtuoluoyi.so");
            java.io.FileOutputStream out = new java.io.FileOutputStream(base + "/libtuoluoyi.so");
            byte[] buf = new byte[4096]; int l;
            while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
            in.close(); out.close();
        } catch (Exception ignored) {}
    }

    // ═══════════ LIFECYCLE ═══════════

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // This is the entry point now (no login gate) — don't allow back.
        finishAffinity();
    }
}
