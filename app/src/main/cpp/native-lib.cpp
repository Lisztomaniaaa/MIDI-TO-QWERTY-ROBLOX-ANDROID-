/**
 * native-lib.cpp — uinput-based virtual keyboard for MIDI-to-QWERTY.
 *
 * ARCHITECTURE (v2 rewrite):
 *   Previous version used /dev/uhid with HID boot-protocol reports. That had
 *   two fatal limitations for fast classical pieces (e.g. Etude Op.10 No.4):
 *     1. 6KRO: max 6 simultaneous keys in one HID report.
 *     2. Shared modifier byte: Shift/Ctrl affected ALL held keys at once,
 *        making natural + sharp notes impossible to hold together.
 *
 *   New version uses /dev/uinput with individual EV_KEY events:
 *     - UNLIMITED simultaneous keys (each key is independent)
 *     - SCOPED MODIFIERS: Shift/Ctrl is tapped ONLY during the key-down
 *       SYN_REPORT, then immediately released. The game registers the
 *       modified key at InputBegan time; the key stays held without modifier.
 *     - Lower per-event latency: one write per key event vs 8-byte report.
 *
 *   The "scoped modifier tap" trick works because Roblox (and most game
 *   engines) check modifier state at the MOMENT of InputBegan, not
 *   continuously during hold. So:
 *     Frame 1: Shift-down + A-down + SYN → game sees "A" (shifted)
 *     Frame 2: Shift-up + SYN            → game sees Shift released
 *     Result: "A" stays held without shift polluting other keys.
 *
 * PERFORMANCE:
 *   - Process-level nice -19 (URGENT_AUDIO equivalent)
 *   - Per-thread boost on first binder call
 *   - Batched writes (multiple input_events in one write() syscall)
 *   - Zero heap allocation on hot path
 *   - Key-collision detection (same physical key, different notes)
 */

#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <linux/uinput.h>
#include <linux/input.h>
#include <android/log.h>
#include <mutex>
#include <atomic>
#include <vector>
#include <algorithm>
#include <sys/resource.h>
#include <sys/time.h>

static const char *TAG = "RobloxAndroidMidi";

// ============================================================================
// UINPUT DEVICE
// ============================================================================

static int g_uinputFd = -1;

// Write helper — retry on EAGAIN/EINTR (same philosophy as old uhidWrite).
static inline ssize_t uinputWrite(const void *buf, size_t len) {
    if (g_uinputFd < 0) return -1;
    for (int attempt = 0; attempt < 256; ++attempt) {
        ssize_t r = write(g_uinputFd, buf, len);
        if (r >= 0) return r;
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            sched_yield();
            continue;
        }
        static std::atomic<bool> warned{false};
        bool expected = false;
        if (warned.compare_exchange_strong(expected, true)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "uinput write failed: errno=%d (%s)", errno, strerror(errno));
        }
        return r;
    }
    return -1;
}

// Emit a single input_event.
static inline void emitEvent(__u16 type, __u16 code, __s32 value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    uinputWrite(&ev, sizeof(ev));
}

// Emit SYN_REPORT to commit pending events as one atomic input frame.
static inline void emitSync() {
    emitEvent(EV_SYN, SYN_REPORT, 0);
}

// Batched emit: write multiple events + SYN in one syscall for minimum latency.
// Events array should NOT include SYN_REPORT — it's appended automatically.
static inline void emitBatch(struct input_event *events, int count) {
    if (count <= 0 || g_uinputFd < 0) return;
    // Allocate on stack: max realistic batch = 4 events + 1 SYN = 5
    // For safety, support up to 8 events + SYN.
    struct input_event buf[9];
    int n = (count > 8) ? 8 : count;
    memcpy(buf, events, n * sizeof(struct input_event));
    // Append SYN_REPORT
    memset(&buf[n], 0, sizeof(struct input_event));
    buf[n].type = EV_SYN;
    buf[n].code = SYN_REPORT;
    buf[n].value = 0;
    uinputWrite(buf, (n + 1) * sizeof(struct input_event));
}

// ============================================================================
// KEY MAPPING: MIDI note (21-108) → Linux keycode + modifier type
// ============================================================================

