#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <linux/uinput.h>
#include <cerrno>
#include <fcntl.h>
#include <poll.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <termios.h>
#include <unistd.h>
#include <linux/uhid.h>
#include <linux/input.h>
#include <jni.h>
#include <android/log.h>
#include <unordered_map>
#include <string>
#include <array>
#include <vector>
#include <mutex>
#include <atomic>
#include <thread>
#include <algorithm>
#include <sys/resource.h>
#include <sys/syscall.h>

static int uhid_fd = -1;
static struct uhid_event uhidEvent;
const char *TAG = "RobloxAndroidMidi";

// Last 8-byte HID report we sent to the kernel. Updated by writeKeyboard()
// on every actual write so it always reflects the kernel's view, even when
// the press path issues its own direct writes (e.g. velocity Alt+velKey tap).
// emitHeldReport() consults this to skip syscalls that would push an already-
// current state — e.g. a release of a note that wasn't actually held, or a
// state that's identical after the redundant 6KRO eviction path. Each saved
// write is one less event for the kernel HID dispatcher and Roblox to chew
// through, which directly translates to lower felt latency on burst sections.
static uint8_t g_lastReport[8] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// ============================================================================
// UHID write helper — retry on EAGAIN/EINTR.
// ----------------------------------------------------------------------------
// /dev/uhid was opened O_NONBLOCK (O_NDELAY). If the kernel report queue is
// momentarily full (rare burst case) write() returns -1 with errno=EAGAIN.
// Old code ignored the return value -> the note was silently dropped, which
// the user perceives as "missed a key" (much worse than a tiny extra wait).
//
// Strategy: spin-retry with sched_yield up to ~1ms total. Real-world UHID
// write should complete in microseconds; if it doesn't, we'd rather wait a
// few hundred microseconds than lose the note. Bounded so a truly broken fd
// can't hang the binder thread forever.
// ============================================================================
static inline ssize_t uhidWrite(const void *buf, size_t len) {
    if (uhid_fd < 0) return -1;
    // ~1ms total budget at 4us per yield iteration (256 attempts).
    for (int attempt = 0; attempt < 256; ++attempt) {
        ssize_t r = write(uhid_fd, buf, len);
        if (r >= 0) return r;
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            sched_yield();
            continue;
        }
        // Hard error: log once per uHID lifetime and bail.
        static std::atomic<bool> warned{false};
        bool expected = false;
        if (warned.compare_exchange_strong(expected, true)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                    "uhid write failed: errno=%d (%s)", errno, strerror(errno));
        }
        return r;
    }
    return -1;
}

// Keyboard description
static unsigned char description[] = {
        0x05, 0x01,        // Usage Page (Generic Desktop Ctrls)
        0x09, 0x06,        // Usage (Keyboard)
        0xA1, 0x01,        // Collection (Application)
        0x05, 0x07,        //   Usage Page (Kbrd/Keypad)
        0x19, 0xE0,        //   Usage Minimum (0xE0)
        0x29, 0xE7,        //   Usage Maximum (0xE7)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x08,        //   Report Count (8)
        0x81, 0x02,        //   Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95, 0x01,        //   Report Count (1)
        0x75, 0x08,        //   Report Size (8)
        0x81, 0x03,        //   Input (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95, 0x05,        //   Report Count (5)
        0x75, 0x01,        //   Report Size (1)
        0x05, 0x08,        //   Usage Page (LEDs)
        0x19, 0x01,        //   Usage Minimum (Num Lock)
        0x29, 0x05,        //   Usage Maximum (Kana)
        0x91, 0x02,        //   Output (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0x95, 0x01,        //   Report Count (1)
        0x75, 0x03,        //   Report Size (3)
        0x91, 0x03,        //   Output (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0x95, 0x06,        //   Report Count (6)
        0x75, 0x08,        //   Report Size (8)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x65,        //   Logical Maximum (101)
        0x05, 0x07,        //   Usage Page (Kbrd/Keypad)
        0x19, 0x00,        //   Usage Minimum (0x00)
        0x29, 0x65,        //   Usage Maximum (0x65)
        0x81, 0x00,        //   Input (Data,Array,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0xC0,              // End Collection

};

const int NUM_KEYS = 88;

std::array<std::string, NUM_KEYS> pianoQwertyKeys = {
        // A0
        "ctrl_1", "ctrl_2", "ctrl_3", "ctrl_4", "ctrl_5",
        "ctrl_6", "ctrl_7", "ctrl_8", "ctrl_9", "ctrl_0", "ctrl_q", "ctrl_w",
        "ctrl_e", "ctrl_r", "ctrl_t",

        // Normal keys C2 to C7
        "1", "!", "2", "@", "3", "4", "$", "5", "%",
        "6", "^", "7", "8", "*", "9", "(", "0", "q", "Q", "w", "W",
        "e", "E", "r", "t", "T", "y", "Y", "u", "i", "I", "o", "O",
        "p", "P", "a", "s", "S", "d", "D", "f", "g", "G", "h", "H",
        "j", "J", "k", "l", "L", "z", "Z", "x", "c", "C", "v", "V",
        "b", "B", "n", "m",

        // C# 7 to C8
        "ctrl_y", "ctrl_u", "ctrl_i", "ctrl_o",
        "ctrl_p", "ctrl_a", "ctrl_s", "ctrl_d", "ctrl_f", "ctrl_g", "ctrl_h",
        "ctrl_j"
};

