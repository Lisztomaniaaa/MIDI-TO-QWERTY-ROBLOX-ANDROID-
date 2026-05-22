package com.lisztomaniaaa.papiano;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import rikka.shizuku.Shizuku;

/**
 * Simplified main screen.
 *
 * Layout (top -> bottom):
 *   1. Permission card    -> Activate button + status text
 *   2. Service card       -> Start Playing button + status text
 *   3. MIDI Device card   -> read-only device name
 *   4. Close App button
 *
 * No popup window. Service runs entirely in the background once Start Playing is on.
 * Mode is hard-locked to QWERTY; no MIDI/PianoRooms mode, no octave/floating settings.
 *
 * IMPORTANT: Pada device tertentu (POCO X3 Pro / MIUI 14, sesuai bug report user)
 * system_server bisa stuck di UsbHostManager deadlock saat USB MIDI di-colok. Kalau
 * kita panggil binder API (Settings.Secure, MidiManager) sync di main thread, app
 * langsung ANR / "open langsung crash". Semua call begitu di-pindahin ke background
 * executor di sini, hasilnya di-post balik ke UI thread.
 */
public class MainActivity extends Activity {
    public static final String TAG = "PapianoMidi";

    boolean isListenerAdded = false;
    boolean isBroadcastRegistered = false;

    Button activateBtn;
    Button startBtn;
    TextView tvPermissionStatus;
    TextView tvServiceStatus;

    SharedPreferences sp;