enum ModType : uint8_t {
    MOD_NONE  = 0,
    MOD_SHIFT = 1,  // KEY_LEFTSHIFT
    MOD_CTRL  = 2,  // KEY_LEFTCTRL
};

struct KeyMapping {
    __u16 keycode;   // Linux KEY_* code
    ModType mod;     // Which modifier to scope-tap
};

// Linux keycodes for reference:
// KEY_1=2, KEY_2=3, ..., KEY_0=11
// KEY_Q=16, KEY_W=17, KEY_E=18, KEY_R=19, KEY_T=20, KEY_Y=21, KEY_U=22,
// KEY_I=23, KEY_O=24, KEY_P=25
// KEY_A=30, KEY_S=31, KEY_D=32, KEY_F=33, KEY_G=34, KEY_H=35, KEY_J=36,
// KEY_K=37, KEY_L=38
// KEY_Z=44, KEY_X=45, KEY_C=46, KEY_V=47, KEY_B=48, KEY_N=49, KEY_M=50
// KEY_LEFTCTRL=29, KEY_LEFTSHIFT=42, KEY_LEFTALT=56

static const int NUM_KEYS = 88;

// Index = noteNumber - 21 (0..87). Maps full 88-key piano to Roblox QWERTY.
static const KeyMapping g_keyMap[NUM_KEYS] = {
    // ── A0 (notes 21-35): Ctrl + key ──
    {KEY_1, MOD_CTRL},   // 21 A0  = ctrl_1
    {KEY_2, MOD_CTRL},   // 22 A#0 = ctrl_2
    {KEY_3, MOD_CTRL},   // 23 B0  = ctrl_3
    {KEY_4, MOD_CTRL},   // 24 C1  = ctrl_4
    {KEY_5, MOD_CTRL},   // 25 C#1 = ctrl_5
    {KEY_6, MOD_CTRL},   // 26 D1  = ctrl_6
    {KEY_7, MOD_CTRL},   // 27 D#1 = ctrl_7
    {KEY_8, MOD_CTRL},   // 28 E1  = ctrl_8
    {KEY_9, MOD_CTRL},   // 29 F1  = ctrl_9
    {KEY_0, MOD_CTRL},   // 30 F#1 = ctrl_0
    {KEY_Q, MOD_CTRL},   // 31 G1  = ctrl_q
    {KEY_W, MOD_CTRL},   // 32 G#1 = ctrl_w
    {KEY_E, MOD_CTRL},   // 33 A1  = ctrl_e
    {KEY_R, MOD_CTRL},   // 34 A#1 = ctrl_r
    {KEY_T, MOD_CTRL},   // 35 B1  = ctrl_t

    // ── C2 to C7 (notes 36-96): normal + shift ──
    {KEY_1, MOD_NONE},   // 36 C2  = 1
    {KEY_1, MOD_SHIFT},  // 37 C#2 = !
    {KEY_2, MOD_NONE},   // 38 D2  = 2
    {KEY_2, MOD_SHIFT},  // 39 D#2 = @
    {KEY_3, MOD_NONE},   // 40 E2  = 3
    {KEY_4, MOD_NONE},   // 41 F2  = 4
    {KEY_4, MOD_SHIFT},  // 42 F#2 = $
    {KEY_5, MOD_NONE},   // 43 G2  = 5
    {KEY_5, MOD_SHIFT},  // 44 G#2 = %
    {KEY_6, MOD_NONE},   // 45 A2  = 6
    {KEY_6, MOD_SHIFT},  // 46 A#2 = ^
    {KEY_7, MOD_NONE},   // 47 B2  = 7
    {KEY_8, MOD_NONE},   // 48 C3  = 8
    {KEY_8, MOD_SHIFT},  // 49 C#3 = *
    {KEY_9, MOD_NONE},   // 50 D3  = 9
    {KEY_9, MOD_SHIFT},  // 51 D#3 = (
    {KEY_0, MOD_NONE},   // 52 E3  = 0
    {KEY_Q, MOD_NONE},   // 53 F3  = q
    {KEY_Q, MOD_SHIFT},  // 54 F#3 = Q
    {KEY_W, MOD_NONE},   // 55 G3  = w
    {KEY_W, MOD_SHIFT},  // 56 G#3 = W
    {KEY_E, MOD_NONE},   // 57 A3  = e
    {KEY_E, MOD_SHIFT},  // 58 A#3 = E
    {KEY_R, MOD_NONE},   // 59 B3  = r
    {KEY_T, MOD_NONE},   // 60 C4  = t
    {KEY_T, MOD_SHIFT},  // 61 C#4 = T
    {KEY_Y, MOD_NONE},   // 62 D4  = y
    {KEY_Y, MOD_SHIFT},  // 63 D#4 = Y
    {KEY_U, MOD_NONE},   // 64 E4  = u
    {KEY_I, MOD_NONE},   // 65 F4  = i
    {KEY_I, MOD_SHIFT},  // 66 F#4 = I
    {KEY_O, MOD_NONE},   // 67 G4  = o
    {KEY_O, MOD_SHIFT},  // 68 G#4 = O
    {KEY_P, MOD_NONE},   // 69 A4  = p
    {KEY_P, MOD_SHIFT},  // 70 A#4 = P
    {KEY_A, MOD_NONE},   // 71 B4  = a
    {KEY_S, MOD_NONE},   // 72 C5  = s
    {KEY_S, MOD_SHIFT},  // 73 C#5 = S
    {KEY_D, MOD_NONE},   // 74 D5  = d
    {KEY_D, MOD_SHIFT},  // 75 D#5 = D
    {KEY_F, MOD_NONE},   // 76 E5  = f
    {KEY_G, MOD_NONE},   // 77 F5  = g
    {KEY_G, MOD_SHIFT},  // 78 F#5 = G
    {KEY_H, MOD_NONE},   // 79 G5  = h
    {KEY_H, MOD_SHIFT},  // 80 G#5 = H
    {KEY_J, MOD_NONE},   // 81 A5  = j
    {KEY_J, MOD_SHIFT},  // 82 A#5 = J
    {KEY_K, MOD_NONE},   // 83 B5  = k
    {KEY_L, MOD_NONE},   // 84 C6  = l
    {KEY_L, MOD_SHIFT},  // 85 C#6 = L
    {KEY_Z, MOD_NONE},   // 86 D6  = z
    {KEY_Z, MOD_SHIFT},  // 87 D#6 = Z
    {KEY_X, MOD_NONE},   // 88 E6  = x
    {KEY_C, MOD_NONE},   // 89 F6  = c
    {KEY_C, MOD_SHIFT},  // 90 F#6 = C
    {KEY_V, MOD_NONE},   // 91 G6  = v
    {KEY_V, MOD_SHIFT},  // 92 G#6 = V
    {KEY_B, MOD_NONE},   // 93 A6  = b
    {KEY_B, MOD_SHIFT},  // 94 A#6 = B
    {KEY_N, MOD_NONE},   // 95 B6  = n
    {KEY_M, MOD_NONE},   // 96 C7  = m

    // ── C#7 to C8 (notes 97-108): Ctrl + key ──
    {KEY_Y, MOD_CTRL},   // 97  C#7 = ctrl_y
    {KEY_U, MOD_CTRL},   // 98  D7  = ctrl_u
    {KEY_I, MOD_CTRL},   // 99  D#7 = ctrl_i
    {KEY_O, MOD_CTRL},   // 100 E7  = ctrl_o
    {KEY_P, MOD_CTRL},   // 101 F7  = ctrl_p
    {KEY_A, MOD_CTRL},   // 102 F#7 = ctrl_a
    {KEY_S, MOD_CTRL},   // 103 G7  = ctrl_s
    {KEY_D, MOD_CTRL},   // 104 G#7 = ctrl_d
    {KEY_F, MOD_CTRL},   // 105 A7  = ctrl_f
    {KEY_G, MOD_CTRL},   // 106 A#7 = ctrl_g
    {KEY_H, MOD_CTRL},   // 107 B7  = ctrl_h
    {KEY_J, MOD_CTRL},   // 108 C8  = ctrl_j
};