std::unordered_map<std::string, int> qwerty_to_hex_map = {
        // A0
        {"ctrl_0", 0x27},
        {"ctrl_1", 0x1E},
        {"ctrl_2", 0x1F},
        {"ctrl_3", 0x20},
        {"ctrl_4", 0x21},
        {"ctrl_5", 0x22},
        {"ctrl_6", 0x23},
        {"ctrl_7", 0x24},
        {"ctrl_8", 0x25},
        {"ctrl_9", 0x26},
        {"ctrl_q", 0x14},
        {"ctrl_w", 0x1A},
        {"ctrl_e", 0x08},
        {"ctrl_r", 0x15},
        {"ctrl_t", 0x17},

        // Normal keys C2 to C7
        {"a", 0x04},
        {"b", 0x05},
        {"c", 0x06},
        {"d", 0x07},
        {"e", 0x08},
        {"f", 0x09},
        {"g", 0x0A},
        {"h", 0x0B},
        {"i", 0x0C},
        {"j", 0x0D},
        {"k", 0x0E},
        {"l", 0x0F},
        {"m", 0x10},
        {"n", 0x11},
        {"o", 0x12},
        {"p", 0x13},
        {"q", 0x14},
        {"r", 0x15},
        {"s", 0x16},
        {"t", 0x17},
        {"u", 0x18},
        {"v", 0x19},
        {"w", 0x1A},
        {"x", 0x1B},
        {"y", 0x1C},
        {"z", 0x1D},
        {"0", 0x27},
        {"1", 0x1E},
        {"2", 0x1F},
        {"3", 0x20},
        {"4", 0x21},
        {"5", 0x22},
        {"6", 0x23},
        {"7", 0x24},
        {"8", 0x25},
        {"9", 0x26},

        // Capitals
        {"A", 0x04},
        {"B", 0x05},
        {"C", 0x06},
        {"D", 0x07},
        {"E", 0x08},
        {"F", 0x09},
        {"G", 0x0A},
        {"H", 0x0B},
        {"I", 0x0C},
        {"J", 0x0D},
        {"K", 0x0E},
        {"L", 0x0F},
        {"M", 0x10},
        {"N", 0x11},
        {"O", 0x12},
        {"P", 0x13},
        {"Q", 0x14},
        {"R", 0x15},
        {"S", 0x16},
        {"T", 0x17},
        {"U", 0x18},
        {"V", 0x19},
        {"W", 0x1A},
        {"X", 0x1B},
        {"Y", 0x1C},
        {"Z", 0x1D},
        {")", 0x27},
        {"!", 0x1E},
        {"@", 0x1F},
        {"#", 0x20},
        {"$", 0x21},
        {"%", 0x22},
        {"^", 0x23},
        {"&", 0x24},
        {"*", 0x25},
        {"(", 0x26},

        // C# 7 to C8

        {"ctrl_y", 0x1C},
        {"ctrl_u", 0x18},
        {"ctrl_i", 0x0C},
        {"ctrl_o", 0x12},
        {"ctrl_p", 0x13},
        {"ctrl_a", 0x04},
        {"ctrl_s", 0x16},
        {"ctrl_d", 0x07},
        {"ctrl_f", 0x09},
        {"ctrl_g", 0x0A},
        {"ctrl_h", 0x0B},
        {"ctrl_j", 0x0D},
};