    /** Cache hasil isAccessibilityEnabled() biar refreshServiceStatus() di UI thread
     *  gak nge-trigger Binder call sync. Update-nya via background executor. */
    private volatile boolean cachedAccessibilityEnabled = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "papiano-bg");
        t.setDaemon(true);
        return t;
    });

    private final Shizuku.OnRequestPermissionResultListener SHIZUKU_LISTENER =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bg.execute(() -> {
                        grantWriteSecureSettingsViaShizuku();
                        launchViaShizuku();
                        ui.post(this::refreshPermissionStatus);
                    });
                }
            };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent.getAction() == null) return;
                switch (intent.getAction()) {
                    case "intent.tuoluoyi.sendBinder":
                        IBinder binder = extractBinder(intent);
                        if (binder == null || !binder.pingBinder()) return;
                        onDaemonReady();
                        break;
                    case "intent.tuoluoyi.exit":
                        onDaemonStopped();
                        break;
                }
            } catch (Throwable t) {
                Log.e(TAG, "BroadcastReceiver onReceive error", t);
            }
        }
    };

    /**
     * Daemon LAMA (sebelum package rename ke com.lisztomaniaaa.papiano) bisa
     * masih hidup di shell uid 2000 — process Shizuku-spawned gak ke-kill pas
     * APK lama uninstall. Daemon itu kirim broadcast pake BinderContainer dari
     * package legacy com.tile.tuoluoyi. Tanpa fallback ini, getParcelableExtra
     * langsung lempar BadParcelableException → app crash.
     *
     * Strategi: support DUA package class (legacy + sekarang). Kalau yg dateng
     * legacy, kita ambil binder-nya, lalu kirim signal exit biar daemon lama
     * mati. Daemon baru nanti naik dari aktivasi user.
     */
    private IBinder extractBinder(Intent intent) {
        try {
            Object raw = intent.getParcelableExtra("binder");
            if (raw instanceof BinderContainer) {
                return ((BinderContainer) raw).getBinder();
            }
            if (raw instanceof com.tile.tuoluoyi.BinderContainer) {
                Log.w(TAG, "Legacy daemon detected (com.tile.tuoluoyi.*) - sending exit");
                try { sendBroadcast(new Intent("intent.tuoluoyi.exit")); } catch (Throwable ignored) {}
                return ((com.tile.tuoluoyi.BinderContainer) raw).getBinder();
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "extractBinder failed (stale daemon?): " + t.getClass().getSimpleName());
            try { sendBroadcast(new Intent("intent.tuoluoyi.exit")); } catch (Throwable ignored) {}
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashHandler();

        try {
            setContentView(R.layout.activity_main);

            sp = getSharedPreferences("data", 0);
            if (sp.getBoolean("first", true)) showWelcome();

            // Unzip jangan di main thread - getAssets/ZipFile bisa lama.
            bg.execute(this::unzipFiles);

            // Register receiver di main (cheap). Pisah try biar satu fail
            // gak nyeret yg lain.
            try {
                IntentFilter daemonFilter = new IntentFilter("intent.tuoluoyi.sendBinder");
                ContextCompat.registerReceiver(this, mBroadcastReceiver, daemonFilter,
                        ContextCompat.RECEIVER_EXPORTED);

                IntentFilter internalFilter = new IntentFilter();
                internalFilter.addAction("intent.tuoluoyi.exit");
                ContextCompat.registerReceiver(this, mBroadcastReceiver, internalFilter,
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                isBroadcastRegistered = true;
            } catch (Throwable t) { Log.e(TAG, "registerReceiver", t); }

            bindViews();
            refreshPermissionStatus();
            refreshServiceStatus();   // UI dgn cached value (false) dulu
            refreshAccessibilityCacheAsync();  // baru kick refresh asli di bg

            // Defer requestBatteryExemption ke akhir + di bg, intent system
            // bisa lambat di MIUI.
            ui.postDelayed(() -> bg.execute(this::requestBatteryExemption), 500);
        } catch (Throwable t) {
            Log.e(TAG, "onCreate fatal", t);
            showCrashDialog(t);
        }
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Log.e(TAG, "UNCAUGHT on " + thread.getName(), throwable);
                java.io.File dir = getExternalFilesDir(null);
                if (dir != null) {
                    java.io.File f = new java.io.File(dir, "last_crash.txt");
                    java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f));
                    pw.println("Thread: " + thread.getName());
                    pw.println("Time: " + new java.util.Date());
                    throwable.printStackTrace(pw);
                    pw.close();
                }
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(thread, throwable);
        });
    }

    private void showCrashDialog(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        final String trace = sw.toString();
        try {
            new AlertDialog.Builder(this)
                    .setTitle("Papiano crashed")
                    .setMessage(trace)
                    .setPositiveButton("Copy", (d, i) -> {
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("crash", trace));
                        Toast.makeText(this, "Stack trace copied", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Close", (d, i) -> finish())
                    .setCancelable(false)
                    .show();
        } catch (Throwable t2) {
            Toast.makeText(this, "Crash: " + t.getClass().getSimpleName()
                    + " - " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void bindViews() {
        activateBtn = findViewById(R.id.b);
        startBtn = findViewById(R.id.btn_start_playing);
        tvPermissionStatus = findViewById(R.id.tv_permission_status);
        tvServiceStatus = findViewById(R.id.tv_service_status);

        activateBtn.setOnClickListener(v -> showActivationDialog());

        startBtn.setOnClickListener(v -> {
            if (!hasWriteSecureSettings()) {
                Toast.makeText(this,
                        "Activate the permission first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!cachedAccessibilityEnabled && !canDrawOverlays()) {
                showOverlayPermissionDialog();
                return;
            }
            // wantStart = user lagi mau ON-in service. Capture sebelum toggle
            // karena toggleAccessibility async (refresh cache di bg thread).
            final boolean wantStart = !cachedAccessibilityEnabled;
            toggleAccessibility();

            if (wantStart && canDrawOverlays()) {
                // User pencet Start = niat eksplisit pengen aktif. Reset
                // dismissed flag biar panel boleh muncul lagi (kalau
                // sebelumnya user pernah close pake ✕).
                sp.edit().remove("panel_user_dismissed").apply();
                startFloatingPanel();
            }
            // Kalau wantStart=false (user lagi STOP), panel mati lewat
            // intent.tuoluoyi.exit yg di-broadcast di toggleAccessibility().
        });

        Button closeBtn = findViewById(R.id.btn_force_close);
        closeBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.close_app_title)
                .setMessage(R.string.close_app_text)
                .setPositiveButton(R.string.close, (d, i) -> {
                    sendBroadcast(new Intent("intent.tuoluoyi.exit"));
                    android.os.Process.killProcess(android.os.Process.myPid());
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    /* ---------------- PERMISSION ---------------- */

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissionStatus() {
        if (hasWriteSecureSettings()) {
            String method = sp.getString("activation_method", "shizuku");
            int label;
            switch (method) {
                case "root": label = R.string.permission_granted_root; break;
                case "adb":  label = R.string.permission_granted_adb;  break;
                default:     label = R.string.permission_granted_shizuku;
            }
            tvPermissionStatus.setText(label);
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
            activateBtn.setText(R.string.activated);
            activateBtn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.green)));
        } else {
            tvPermissionStatus.setText(R.string.permission_not_granted);
            tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
            activateBtn.setText(R.string.activate);
            activateBtn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.red)));
        }
    }

    private void showActivationDialog() {
        java.io.File extDir = getExternalFilesDir(null);
        final String cmd = "sh " + (extDir != null ? extDir.getPath() : getFilesDir().getPath())
                + "/starter.sh";
        new AlertDialog.Builder(this)
                .setTitle(R.string.active_title)
                .setMessage(R.string.active_text)
                .setPositiveButton("Root", (d, i) -> {
                    sp.edit().putString("activation_method", "root").apply();
                    bg.execute(() -> {
                        try {
                            Process p = Runtime.getRuntime().exec("su");
                            OutputStream o = p.getOutputStream();
                            o.write((cmd + "\nexit\n").getBytes()); o.flush(); o.close();
                            ui.post(() -> Toast.makeText(this,
                                    "Activation script sent to root shell.",
                                    Toast.LENGTH_SHORT).show());
                        } catch (IOException ex) {
                            Log.e(TAG, "root", ex);
                            ui.post(() -> Toast.makeText(this,
                                    "Root not available.",
                                    Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNeutralButton(R.string.copy_cmd, (d, i) -> {
                    sp.edit().putString("activation_method", "adb").apply();
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("c", "adb shell " + cmd));
                    Toast.makeText(this, "ADB command copied to clipboard.",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Shizuku", (d, i) -> {
                    sp.edit().putString("activation_method", "shizuku").apply();
                    bg.execute(this::launchViaShizuku);
                })
                .show();
    }

    /* ---------------- SERVICE (accessibility) ---------------- */

    /** Background-only. Settings.Secure binder call. */
    private boolean readAccessibilityEnabledBlocking() {
        try {
            String svcName = new ComponentName(getPackageName(),
                    tuoluoyiService.class.getName()).flattenToString();
            String set = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return set != null && set.contains(svcName);
        } catch (Throwable t) {
            Log.w(TAG, "readAccessibilityEnabled failed", t);
            return false;
        }
    }

    private void refreshAccessibilityCacheAsync() {
        bg.execute(() -> {
            boolean v = readAccessibilityEnabledBlocking();
            if (v != cachedAccessibilityEnabled) {
                cachedAccessibilityEnabled = v;
                ui.post(this::refreshServiceStatus);
            }
        });
    }

    private void refreshServiceStatus() {
        boolean running = cachedAccessibilityEnabled;
        tvServiceStatus.setText(running ? R.string.service_running : R.string.service_stopped);
        tvServiceStatus.setTextColor(ContextCompat.getColor(this,
                running ? R.color.green : R.color.text_muted));
        startBtn.setText(running ? R.string.stop_playing : R.string.start_playing);
        startBtn.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this,
                        running ? R.color.text_muted : R.color.accent_primary)));
    }

    private void toggleAccessibility() {
        final String svcName = new ComponentName(getPackageName(),
                tuoluoyiService.class.getName()).flattenToString();
        final boolean wantEnable = !cachedAccessibilityEnabled;

        bg.execute(() -> {
            try {
                if (!wantEnable) {
                    sendBroadcast(new Intent("intent.tuoluoyi.exit"));
                    String cur = Settings.Secure.getString(getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (cur != null) {
                        List<String> list = new ArrayList<>(Arrays.asList(cur.split(":")));
                        list.remove(svcName);
                        Settings.Secure.putString(getContentResolver(),
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                TextUtils.join(":", list));
                    }
                } else {
                    String cur = Settings.Secure.getString(getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    String next = (cur == null || cur.isEmpty())
                            ? svcName : svcName + ":" + cur;
                    Settings.Secure.putInt(getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    Settings.Secure.putString(getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
                }
            } catch (Exception e) {
                Log.e(TAG, "toggleAccessibility", e);
                ui.post(() -> Toast.makeText(this,
                        wantEnable ? "Failed to start the service."
                                   : "Failed to stop the service.",
                        Toast.LENGTH_SHORT).show());
            }
            refreshAccessibilityCacheAsync();
        });
    }

    /* ---------------- FLOATING OVERLAY ---------------- */

    private boolean canDrawOverlays() {
        try { return Settings.canDrawOverlays(this); }
        catch (Throwable t) { return false; }
    }

    private void showOverlayPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Floating overlay permission")
                .setMessage("Papiano butuh izin tampil di atas aplikasi lain "
                        + "supaya panel kontrol tetap muncul saat kamu pindah "
                        + "ke Roblox / app lain.")
                .setPositiveButton("Open Settings", (d, i) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Throwable t) {
                        Log.w(TAG, "open overlay settings", t);
                        Toast.makeText(this, "Buka Settings manual: "
                                + "Apps -> Papiano -> Display over other apps",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startFloatingPanel() {
        try {
            Intent svc = new Intent(this, FloatingPanelService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startFloatingPanel", t);
        }
    }

    /* ---------------- BROADCAST CALLBACKS ---------------- */

    private void onDaemonReady() {
        Toast.makeText(this, R.string.connect_success, Toast.LENGTH_SHORT).show();
        refreshPermissionStatus();
        refreshAccessibilityCacheAsync();
    }

    private void onDaemonStopped() {
        refreshAccessibilityCacheAsync();
    }

    /* ---------------- SHIZUKU / SETUP ---------------- */

    /** Run on background. */
    private void grantWriteSecureSettingsViaShizuku() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
                OutputStream o = p.getOutputStream();
                String cmd = "pm grant " + getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS\nexit\n";
                o.write(cmd.getBytes()); o.flush(); o.close(); p.waitFor();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly();
                else p.destroy();
                Log.d(TAG, "WRITE_SECURE_SETTINGS granted via Shizuku");
            }
        } catch (Exception e) {
            Log.e(TAG, "grantWriteSecureSettingsViaShizuku", e);
        }
    }

    /** Run on background. */
    private void launchViaShizuku() {
        if (!isListenerAdded) {
            ui.post(() -> {
                Shizuku.addRequestPermissionResultListener(SHIZUKU_LISTENER);
                isListenerAdded = true;
            });
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                ui.post(() -> Shizuku.requestPermission(0));
                return;
            }
            grantWriteSecureSettingsViaShizuku();
            java.io.File extDir = getExternalFilesDir(null);
            String base = extDir != null ? extDir.getPath() : getFilesDir().getPath();
            String cmd = "sh " + base + "/starter.sh";
            Process p = Shizuku.newProcess(new String[]{"sh"}, null, null);
            OutputStream o = p.getOutputStream();
            o.write((cmd + "\nexit\n").getBytes()); o.flush(); o.close(); p.waitFor();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly();
            else p.destroy();
            ui.post(this::refreshPermissionStatus);
        } catch (Exception e) {
            Log.e(TAG, "shizuku", e);
            ui.post(() -> Toast.makeText(this, "Shizuku is not running.",
                    Toast.LENGTH_SHORT).show());
        }
    }

    /** Run on background. PowerManager.isIgnoringBatteryOptimizations() = sync binder. */
    private void requestBatteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Service.POWER_SERVICE);
            final boolean needsRequest = pm != null
                    && !pm.isIgnoringBatteryOptimizations(getPackageName());
            if (needsRequest) {
                ui.post(() -> {
                    try {
                        Intent i = new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable e) { Log.w(TAG, "battery startActivity", e); }
                });
            }
        } catch (Throwable e) { Log.w(TAG, "battery", e); }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                final boolean needsNotifPerm = nm != null && !nm.areNotificationsEnabled();
                if (needsNotifPerm) {
                    ui.post(() -> {
                        try {
                            requestPermissions(new String[]{
                                    "android.permission.POST_NOTIFICATIONS"}, 0);
                        } catch (Throwable e) { Log.w(TAG, "notif perm req", e); }
                    });
                }
            }
        } catch (Throwable e) { Log.w(TAG, "notif perm", e); }
    }

    private void showWelcome() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.privacy_title)
                .setMessage(R.string.privacy_text)
                .setPositiveButton(R.string.agree,
                        (d, i) -> sp.edit().putBoolean("first", false).apply())
                .setNegativeButton(R.string.exit, (d, i) -> finish())
                .setCancelable(false)
                .show();
    }

    /* ---------------- ASSETS UNPACK (background only) ---------------- */

    private void unzipFiles() {
        java.io.File extDir = getExternalFilesDir(null);
        if (extDir == null) {
            Log.w(TAG, "external files dir null, skip unzip");
            return;
        }
        String base = extDir.getPath();

        try {
            InputStream is = getAssets().open("starter.sh");
            FileOutputStream fos = new FileOutputStream(base + "/starter.sh");
            byte[] buf = new byte[4096]; int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            is.close(); fos.close();
        } catch (Exception ignored) {}

        try {
            ZipFile zip = new ZipFile(getPackageResourcePath());
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("classes4.dex")) {
                    InputStream in = zip.getInputStream(entry);
                    FileOutputStream out = new FileOutputStream(base + "/GyroNative.dex");
                    byte[] buf = new byte[4096]; int l;
                    while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
                    in.close(); out.close();
                    break;
                }
            }
            zip.close();
        } catch (Exception ignored) {}

        try {
            FileInputStream in = new FileInputStream(
                    getApplicationInfo().nativeLibraryDir + "/libtuoluoyi.so");
            FileOutputStream out = new FileOutputStream(base + "/libtuoluoyi.so");
            byte[] buf = new byte[4096]; int l;
            while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
            in.close(); out.close();
        } catch (Exception ignored) {}
    }

    /* ---------------- LIFECYCLE ---------------- */

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        refreshAccessibilityCacheAsync();
        // Kalau service udah running + overlay perm udah granted (mis. user
        // baru pulang dari Settings), auto-start panel biar gak perlu pencet
        // Start Playing 2x. TAPI hormati flag panel_user_dismissed: kalau
        // user baru aja close panel pake ✕, jangan langsung restart.
        if (cachedAccessibilityEnabled && canDrawOverlays()
                && !sp.getBoolean("panel_user_dismissed", false)) {
            startFloatingPanel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBroadcastRegistered) {
            try { unregisterReceiver(mBroadcastReceiver); } catch (Throwable ignored) {}
        }
        if (isListenerAdded) {
            try { Shizuku.removeRequestPermissionResultListener(SHIZUKU_LISTENER); }
            catch (Throwable ignored) {}
        }
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() { finish(); }
}