// Modifier keycode lookup
static inline __u16 modifierKeycode(ModType mod) {
    switch (mod) {
        case MOD_SHIFT: return KEY_LEFTSHIFT;
        case MOD_CTRL:  return KEY_LEFTCTRL;
        default:        return 0;
    }
}

// ============================================================================
// VELOCITY (Alt + velKey, 32-step) — same musical semantics as v1
// ============================================================================

static const __u16 KEY_MOD_ALT = KEY_LEFTALT;

// 32 velocity levels mapped to keycodes: 1234567890 qwertyuiop asdfghjkl zxc
static const __u16 g_velKeys[32] = {
    KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9, KEY_0,
    KEY_Q, KEY_W, KEY_E, KEY_R, KEY_T, KEY_Y, KEY_U, KEY_I, KEY_O, KEY_P,
    KEY_A, KEY_S, KEY_D, KEY_F, KEY_G, KEY_H, KEY_J, KEY_K, KEY_L,
    KEY_Z, KEY_X, KEY_C,
};

// Last velocity key sent (-1 = none). Only re-emit when level changes.
static int g_lastVelIdx = -1;

// MIDI velocity (1..127) → index (0..31)
static inline int velocityToIndex(int velocity) {
    int idx = (velocity * 32) / 128;
    if (idx < 0) idx = 0;
    if (idx > 31) idx = 31;
    return idx;
}