std::unordered_map<std::string, int> meta_key_map = {
        // C1 (left ctrl)
        {"ctrl_0", 0x01},
        {"ctrl_1", 0x01},
        {"ctrl_2", 0x01},
        {"ctrl_3", 0x01},
        {"ctrl_4", 0x01},
        {"ctrl_5", 0x01},
        {"ctrl_6", 0x01},
        {"ctrl_7", 0x01},
        {"ctrl_8", 0x01},
        {"ctrl_9", 0x01},
        {"ctrl_q", 0x01},
        {"ctrl_w", 0x01},
        {"ctrl_e", 0x01},
        {"ctrl_r", 0x01},
        {"ctrl_t", 0x01},

        {"a", 0x00},
        {"b", 0x00},
        {"c", 0x00},
        {"d", 0x00},
        {"e", 0x00},
        {"f", 0x00},
        {"g", 0x00},
        {"h", 0x00},
        {"i", 0x00},
        {"j", 0x00},
        {"k", 0x00},
        {"l", 0x00},
        {"m", 0x00},
        {"n", 0x00},
        {"o", 0x00},
        {"p", 0x00},
        {"q", 0x00},
        {"r", 0x00},
        {"s", 0x00},
        {"t", 0x00},
        {"u", 0x00},
        {"v", 0x00},
        {"w", 0x00},
        {"x", 0x00},
        {"y", 0x00},
        {"z", 0x00},
        {"0", 0x00},
        {"1", 0x00},
        {"2", 0x00},
        {"3", 0x00},
        {"4", 0x00},
        {"5", 0x00},
        {"6", 0x00},
        {"7", 0x00},
        {"8", 0x00},
        {"9", 0x00},

        // Capitals (left shift)
        {"A", 0x02},
        {"B", 0x02},
        {"C", 0x02},
        {"D", 0x02},
        {"E", 0x02},
        {"F", 0x02},
        {"G", 0x02},
        {"H", 0x02},
        {"I", 0x02},
        {"J", 0x02},
        {"K", 0x02},
        {"L", 0x02},
        {"M", 0x02},
        {"N", 0x02},
        {"O", 0x02},
        {"P", 0x02},
        {"Q", 0x02},
        {"R", 0x02},
        {"S", 0x02},
        {"T", 0x02},
        {"U", 0x02},
        {"V", 0x02},
        {"W", 0x02},
        {"X", 0x02},
        {"Y", 0x02},
        {"Z", 0x02},
        {")", 0x02},
        {"!", 0x02},
        {"@", 0x02},
        {"#", 0x02},
        {"$", 0x02},
        {"%", 0x02},
        {"^", 0x02},
        {"&", 0x02},
        {"*", 0x02},
        {"(", 0x02},

        // C# 7 to C8

        {"ctrl_y", 0x01},
        {"ctrl_u", 0x01},
        {"ctrl_i", 0x01},
        {"ctrl_o", 0x01},
        {"ctrl_p", 0x01},
        {"ctrl_a", 0x01},
        {"ctrl_s", 0x01},
        {"ctrl_d", 0x01},
        {"ctrl_f", 0x01},
        {"ctrl_g", 0x01},
        {"ctrl_h", 0x01},
        {"ctrl_j", 0x01},
};

std::unordered_map<std::string, int> numpad_to_hex_map = {
        {"*", 0x55},
        {"-", 0x56},
        {"+", 0x57},


        {"1", 0x59},
        {"2", 0x5A},
        {"3", 0x5B},
        {"4", 0x5C},
        {"5", 0x5D},
        {"6", 0x5E},
        {"7", 0x5F},
        {"8", 0x60},
        {"9", 0x61},
        {"0", 0x62},

        // 0123456789 (10) (11)
        {"10", 0x56}, // keycode of -
        {"11", 0x57}, // keycode of +
};

std::unordered_map<std::string, std::string> character_map = {
        {"*", "*"},
        {"-", "-"},
        {"+", "+"},


        {"1", "1"},
        {"2", "2"},
        {"3", "3"},
        {"4", "4"},
        {"5", "5"},
        {"6", "6"},
        {"7", "7"},
        {"8", "8"},
        {"9", "9"},
        {"0", "0"},

        {"10", "-"},
        {"11", "+"},
};

// Function to write 8 integers to the file descriptor
void writeKeyboard(int metakey0=0x00, int reserved1=0x00, int data2=0x00, int data3=0x00, int data4=0x00, int data5=0x00, int data6=0x00, int data7=0x00) {

    // https://d1.amobbs.com/bbs_upload782111/files_47/ourdev_692986N5FAHU.pdf
    // https://www.usbzh.com/article/detail-326.html

    uhidEvent.u.input.data[0] = metakey0;
    uhidEvent.u.input.data[1] = reserved1; // Reserved

    // Keyboard keys in HEX
    uhidEvent.u.input.data[2] = data2;
    uhidEvent.u.input.data[3] = data3;
    uhidEvent.u.input.data[4] = data4;
    uhidEvent.u.input.data[5] = data5;
    uhidEvent.u.input.data[6] = data6;
    uhidEvent.u.input.data[7] = data7;
    uhidWrite(&uhidEvent, sizeof(uhidEvent));

    // Track what the kernel last received so emitHeldReport()'s dedup is
    // accurate even for direct writes (e.g. velocity Alt+velKey tap).
    g_lastReport[0] = (uint8_t) metakey0;
    g_lastReport[1] = (uint8_t) reserved1;
    g_lastReport[2] = (uint8_t) data2;
    g_lastReport[3] = (uint8_t) data3;
    g_lastReport[4] = (uint8_t) data4;
    g_lastReport[5] = (uint8_t) data5;
    g_lastReport[6] = (uint8_t) data6;
    g_lastReport[7] = (uint8_t) data7;

//    return 0;
}

void writeKeyboardVector(int metakey0, int reserved1, const std::vector<int>& data) {
    if (data.size() > 6) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Error: Too many data arguments");
        return;
    }

    // https://d1.amobbs.com/bbs_upload782111/files_47/ourdev_692986N5FAHU.pdf
    // https://www.usbzh.com/article/detail-326.html

    uhidEvent.u.input.data[0] = metakey0;
    uhidEvent.u.input.data[1] = reserved1; // Reserved

    // Keyboard keys in HEX
    for (size_t i = 0; i < data.size(); i++) {
        uhidEvent.u.input.data[i + 2] = data[i];
    }

    uhidWrite(&uhidEvent, sizeof(uhidEvent));

    writeKeyboard(); // releases all the held keys
}

