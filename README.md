# Papiano - MIDI to QWERTY Bridge for Roblox (Android)

A lightweight Android app that converts real MIDI keyboard input into virtual QWERTY keypresses, allowing you to play piano games in Roblox using a physical MIDI keyboard on Android devices.

## How It Works

1. Connect a USB MIDI keyboard to your Android device (via USB OTG)
2. The app intercepts MIDI Note On/Off messages
3. Converts them to virtual HID keypresses that Roblox recognizes
4. Play piano games (Roblox Piano, Piano Rooms, etc.) with your real keyboard

## Features

- Full 88-key support (MIDI notes 21-107)
- Sustain/hold support (hold a key = hold in game)
- MIDI file playback with speed control
- Floating overlay panel for live status
- Auto-reconnect on MIDI device replug
- Background service (works while Roblox is in foreground)
- Shizuku / Root / ADB activation methods

## Requirements

- Android 7.0+ (API 24)
- USB OTG support
- One of: Shizuku, Root, or ADB access (for WRITE_SECURE_SETTINGS permission)
- USB MIDI keyboard + OTG adapter

## How to Install

### Option 1: Download from GitHub Actions (Recommended)

1. Go to the **[Actions](../../actions)** tab
2. Click the latest successful **"Build APK"** workflow run
3. Download the **`app-debug`** artifact (or `app-release` if available)
4. Extract the ZIP and install the APK on your phone
5. You may need to enable "Install from unknown sources"

### Option 2: Build from Source

```bash
git clone https://github.com/Lisztomaniaaa/MIDI-TO-QWERTY-ROBLOX-ANDROID-.git
cd MIDI-TO-QWERTY-ROBLOX-ANDROID-
chmod +x gradlew
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Setup Guide

1. Install the APK
2. Open Papiano
3. Tap **Activate** and choose your method:
   - **Shizuku** (recommended) - Install Shizuku app, start it, then activate
   - **Root** - If your device is rooted
   - **ADB** - Run the copied command via `adb shell` from PC
4. Tap **Start Playing**
5. Grant overlay permission when prompted
6. Connect your MIDI keyboard via USB OTG
7. Open Roblox and join a piano game
8. Play!

## Toast Notifications

The app shows minimal toast notifications:
- **"MIDI Connected"** - when your MIDI keyboard is detected
- **"MIDI Disconnected"** - when your MIDI keyboard is unplugged

## Credits & Sources

This project is based on and inspired by:

- **[RobloxAndroidMidi](https://github.com/EDLLT/RobloxAndroidMidi)** by EDLLT - Original concept for MIDI-to-Roblox bridge on Android

## Technical Details

- Uses Android AccessibilityService for background operation
- Virtual HID device created via `/dev/uhid` (requires elevated permissions)
- MIDI processing on dedicated HandlerThread to avoid ANR
- Floating panel via SYSTEM_ALERT_WINDOW overlay

## License

This project is provided as-is for educational purposes. Use at your own risk.

---

*Want updates? Contact on Discord: @jvkowi*