// Tap Alt+velKey: press and release in two SYN frames.
static void tapVelocityKey(__u16 velKeycode) {
    // Frame 1: Alt down + velKey down
    struct input_event ev[3];
    memset(ev, 0, sizeof(ev));
    ev[0].type = EV_KEY; ev[0].code = KEY_MOD_ALT; ev[0].value = 1;
    ev[1].type = EV_KEY; ev[1].code = velKeycode;  ev[1].value = 1;
    ev[2].type = EV_SYN; ev[2].code = SYN_REPORT;  ev[2].value = 0;
    uinputWrite(ev, sizeof(ev));

    // Frame 2: Alt up + velKey up
    ev[0].type = EV_KEY; ev[0].code = velKeycode;  ev[0].value = 0;
    ev[1].type = EV_KEY; ev[1].code = KEY_MOD_ALT; ev[1].value = 0;
    ev[2].type = EV_SYN; ev[2].code = SYN_REPORT;  ev[2].value = 0;
    uinputWrite(ev, sizeof(ev));
}

// ============================================================================
// HELD KEYS STATE — tracks which physical keys are currently pressed
// ============================================================================

// Each entry: which MIDI note "owns" this physical key press.
// Key: Linux keycode. Value: MIDI note number that pressed it (or -1 if free).
// Fixed-size array indexed by keycode (max KEY_MAX ~767 in Linux headers,
// but we only use up to ~88 so a simple array for keycodes up to 128 suffices).
static const int KEYCODE_TABLE_SIZE = 128;
static int g_keyOwner[KEYCODE_TABLE_SIZE]; // -1 = not held
static std::mutex g_mutex;

// ============================================================================
// PIANO ROOMS MODE (numpad encoding) — unchanged semantics from v1
// ============================================================================

// Numpad keycodes for Piano Rooms encoding
static const __u16 g_numpadKeys[] = {
    KEY_KP0,         // '0'
    KEY_KP1,         // '1'
    KEY_KP2,         // '2'
    KEY_KP3,         // '3'
    KEY_KP4,         // '4'
    KEY_KP5,         // '5'
    KEY_KP6,         // '6'
    KEY_KP7,         // '7'
    KEY_KP8,         // '8'
    KEY_KP9,         // '9'
    KEY_KPMINUS,     // '-' (index 10)
    KEY_KPPLUS,      // '+' (index 11)
    KEY_KPASTERISK,  // '*' (index 12)
};