// TODO: Change implementation to use Vectors for both this and the original writeKeyboard function
void tapKeyboard(int metakey0=0x00, int reserved1=0x00, int data2=0x00, int data3=0x00, int data4=0x00, int data5=0x00, int data6=0x00, int data7=0x00) {
    writeKeyboard(metakey0, reserved1, data2, data3, data4, data5, data6, data7);
    writeKeyboard();
}

// ============================================================================
// QWERTY hold/sustain state
// ----------------------------------------------------------------------------
// HID keyboard report cuma support 6 key concurrently (boot-protocol 6KRO).
// Kita track semua note yang lagi di-hold di g_heldKeys, dan tiap kali ada
// press/release kita re-emit aggregated HID report dengan up-to-6 keys di-set.
//
// Limit kapasitas 6: kalau note ke-7 masuk, note PALING LAMA (oldest) di-evict
// dari held set untuk bikin ruang. Ini karena note tertua kemungkinan besar
// udah "selesai bunyi" secara persepsi (sustain udah decay). Hasilnya:
// chord besar tetep kedenger lengkap — cuma note paling awal yang di-cut.
//
// meta byte di-OR dari semua held keys (Roblox piano pake shift utk note
// hitam tertentu dan ctrl utk oktaf paling rendah/tinggi). Kalo user
// kombinasiin note dgn meta beda (mis. capital + ctrl_) hasil meta jadi
// gabungan — sayangnya HID cuma punya 1 byte modifier, ini limit fundamental.
// Use case piano normal jarang nyentuh case ini.
// ============================================================================

struct HeldKey {
    int note;   // MIDI note number (utk match release ke press yg bener)
    int meta;   // modifier byte (left ctrl=0x01, left shift=0x02, none=0x00)
    int hex;    // HID usage code
};

static std::vector<HeldKey> g_heldKeys;
static std::mutex g_heldMutex;
static const size_t HID_MAX_KEYS = 6;

// Emit aggregated HID report dari current held set.
// Caller MUST hold g_heldMutex.
static void emitHeldReport() {
    int meta = 0;
    int keys[HID_MAX_KEYS] = {0, 0, 0, 0, 0, 0};
    size_t n = std::min(g_heldKeys.size(), HID_MAX_KEYS);
    for (size_t i = 0; i < n; i++) {
        keys[i] = g_heldKeys[i].hex;
        meta  |= g_heldKeys[i].meta;
    }
    // Same-state dedup against the actual last-written kernel report.
    // writeKeyboard() updates g_lastReport, so this also correctly skips
    // the case where tapVelocityKey just sent the equivalent transition.
    uint8_t next[8] = {
        (uint8_t) meta, 0,
        (uint8_t) keys[0], (uint8_t) keys[1], (uint8_t) keys[2],
        (uint8_t) keys[3], (uint8_t) keys[4], (uint8_t) keys[5]
    };
    if (memcmp(next, g_lastReport, 8) == 0) return;
    writeKeyboard(meta, 0x00, keys[0], keys[1], keys[2], keys[3], keys[4], keys[5]);
}


// ============================================================================
// VELOCITY ("Visual Piano" / MIDI++ style) — Alt + velocity-key, 32 steps
// ----------------------------------------------------------------------------
// Game piano tertentu (Visual Piano, Piano Gardens, dll) menerima velocity
// lewat keypress TERPISAH: tap `Alt + <key>` untuk SET level velocity, lalu
// notnya ditekan biasa. Game inget level itu sampai diganti.
//
// Skema diambil dari MIDI++ (Zephkek/MIDIPlusPlus): 32 level, dipetakan ke
// deret tombol di bawah (pelan -> keras). MIDI velocity 0..127 dipetakan
// linear ke index 0..31. velKey CUMA dikirim ulang saat level berubah
// (lihat g_lastVelHex) — biar gak spam keypress tiap not.
//
// LeftAlt = bit 2 di modifier byte = 0x04 (sejajar LeftCtrl=0x01, Shift=0x02).
// ============================================================================
static const int MOD_LEFTALT = 0x04;

// HID usage code utk tiap velocity-key, index 0..31:
//   1 2 3 4 5 6 7 8 9 0  q w e r t y u i o p  a s d f g h j k l  z x c
static const int g_velKeyHex[32] = {
    0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, // 1..0
    0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13, // q w e r t y u i o p
    0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F,       // a s d f g h j k l
    0x1D, 0x1B, 0x06                                            // z x c
};

// velKey terakhir yg di-emit (-1 = belum ada). Reset di nativeCloseUHid.
static int g_lastVelHex = -1;

// MIDI velocity (1..127) -> velocity-key index (0..31), linear.
static inline int velocityToIndex(int velocity) {
    int idx = (velocity * 32) / 128;
    if (idx < 0) idx = 0;
    if (idx > 31) idx = 31;
    return idx;
}

