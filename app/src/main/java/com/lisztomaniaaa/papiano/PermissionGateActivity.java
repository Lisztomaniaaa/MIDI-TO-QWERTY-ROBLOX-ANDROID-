package com.lisztomaniaaa.papiano;

import android.app.Activity;
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
     * Activation — runs on bg thread, posts status updates to UI.
     *
     * Used to be an "atomic" gate: grant permission, sleep(300), verify;
     * enable accessibility, sleep(500), verify; spawn daemon, sleep(1000);
     * only THEN declare success. Those sleep-then-verify steps guessed a
     * fixed propagation delay — if a slower device took a bit longer than
     * 300/500ms, the gate declared FAILURE even though the step would have
     * succeeded moments later, sending the user back to square one for no
     * real reason. (This is also the flow the app's original, pre-rebuild
     * version never had — it just ran the shell command once and let
     * status reflect reality, no synchronous pass/fail gate at all.)
     *
     * Now: fire permission grant + accessibility enable + daemon spawn in
     * sequence, don't gate on guessed timing. The ONLY hard-fail check left
     * is "is the activation source (Shizuku/root) even reachable", which is
     * an instant, real check (no sleep/guessing involved). Everything else's
     * real status is reported continuously by PermissionHealthMonitor /
     * tuoluoyiService once we land on Home — same source of truth the rest
     * of the app already relies on, instead of a one-time snapshot taken
     * moments after firing off async shell commands.
     */
    private void runActivation(String method) {
        try {
            // ── Unzip daemon files (starter.sh, .dex, .so) ──
            // Must happen BEFORE spawning daemon — otherwise starter.sh doesn't exist.
            postStatus("Preparing files...", "");
            unzipFiles();

            // ── Ping activation source — the one real, instant check ──
            postStatus("Checking " + method + "...", "");

            boolean sourceAlive = pingSource(method);
            if (!sourceAlive) {
                postFailed(capitalize(method) + " is not running.",
                        method.equals("shizuku")
                                ? "Open Shizuku app and make sure it's active."
                                : "Root access not available.");
                return;
            }

            // ── Grant permission, enable accessibility, spawn daemon ──
            postStatus("Activating...", "");

            grantPermission(method);
            enableAccessibility();
            DaemonControl.respawn(getApplicationContext());

            // Brief pause for UX pacing only — not a pass/fail gate. The
            // daemon connects asynchronously via sticky broadcast ->
            // tuoluoyiService -> GamePadBridge; Home screen's health monitor
            // shows the real, live status from here on.
            Thread.sleep(500);

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
        AccessibilityGate.ensureEnabled(this);
    }

    // ═══════════ STATE CHECKS ═══════════

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks the REAL bound state, not just whether Settings.Secure lists our
     * service — see AccessibilityGate for why that distinction matters
     * (Android 13+ restricted settings can let the string-write succeed while
     * silently blocking the actual bind).
     */
    private boolean isAccessibilityEnabled() {
        return AccessibilityGate.isActuallyBound(this);
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