// Map a character from the encoded string to a numpad keycode
static __u16 charToNumpad(char c) {
    switch (c) {
        case '0': return KEY_KP0;
        case '1': return KEY_KP1;
        case '2': return KEY_KP2;
        case '3': return KEY_KP3;
        case '4': return KEY_KP4;
        case '5': return KEY_KP5;
        case '6': return KEY_KP6;
        case '7': return KEY_KP7;
        case '8': return KEY_KP8;
        case '9': return KEY_KP9;
        case '-': return KEY_KPMINUS;
        case '+': return KEY_KPPLUS;
        case '*': return KEY_KPASTERISK;
        default:  return 0;
    }
}

// ============================================================================
// UINPUT DEVICE CREATION / DESTRUCTION
// ============================================================================

static bool createUinputDevice() {
    g_uinputFd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_uinputFd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Failed to open /dev/uinput: errno=%d (%s)", errno, strerror(errno));
        return false;
    }

    // Enable EV_KEY
    if (ioctl(g_uinputFd, UI_SET_EVBIT, EV_KEY) < 0) goto fail;

    // Enable all keycodes we need:
    // Letters A-Z
    for (int k = KEY_Q; k <= KEY_P; k++) ioctl(g_uinputFd, UI_SET_KEYBIT, k); // top row
    for (int k = KEY_A; k <= KEY_L; k++) ioctl(g_uinputFd, UI_SET_KEYBIT, k); // middle row
    for (int k = KEY_Z; k <= KEY_M; k++) ioctl(g_uinputFd, UI_SET_KEYBIT, k); // bottom row

    // Numbers 1-0
    for (int k = KEY_1; k <= KEY_0; k++) ioctl(g_uinputFd, UI_SET_KEYBIT, k);

    // Modifiers
    ioctl(g_uinputFd, UI_SET_KEYBIT, KEY_LEFTSHIFT);
    ioctl(g_uinputFd, UI_SET_KEYBIT, KEY_LEFTCTRL);
    ioctl(g_uinputFd, UI_SET_KEYBIT, KEY_LEFTALT);

    // Numpad (for Piano Rooms mode)
    for (int k = KEY_KP0; k <= KEY_KP9; k++) ioctl(g_uinputFd, UI_SET_KEYBIT, k);
    ioctl(g_uinputFd, UI_SET_KEYBIT, KEY_KPMINUS);
    ioctl(g_uinputFd, UI_SET_KEYBIT, KEY_KPPLUS);
    ioctl(g_uinputFd, UI_SET_KEYBIT, KEY_KPASTERISK);

    {
        struct uinput_setup usetup;
        memset(&usetup, 0, sizeof(usetup));
        snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "PapianoVirtualKB");
        usetup.id.bustype = BUS_VIRTUAL;
        usetup.id.vendor  = 0x1234;
        usetup.id.product = 0x5678;
        usetup.id.version = 2;

        if (ioctl(g_uinputFd, UI_DEV_SETUP, &usetup) < 0) goto fail;
        if (ioctl(g_uinputFd, UI_DEV_CREATE) < 0) goto fail;
    }

    // Init held-key state
    for (int i = 0; i < KEYCODE_TABLE_SIZE; i++) g_keyOwner[i] = -1;
    g_lastVelIdx = -1;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "uinput device created: PapianoVirtualKB (no 6KRO, scoped modifiers)");
    return true;

fail:
    __android_log_print(ANDROID_LOG_ERROR, TAG,
        "uinput setup failed: errno=%d (%s)", errno, strerror(errno));
    close(g_uinputFd);
    g_uinputFd = -1;
    return false;
}

static bool destroyUinputDevice() {
    if (g_uinputFd < 0) return false;

    // Release all held keys
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        for (int i = 0; i < KEYCODE_TABLE_SIZE; i++) {
            if (g_keyOwner[i] >= 0) {
                emitEvent(EV_KEY, (__u16)i, 0); // key up
                g_keyOwner[i] = -1;
            }
        }
        emitSync();
        g_lastVelIdx = -1;
    }

    ioctl(g_uinputFd, UI_DEV_DESTROY);
    close(g_uinputFd);
    g_uinputFd = -1;

    __android_log_print(ANDROID_LOG_INFO, TAG, "uinput device destroyed");
    return true;
}