// Kirim command "Alt + velKey" TANPA nge-release not yg lagi di-hold.
// Not yg di-hold dimasukin ke report yg sama biar tetep down (Roblox fire
// InputBegan cuma pas keycode transisi up->down; nambah modifier Alt
// sementara TIDAK nge-retrigger not yg udah down). velKey dapet transisi
// down->up fresh, jadi handler Alt+key velocity di game ke-trigger.
// Held note yg kebetulan pakai hex sama dgn velKey di-skip biar gak dobel.
// Caller MUST hold g_heldMutex.
static void tapVelocityKey(int velHex) {
    int keys[HID_MAX_KEYS] = {0, 0, 0, 0, 0, 0};
    size_t k = 0;
    // sisakan 1 slot utk velKey (max 5 held note ikut)
    for (size_t i = 0; i < g_heldKeys.size() && k < HID_MAX_KEYS - 1; i++) {
        if (g_heldKeys[i].hex == velHex) continue; // hindari duplikat keycode
        keys[k++] = g_heldKeys[i].hex;
    }
    keys[k++] = velHex;
    writeKeyboard(MOD_LEFTALT, 0x00, keys[0], keys[1], keys[2], keys[3], keys[4], keys[5]);
    // restore: Alt + velKey dilepas, not yg di-hold tetep down
    emitHeldReport();
}


// ============================================================================
// ZERO-ALLOCATION LOOKUP TABLE for nativeQwertyKey hot path
// ----------------------------------------------------------------------------
// Precomputed from pianoQwertyKeys + qwerty_to_hex_map + meta_key_map.
// Index = noteNumber - 21 (0..87). Each entry: {hid_keycode, meta_byte}.
// Eliminates: std::string alloc, hash lookup x2 per note event.
// ============================================================================
struct KeyEntry { int hex; int meta; };

static const KeyEntry g_keyLookup[NUM_KEYS] = {
    // A0 (notes 21-35) — ctrl_ prefix = meta 0x01
    {0x1E, 0x01}, // ctrl_1
    {0x1F, 0x01}, // ctrl_2
    {0x20, 0x01}, // ctrl_3
    {0x21, 0x01}, // ctrl_4
    {0x22, 0x01}, // ctrl_5
    {0x23, 0x01}, // ctrl_6
    {0x24, 0x01}, // ctrl_7
    {0x25, 0x01}, // ctrl_8
    {0x26, 0x01}, // ctrl_9
    {0x27, 0x01}, // ctrl_0
    {0x14, 0x01}, // ctrl_q
    {0x1A, 0x01}, // ctrl_w
    {0x08, 0x01}, // ctrl_e
    {0x15, 0x01}, // ctrl_r
    {0x17, 0x01}, // ctrl_t

    // Normal keys C2 to C7 (notes 36-95)
    {0x1E, 0x00}, // "1"
    {0x1E, 0x02}, // "!"
    {0x1F, 0x00}, // "2"
    {0x1F, 0x02}, // "@"
    {0x20, 0x00}, // "3"
    {0x21, 0x00}, // "4"
    {0x21, 0x02}, // "$"
    {0x22, 0x00}, // "5"
    {0x22, 0x02}, // "%"
    {0x23, 0x00}, // "6"
    {0x23, 0x02}, // "^"
    {0x24, 0x00}, // "7"
    {0x25, 0x00}, // "8"
    {0x25, 0x02}, // "*"
    {0x26, 0x00}, // "9"
    {0x26, 0x02}, // "("
    {0x27, 0x00}, // "0"
    {0x14, 0x00}, // "q"
    {0x14, 0x02}, // "Q"
    {0x1A, 0x00}, // "w"
    {0x1A, 0x02}, // "W"
    {0x08, 0x00}, // "e"
    {0x08, 0x02}, // "E"
    {0x15, 0x00}, // "r"
    {0x17, 0x00}, // "t"
    {0x17, 0x02}, // "T"
    {0x1C, 0x00}, // "y"
    {0x1C, 0x02}, // "Y"
    {0x18, 0x00}, // "u"
    {0x0C, 0x00}, // "i"
    {0x0C, 0x02}, // "I"
    {0x12, 0x00}, // "o"
    {0x12, 0x02}, // "O"
    {0x13, 0x00}, // "p"
    {0x13, 0x02}, // "P"
    {0x04, 0x00}, // "a"
    {0x16, 0x00}, // "s"
    {0x16, 0x02}, // "S"
    {0x07, 0x00}, // "d"
    {0x07, 0x02}, // "D"
    {0x09, 0x00}, // "f"
    {0x0A, 0x00}, // "g"
    {0x0A, 0x02}, // "G"
    {0x0B, 0x00}, // "h"
    {0x0B, 0x02}, // "H"
    {0x0D, 0x00}, // "j"
    {0x0D, 0x02}, // "J"
    {0x0E, 0x00}, // "k"
    {0x0F, 0x00}, // "l"
    {0x0F, 0x02}, // "L"
    {0x1D, 0x00}, // "z"
    {0x1D, 0x02}, // "Z"
    {0x1B, 0x00}, // "x"
    {0x06, 0x00}, // "c"
    {0x06, 0x02}, // "C"
    {0x19, 0x00}, // "v"
    {0x19, 0x02}, // "V"
    {0x05, 0x00}, // "b"
    {0x05, 0x02}, // "B"
    {0x11, 0x00}, // "n"
    {0x10, 0x00}, // "m"

    // C#7 to C8 (notes 97-108) — ctrl_ prefix = meta 0x01
    {0x1C, 0x01}, // ctrl_y
    {0x18, 0x01}, // ctrl_u
    {0x0C, 0x01}, // ctrl_i
    {0x12, 0x01}, // ctrl_o
    {0x13, 0x01}, // ctrl_p
    {0x04, 0x01}, // ctrl_a
    {0x16, 0x01}, // ctrl_s
    {0x07, 0x01}, // ctrl_d
    {0x09, 0x01}, // ctrl_f
    {0x0A, 0x01}, // ctrl_g
    {0x0B, 0x01}, // ctrl_h
    {0x0D, 0x01}, // ctrl_j
};

