#!/system/bin/sh
# Daemon launcher — dipanggil via Shizuku (uid 2000) atau root (uid 0).
#
# Job:
#   1. Grant WRITE_SECURE_SETTINGS ke app supaya bisa toggle accessibility.
#   2. Copy libtuoluoyi.so ke /data/local/tmp (writable + executable
#      dari shell uid).
#   3. Spawn daemon process (com.lisztomaniaaa.papiano.GamePadNative) via
#      app_process dengan CLASSPATH yg punya class itu.
#
# CLASSPATH STRATEGY:
# Original starter.sh asumsi class ada di classes4.dex (extracted ke
# GyroNative.dex). Tapi multidex layout kita beda — class bisa landing
# di classes.dex / classes2.dex / dst, bukan classes4.dex. Akibatnya
# app_process ClassNotFoundException -> daemon mati instan.
#
# Fix: pake APK path langsung via 'pm path'. APK itu ZIP yg berisi SEMUA
# classes*.dex; app_process bisa load class dari APK terlepas dari di
# dex file mana class-nya disimpan. Jauh lebih robust dari nge-tebak
# mana dex yg punya GamePadNative.

PKG="papiano.fun"
TARGET_CLASS="com.lisztomaniaaa.papiano.GamePadNative"

pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS

# ---- Native lib ----
# libtuoluoyi.so harus di lokasi yg executable. /data/local/tmp = group
# 'shell', readable + executable by shell uid. /data/data/<pkg>/files/
# bisa juga, tapi shell uid gak punya akses tulis ke /data/data milik app.
LIBSO_SRC="$(dirname "$0")/libtuoluoyi.so"
CACHE_DIR="/data/local/tmp"
LIBSO_DST="$CACHE_DIR/libtuoluoyi.so"

if [ -e "$LIBSO_SRC" ]; then
  cp -f "$LIBSO_SRC" "$LIBSO_DST"
  chmod 0755 "$LIBSO_DST"
fi

# ---- Find APK path ----
# 'pm path <pkg>' return: "package:/data/app/.../base.apk"
# Strip "package:" prefix. head -1 in case ada multi-split (usually base.apk
# is first / only).
APK_PATH=""
PM_OUT=$(pm path "$PKG" 2>/dev/null | head -1)
if [ -n "$PM_OUT" ]; then
  APK_PATH=$(echo "$PM_OUT" | sed 's/^package://')
fi

# Fallback: kalau pm path gagal (rare), coba pakai dex extraction lama.
DEX_FALLBACK="$(dirname "$0")/GyroNative.dex"

if [ -n "$APK_PATH" ] && [ -e "$APK_PATH" ]; then
  CP="$APK_PATH"
elif [ -e "$DEX_FALLBACK" ]; then
  CP="$DEX_FALLBACK"
else
  # Gak ada classpath valid — abort.
  exit 1
fi

export CLASSPATH="$CP"

# ---- Kill stale daemon ----
# Every respawn attempt (Shizuku hiccup, binder death, auto-recovery retry)
# invokes this script. Without killing the previous instance first, retries
# stack ANOTHER app_process on top of the old one — multiple processes
# racing to open /dev/uhid, which is why reconnect can silently fail or
# leave an orphaned daemon running after the app is closed.
pkill -f "$TARGET_CLASS" 2>/dev/null

# ---- Spawn daemon ----
# nohup + & supaya daemon stay running setelah shell session yg invoke
# starter.sh selesai. >/dev/null 2>&1 utk redirect log (kalau gak,
# stdout/stderr nge-block pas pipe parent close).
nohup app_process -Djava.library.path="$CACHE_DIR" / "$TARGET_CLASS" >/dev/null 2>&1 &