// ============================================================================
// CORE: nativeQwertyKey — the per-note hot path
// ============================================================================
//
// SCOPED MODIFIER approach:
//   Press: [modifier↓ +] key↓ + SYN → [modifier↑ + SYN]
//   Release: key↑ + SYN
//
// The modifier (Shift/Ctrl) is held only for ONE SYN_REPORT frame — just
// long enough for the game to read it at InputBegan. Then it's released so
// it doesn't pollute other held keys.
//
// KEY COLLISION handling:
//   If note A and note B map to the same physical key (same keycode, different
//   modifier), the newer note evicts the older one. The physical key is released
//   and re-pressed with the new modifier context. This is unavoidable — one
//   physical key can't be in two states. For Etude Op.10 No.4, same-key
//   collisions (adjacent semitones held together) are rare and short-lived.
//
// VELOCITY:
//   If velocity > 0, tap Alt+velKey BEFORE the note (same as v1). Only re-emits
//   when velocity level changes from last note.
// ============================================================================

static void pressKey(int noteNumber, const KeyMapping &km) {
    __u16 kc = km.keycode;
    if (kc >= KEYCODE_TABLE_SIZE) return;

    // Key collision: if this physical key is already held by a different note,
    // release it first. (Same note re-pressed = just update ownership.)
    if (g_keyOwner[kc] >= 0 && g_keyOwner[kc] != noteNumber) {
        emitEvent(EV_KEY, kc, 0); // release old
        emitSync();
    }

    // Build press batch
    if (km.mod != MOD_NONE) {
        __u16 modKc = modifierKeycode(km.mod);
        // Frame: modifier↓ + key↓ + SYN
        struct input_event ev[3];
        memset(ev, 0, sizeof(ev));
        ev[0].type = EV_KEY; ev[0].code = modKc; ev[0].value = 1;
        ev[1].type = EV_KEY; ev[1].code = kc;    ev[1].value = 1;
        ev[2].type = EV_SYN; ev[2].code = SYN_REPORT; ev[2].value = 0;
        uinputWrite(ev, sizeof(ev));

        // Immediately release modifier (scoped tap)
        struct input_event rel[2];
        memset(rel, 0, sizeof(rel));
        rel[0].type = EV_KEY; rel[0].code = modKc; rel[0].value = 0;
        rel[1].type = EV_SYN; rel[1].code = SYN_REPORT; rel[1].value = 0;
        uinputWrite(rel, sizeof(rel));
    } else {
        // No modifier — just key↓ + SYN
        struct input_event ev[2];
        memset(ev, 0, sizeof(ev));
        ev[0].type = EV_KEY; ev[0].code = kc; ev[0].value = 1;
        ev[1].type = EV_SYN; ev[1].code = SYN_REPORT; ev[1].value = 0;
        uinputWrite(ev, sizeof(ev));
    }

    g_keyOwner[kc] = noteNumber;
}

static void releaseKey(int noteNumber, const KeyMapping &km) {
    __u16 kc = km.keycode;
    if (kc >= KEYCODE_TABLE_SIZE) return;

    // Only release if THIS note still owns the key (collision eviction may
    // have already released it).
    if (g_keyOwner[kc] != noteNumber) return;

    struct input_event ev[2];
    memset(ev, 0, sizeof(ev));
    ev[0].type = EV_KEY; ev[0].code = kc; ev[0].value = 0;
    ev[1].type = EV_SYN; ev[1].code = SYN_REPORT; ev[1].value = 0;
    uinputWrite(ev, sizeof(ev));

    g_keyOwner[kc] = -1;
}

// ============================================================================
// PIANO ROOMS: send encoded numpad key sequence
// ============================================================================

// Split encoded string so no segment contains duplicate characters (same as v1)
static std::vector<std::string> splitString(const std::string &input) {
    std::vector<std::string> result;
    result.reserve(input.length());
    std::string current;
    uint32_t current_bits = 0;

    for (char c : input) {
        uint32_t bit_index = (uint32_t)(c - '*');
        if (bit_index < 32 && !(current_bits & (1u << bit_index))) {
            current += c;
            current_bits |= (1u << bit_index);
        } else {
            if (!current.empty()) result.push_back(current);
            current = c;
            current_bits = (bit_index < 32) ? (1u << bit_index) : 0;
        }
    }
    if (!current.empty()) result.push_back(current);
    return result;
}