// This function slices a string in such a way that no keys are repeated
// For example, *6629*6000 becomes
// *6 629* 60 0 0
std::vector<std::string> splitString(const std::string& input) {
    std::vector<std::string> result;
    result.reserve(input.length());
    std::string current;
    uint32_t current_bits = 0;

    for (char c : input) {
        uint32_t bit_index = static_cast<uint32_t>(c - '*');
        if (!(current_bits & (1 << bit_index))) {
            current += c;
            current_bits |= (1 << bit_index);
        } else {
            result.push_back(current);
            current = c;
            current_bits = (1 << bit_index);
        }
    }

    result.push_back(current);

    return result;
}

// Function to write 8 integers to the file descriptor
void SendEncodedKeys(std::string encodedCharacters) {
    // encodedCharacters = *6629*6000

    std::vector<std::string> result = splitString(encodedCharacters);
    //*6
    //629*
    //60
    //0
    //0

    for (const auto& stringSequence : result) {
        // loop through the string and convert each character into the corresponding hex key
        // store each one in a vector then call a function which'd process the vector
        std::vector<int> hex_values;

        for (char c : stringSequence) {
            std::string key = std::string(1, c);
            hex_values.push_back(numpad_to_hex_map[key]);


        }

        writeKeyboardVector(0x00, 0x00, hex_values);

    }
//    __android_log_print(ANDROID_LOG_WARN, TAG, "%d %d %d %d", a,b,c,d);
}

// ============================================================================
// UHID kernel-event drain thread.
// ----------------------------------------------------------------------------
// /dev/uhid is bidirectional: write() sends HID reports TO the kernel; read()
// receives UHID_START / UHID_STOP / UHID_OPEN / UHID_CLOSE / UHID_OUTPUT
// events FROM the kernel (e.g. when an app starts using the keyboard, or
// requests a LED state change).
//
// If we never read these, they accumulate in the kernel side buffer. On most
// kernels that's bounded and harmless, but on some Android variants a full
// queue starts to throttle writes — exactly the kind of subtle latency
// regression that "feels parah" without showing up in any single benchmark.
//
// A dedicated drain thread blocks on read() and tosses every event. Tiny
// (~1 syscall per app focus change) and self-terminates when uhid_fd closes.
// ============================================================================
static std::thread g_drainThread;
static std::atomic<bool> g_drainRun{false};