static void sendNumpadSequence(const std::string &encoded) {
    std::vector<std::string> segments = splitString(encoded);

    for (const auto &seg : segments) {
        // Press all keys in segment
        for (char c : seg) {
            __u16 kc = charToNumpad(c);
            if (kc) emitEvent(EV_KEY, kc, 1);
        }
        emitSync();

        // Release all keys in segment
        for (char c : seg) {
            __u16 kc = charToNumpad(c);
            if (kc) emitEvent(EV_KEY, kc, 0);
        }
        emitSync();
    }
}

// ============================================================================
// JNI EXPORTS — signatures MUST match GamePadNative.java exactly
// ============================================================================

// Mode tracking (kept for interface compat; only QWERTY is performance-critical)
static int g_currentMode = 0;
static bool g_deviceCreated = false;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativeCreateUHid(JNIEnv *env, jclass clazz) {
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "nativeCreateUHid called — creating uinput device (v2 rewrite)");

    // Boost daemon process priority
    setpriority(PRIO_PROCESS, 0, -19);

    if (g_deviceCreated) {
        // Already created — re-create
        destroyUinputDevice();
    }

    g_deviceCreated = createUinputDevice();
    return g_deviceCreated ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativeCloseUHid(JNIEnv *env, jclass clazz) {
    bool ok = destroyUinputDevice();
    g_deviceCreated = false;
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativeQwertyKey(JNIEnv *env,
                                                             jclass thiz,
                                                             jint noteNumber,
                                                             jboolean isDown,
                                                             jint velocity) {
    if (noteNumber < 21 || noteNumber > 108) return;
    if (g_uinputFd < 0) return;

    // Per-thread priority boost (binder pool threads)
    static thread_local bool boosted = false;
    if (!boosted) {
        boosted = true;
        setpriority(PRIO_PROCESS, 0, -19);
    }

    const int idx = noteNumber - 21;
    const KeyMapping &km = g_keyMap[idx];

    std::lock_guard<std::mutex> lock(g_mutex);

    if (isDown) {
        // Velocity: tap Alt+velKey if level changed
        if (velocity > 0) {
            int velIdx = velocityToIndex(velocity);
            if (velIdx != g_lastVelIdx) {
                g_lastVelIdx = velIdx;
                tapVelocityKey(g_velKeys[velIdx]);
            }
        }
        pressKey(noteNumber, km);
    } else {
        releaseKey(noteNumber, km);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativePianoRoomsKey(JNIEnv *env,
                                                                  jclass thiz,
                                                                  jintArray noteIntArray) {
    if (g_uinputFd < 0) return;

    jint *notes = env->GetIntArrayElements(noteIntArray, nullptr);
    jsize len = env->GetArrayLength(noteIntArray);
    if (notes == nullptr || len == 0) {
        if (notes) env->ReleaseIntArrayElements(noteIntArray, notes, 0);
        return;
    }

    // Build encoded string from note array (same encoding as v1)
    std::string encoded;
    for (int i = 0; i < len; i++) {
        int n = notes[i];
        if (n >= 0 && n <= 9) {
            encoded += (char)('0' + n);
        } else if (n == 10) {
            encoded += '-';
        } else if (n == 11) {
            encoded += '+';
        } else if (n == 12) {
            encoded += '*';
        }
    }

    env->ReleaseIntArrayElements(noteIntArray, notes, 0);

    if (!encoded.empty()) {
        std::lock_guard<std::mutex> lock(g_mutex);
        sendNumpadSequence(encoded);
    }
}

// ============================================================================
// MAIN — daemon entry point (unchanged: stdin keepalive + binder broadcast)
// ============================================================================

// Forward declarations from GamePadNative.java (these are Java-side static
// methods called reflectively from main). The actual sendBinder logic stays
// in Java; native main() just loads the lib and blocks.

// This is the native daemon's main(). It's invoked by the Java wrapper
// (GamePadNative.main) which handles binder broadcast + shutdown hooks.
// We don't redefine main here — it's in GamePadNative.java.