static void uhidDrainLoop(int fd) {
    struct pollfd pfd;
    while (g_drainRun.load(std::memory_order_relaxed)) {
        pfd.fd = fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, 500);
        if (pr <= 0) continue; // timeout or interrupted
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (pfd.revents & POLLIN) {
            struct uhid_event ev;
            ssize_t n = read(fd, &ev, sizeof(ev));
            if (n < 0) {
                if (errno == EAGAIN || errno == EINTR) continue;
                break;
            }
            // We don't care WHAT the kernel sent — discarding is fine. The
            // point is just to keep the queue empty so no back-pressure
            // ever bleeds into write() latency.
        }
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativeCreateUHid(JNIEnv *env, jclass clazz) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "HELLO WORLD THIS IS FROM JNI");

    // Daemon process priority: this JNI runs inside the helper daemon spawned
    // by Shizuku/root. Boost the WHOLE process to URGENT_AUDIO-equivalent
    // nice (-19) once at startup so every binder thread it spins up later
    // inherits the low-jitter scheduling. Per-thread boost in nativeQwertyKey
    // is still kept as a belt-and-suspenders for new threads added by the
    // binder pool. setpriority(PRIO_PROCESS,0,...) without CAP_SYS_NICE is a
    // no-op (returns -1) — safe to call unconditionally.
    setpriority(PRIO_PROCESS, 0, -19);

    // Stop any drain thread from a previous Create (e.g. uHID re-create after
    // a daemon respawn). It was polling the now-stale fd; spawn a fresh one
    // for the new fd below. Idempotent if no thread was running.
    if (g_drainRun.exchange(false)) {
        if (g_drainThread.joinable()) {
            try { g_drainThread.join(); } catch (...) {}
        }
    }

    if ((uhid_fd = open("/dev/uhid", O_RDWR | O_NDELAY)) < 0) {
        return false;//error process.
    }
    struct uhid_event ev;
    memset(&ev, 0, sizeof(uhid_event));
    ev.type = UHID_CREATE;
    strcpy((char *) ev.u.create.name, "uHidKeyboard");
    ev.u.create.rd_data = description;
    ev.u.create.rd_size = sizeof(description);
    ev.u.create.bus = 0x03;
    ev.u.create.vendor = 0x1;
    ev.u.create.product = 0x1;
    ev.u.create.version = 0x1;
    ev.u.create.country = 0;
    if (write(uhid_fd, &ev, sizeof(ev)) != sizeof(uhid_event)) {
        return false;
    }

    memset(&uhidEvent, 0, sizeof(uhid_event));
    uhidEvent.type = UHID_INPUT;
    uhidEvent.u.input.size = 8;
    uhidEvent.u.input.data[0] = 0x00;

    // Reset last-report cache so dedup doesn't think the brand-new uHID
    // device already received {0,0,0,0,0,0,0,0}. (Fresh device starts in
    // unknown state — first emit must hit the wire.)
    memset(g_lastReport, 0xFF, sizeof(g_lastReport));

    // Spawn a fresh drain thread for THIS fd.
    g_drainRun.store(true);
    int fd_copy = uhid_fd;
    g_drainThread = std::thread([fd_copy]() { uhidDrainLoop(fd_copy); });

    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativeCloseUHid(JNIEnv *env, jclass clazz) {

    // Stop drain thread first so it doesn't race against fd close.
    g_drainRun.store(false);
    if (g_drainThread.joinable()) {
        try { g_drainThread.join(); } catch (...) {}
    }

    // Clear held-keys state biar gak ada stale data pas uHID di-recreate.
    {
        std::lock_guard<std::mutex> lock(g_heldMutex);
        g_heldKeys.clear();
        g_lastVelHex = -1; // reset velocity level tracking
        memset(g_lastReport, 0, sizeof(g_lastReport));
    }

    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_DESTROY;
    ssize_t r = (uhid_fd >= 0) ? write(uhid_fd, &ev, sizeof(uhid_event)) : -1;
    if (uhid_fd >= 0) { close(uhid_fd); uhid_fd = -1; }
    return r > 0;
}


// qwerty mode function [works on most roblox piano games]
// RobloxMidiConnect(Piano Rooms) mode function [made specifically for Piano Rooms' MidiConnect functionality]

extern "C"
JNIEXPORT void JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativeQwertyKey(JNIEnv *env,
                                                     jclass thiz,
                                                     jint noteNumber,
                                                     jboolean isDown,
                                                     jint velocity) {
    if (!(noteNumber >= 21 && noteNumber <= 108)) {
        return; // silent drop — no log on hot path
    }
    // Range is 21..108 inclusive = the full 88-key piano (A0..C8).
    // g_keyLookup has NUM_KEYS(88) entries: index 0 = note 21 (A0),
    // index 87 = note 108 (C8 -> ctrl_j). Capping at 107 used to drop C8.

    // Latency/jitter: daemon nanganin note event lewat binder oneway dari MIDI
    // dispatcher. Binder pakai thread POOL, jadi boost dilakukan PER-THREAD
    // (thread_local) sekali doang tiap binder thread yg pernah ngelayanin not.
    // Naikin nice ke -19 (setara URGENT_AUDIO) biar thread injeksi HID gak
    // kena preempt → ngurangin jitter di sisi daemon.
    //
    // setpriority(PRIO_PROCESS, 0, ...) di Linux = nice thread pemanggil
    // (nice itu per-task). CUMA nice value, bukan RT scheduling → gak bisa
    // nge-starve sistem. Kalau proses gak punya CAP_SYS_NICE, panggilan gagal
    // graceful (return -1) tanpa efek samping — thread tetap di prioritas normal.
    static thread_local bool t_prioBoosted = false;
    if (!t_prioBoosted) {
        t_prioBoosted = true;
        setpriority(PRIO_PROCESS, 0, -19);
    }

    // Zero-allocation lookup: direct array index, no string, no hash
    const KeyEntry& key = g_keyLookup[noteNumber - 21];

    std::lock_guard<std::mutex> lock(g_heldMutex);

    if (isDown) {
        // Dedupe: skip if already held
        for (const auto& h : g_heldKeys) {
            if (h.note == noteNumber) return;
        }

        // VELOCITY: velocity>0 artinya Visual Piano velocity mode aktif untuk
        // event ini (gating ON/OFF dilakukan di tuoluoyiService — OFF kirim 0).
        // Tap Alt+velKey SEBELUM not ditekan, dan cuma kalau level berubah.
        // Dilakukan sebelum push not baru supaya tapVelocityKey cuma bawa not
        // yg lagi bener-bener di-hold (bukan not baru ini).
        if (velocity > 0) {
            int velHex = g_velKeyHex[velocityToIndex(velocity)];
            if (velHex != g_lastVelHex) {
                g_lastVelHex = velHex;
                tapVelocityKey(velHex);
            }
        }

        // Meta collision fix: if new note has a DIFFERENT modifier than
        // what's currently held, drop the held set first. HID report only
        // has 1 meta byte shared across all 6 keys — mixing notes with
        // different modifiers causes wrong key interpretation.
        // e.g. holding "q" (meta=0x00) + pressing "ctrl_1" (meta=0x01)
        // would make Roblox see Ctrl+q = wrong note.
        // Roblox sustain is absolute (hold=sound, release=stop) so
        // dropping doesn't cause audio gaps — notes just get re-triggered.
        //
        // HEX-COLLISION CASE (the subtle one):
        //   Many natural+sharp pairs share the SAME HID keycode and only
        //   differ by modifier — e.g. note 69 (A4 = "p") and note 70
        //   (Bb4 = "P") both use 0x13. If user plays A→Bb with A still
        //   held (legato), the next emitHeldReport would write a report
        //   that only flips the modifier; key 0x13 stays down across
        //   reports so the kernel emits NO KEY_P transition and Roblox's
        //   InputBegan handler never fires for the new note → Bb is
        //   silent even though shift went down. We MUST insert an
        //   intermediate empty report so the shared key cycles up→down,
        //   forcing Roblox to see a fresh InputBegan with the new
        //   modifier state.
        //
        //   When there's NO hex collision (e.g. holding "q" then
        //   pressing "!"), the single emitHeldReport at the end of the
        //   press path already produces a clean release-old + press-new
        //   transition in one report — no intermediate write needed.
        if (!g_heldKeys.empty()) {
            int currentMeta = 0;
            bool hexCollision = false;
            for (const auto& h : g_heldKeys) {
                currentMeta |= h.meta;
                if (h.hex == key.hex) hexCollision = true;
            }
            if (currentMeta != key.meta) {
                g_heldKeys.clear();
                if (hexCollision) {
                    writeKeyboard(); // empty report — force the shared key to cycle
                }
            }
        }

        // 6KRO limit: evict oldest held note to make room for new one.
        if (g_heldKeys.size() >= HID_MAX_KEYS) {
            g_heldKeys.erase(g_heldKeys.begin());
        }

        g_heldKeys.push_back({static_cast<int>(noteNumber), key.meta, key.hex});
        emitHeldReport();
    } else {
        size_t before = g_heldKeys.size();
        g_heldKeys.erase(
            std::remove_if(g_heldKeys.begin(), g_heldKeys.end(),
                [noteNumber](const HeldKey& h) { return h.note == noteNumber; }),
            g_heldKeys.end()
        );
        if (g_heldKeys.size() != before) {
            emitHeldReport();
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lisztomaniaaa_papiano_GamePadNative_nativePianoRoomsKey(JNIEnv *env,
                                                         jclass thiz,
                                                         jintArray noteInfo) {
    //    # We are dividing by 12 because we will be encoding it with 12 keys only(The keys are "0123456789-+")
    //    # Additionally, it adds up nicely because there are 12 semitones in one octave
    //
    //    # C0 aka Note C at Octave 0
    //    # Note number: 12
    //    # O = msg.note / 12 = 12 / 12 = 1
    //    # Octave = 1 - O = 1 - 1 = 0
    //
    //    # C4 aka Note C at Octave 4
    //    # Note number: 60
    //    # O = msg.note / 12 = 60 /12 = 5
    //    # Octave = 1 - O = 5 - 1 = 4
    //
    //    # To decode
    //    # (DIV_VAL * 12) + MODULOS_VAL

    // NOTE: Because we explicitly declared the arguments as an int, C++ automatically discards
    // the decimal bit for us allowing us to skip having to floor(this wouldn't be possible if the values given were negative)

    jsize length = env->GetArrayLength(noteInfo);

    jint *int_elements = env->GetIntArrayElements(noteInfo, NULL);

    std::string encodedCharacters;

    for (int i = 0; i < length; i+=3) {
        int isDown = int_elements[i];
        int noteNumber = int_elements[i+1];
        int velocity = int_elements[i+2];


        int EncodedOctaveNo = noteNumber / 12;
        int EncodedNoteNo = noteNumber % 12;

        // Velocity defaults to 0 which tells the game that the notes are no longer being held
        int EncodedVelocityA = 0;
        int EncodedVelocityB = 0;

        if (isDown) {
            EncodedVelocityA = velocity / 12;
            EncodedVelocityB = velocity % 12;
        }

        encodedCharacters += '*';

        encodedCharacters += character_map[std::to_string(EncodedOctaveNo)];
        encodedCharacters += character_map[std::to_string(EncodedNoteNo)];
        encodedCharacters += character_map[std::to_string(EncodedVelocityA)];
        encodedCharacters += character_map[std::to_string(EncodedVelocityB)];
    }



    SendEncodedKeys(encodedCharacters);

    // Log removed from hot path for latency optimization
//     return 0;
    env->ReleaseIntArrayElements(noteInfo, int_elements, JNI_ABORT);
}