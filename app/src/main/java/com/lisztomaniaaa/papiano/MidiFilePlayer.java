package com.lisztomaniaaa.papiano;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight Standard MIDI File parser + playback engine.
 * Supports Format 0 and Format 1 .mid files.
 *
 * Plays note events by calling a NoteCallback which should forward
 * to iGamePad.qwertyKey(). Runs on its own HandlerThread for
 * accurate timing without blocking UI.
 *
 * Features:
 * - Variable speed (0.25x - 2.0x)
 * - Play / Pause / Stop
 * - Auto-clamp to note range 21-107
 * - Tempo map (handles mid-song tempo changes correctly)
 * - Channel filter (default: channel 0 only, configurable)
 */
public class MidiFilePlayer {

    private static final String TAG = "MidiFilePlayer";

    public interface NoteCallback {
        void onNote(int noteNumber, boolean isDown, int velocity);
    }

    public interface StateCallback {
        void onPlaybackStarted();
        void onPlaybackStopped();
        void onProgress(float percent);
    }

    // Parsed note event
    private static class NoteEvent {
        long absoluteTick;
        int note;
        boolean isDown;
        int channel;
        int velocity; // 0..127 (MIDI note-on velocity; 0 for note-off)

        NoteEvent(long tick, int note, boolean isDown, int channel, int velocity) {
            this.absoluteTick = tick;
            this.note = note;
            this.isDown = isDown;
            this.channel = channel;
            this.velocity = velocity;
        }
    }

    // Tempo change event for tempo map
    private static class TempoEvent {
        long tick;
        int microsecondsPerBeat; // tempo in µs/beat

        TempoEvent(long tick, int tempo) {
            this.tick = tick;
            this.microsecondsPerBeat = tempo;
        }
    }

    private List<NoteEvent> events = new ArrayList<>();
    private List<TempoEvent> tempoMap = new ArrayList<>();
    private int ticksPerBeat = 480;

    private HandlerThread thread;
    private Handler handler;
    private NoteCallback noteCallback;
    private StateCallback stateCallback;

    private volatile boolean isPlaying = false;
    private volatile boolean isPaused = false;
    private volatile float speedMultiplier = 1.0f;
    private int currentEventIndex = 0;
    private String fileName = "";

    /** Channel filter: -1 = all channels, 0-15 = specific channel.
     *  Default -1 plays all channels EXCEPT channel 9 (drums/percussion). */
    private volatile int channelFilter = -1;

    public MidiFilePlayer() {
        thread = new HandlerThread("midi-player");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void setNoteCallback(NoteCallback cb) { this.noteCallback = cb; }
    public void setStateCallback(StateCallback cb) { this.stateCallback = cb; }
    public void setSpeed(float speed) { this.speedMultiplier = Math.max(0.25f, Math.min(2.0f, speed)); }
    public float getSpeed() { return speedMultiplier; }
    public void setChannelFilter(int ch) { this.channelFilter = ch; }
    public int getChannelFilter() { return channelFilter; }
    public boolean isPlaying() { return isPlaying; }
    public boolean isPaused() { return isPaused; }
    public String getFileName() { return fileName; }
    public boolean isLoaded() { return !events.isEmpty(); }

    /**
     * Parse a .mid file from InputStream. Call from any thread.
     * @return true if parsed successfully
     */
    public boolean load(InputStream is, String name) {
        stop();
        events.clear();
        tempoMap.clear();
        fileName = name != null ? name : "Unknown";

        try {
            byte[] data = readAll(is);
            parse(data);
            Log.d(TAG, "Loaded: " + fileName + " (" + events.size() + " events, "
                    + tempoMap.size() + " tempo changes)");
            return !events.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse MIDI: " + e.getMessage());
            events.clear();
            tempoMap.clear();
            return false;
        }
    }

    public void play() {
        if (events.isEmpty()) return;
        if (isPaused) {
            isPaused = false;
            isPlaying = true;
            scheduleNext();
            if (stateCallback != null) stateCallback.onPlaybackStarted();
            return;
        }
        stop();
        isPlaying = true;
        isPaused = false;
        currentEventIndex = 0;
        if (stateCallback != null) stateCallback.onPlaybackStarted();
        scheduleNext();
    }

    public void pause() {
        if (!isPlaying) return;
        isPaused = true;
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
    }

    public void stop() {
        isPlaying = false;
        isPaused = false;
        currentEventIndex = 0;
        handler.removeCallbacksAndMessages(null);
        // Release all held notes
        if (noteCallback != null) {
            for (int n = 21; n <= 107; n++) {
                noteCallback.onNote(n, false, 0);
            }
        }
        if (stateCallback != null) stateCallback.onPlaybackStopped();
    }

    public void destroy() {
        stop();
        thread.quitSafely();
    }

    private void scheduleNext() {
        if (!isPlaying || currentEventIndex >= events.size()) {
            // Playback finished
            isPlaying = false;
            if (stateCallback != null) stateCallback.onPlaybackStopped();
            // Release all
            if (noteCallback != null) {
                for (int n = 21; n <= 107; n++) noteCallback.onNote(n, false, 0);
            }
            return;
        }

        handler.post(this::playNextBatch);
    }

    private void playNextBatch() {
        if (!isPlaying || currentEventIndex >= events.size()) {
            isPlaying = false;
            if (stateCallback != null) stateCallback.onPlaybackStopped();
            return;
        }

        NoteEvent current = events.get(currentEventIndex);

        // Fire current event (respect channel filter, always skip ch9 drums)
        int filter = channelFilter;
        if (noteCallback != null && current.note >= 21 && current.note <= 107) {
            if (current.channel == 9) {
                // Skip percussion channel — note numbers mean different instruments, not piano keys
            } else if (filter == -1 || current.channel == filter) {
                noteCallback.onNote(current.note, current.isDown, current.velocity);
            }
        }
        currentEventIndex++;

        // Report progress
        if (stateCallback != null) {
            float pct = (float) currentEventIndex / events.size() * 100f;
            stateCallback.onProgress(pct);
        }

        // Schedule next event
        if (currentEventIndex < events.size()) {
            NoteEvent next = events.get(currentEventIndex);
            long delayMs = tickDeltaToMs(current.absoluteTick, next.absoluteTick);
            // Apply speed
            delayMs = (long) (delayMs / speedMultiplier);
            if (delayMs <= 0) {
                // Simultaneous events — fire immediately
                handler.post(this::playNextBatch);
            } else {
                handler.postDelayed(this::playNextBatch, delayMs);
            }
        } else {
            // End of file
            isPlaying = false;
            if (stateCallback != null) stateCallback.onPlaybackStopped();
        }
    }

    /**
     * Convert tick delta to milliseconds using the tempo map.
     * Handles tempo changes that occur between fromTick and toTick.
     */
    private long tickDeltaToMs(long fromTick, long toTick) {
        if (fromTick >= toTick) return 0;
        if (tempoMap.isEmpty()) {
            // Fallback: 120 BPM
            return (toTick - fromTick) * 500000L / ticksPerBeat / 1000;
        }

        long totalMicros = 0;
        long currentTick = fromTick;

        // Find the tempo that's active at fromTick
        int tempoIdx = 0;
        for (int i = tempoMap.size() - 1; i >= 0; i--) {
            if (tempoMap.get(i).tick <= fromTick) {
                tempoIdx = i;
                break;
            }
        }

        while (currentTick < toTick) {
            int currentTempo = tempoMap.get(tempoIdx).microsecondsPerBeat;
            // Next tempo change tick (or toTick if no more changes)
            long nextChangeTick = toTick;
            if (tempoIdx + 1 < tempoMap.size()) {
                nextChangeTick = Math.min(toTick, tempoMap.get(tempoIdx + 1).tick);
            }

            long ticksInThisSegment = nextChangeTick - currentTick;
            totalMicros += ticksInThisSegment * currentTempo / ticksPerBeat;

            currentTick = nextChangeTick;
            if (tempoIdx + 1 < tempoMap.size() && currentTick >= tempoMap.get(tempoIdx + 1).tick) {
                tempoIdx++;
            }
        }

        return totalMicros / 1000; // µs -> ms
    }

    // ═══════════════════════════════════════════════════════════════
    // MIDI PARSER — Standard MIDI File Format 0 & 1
    // ═══════════════════════════════════════════════════════════════

    private int pos; // parser position

    private void parse(byte[] data) throws Exception {
        pos = 0;

        // Header chunk: "MThd"
        String hdr = readString(data, 4);
        if (!"MThd".equals(hdr)) throw new IOException("Not a MIDI file");

        int headerLen = readInt32(data);
        int format = readInt16(data);
        int numTracks = readInt16(data);
        int division = readInt16(data);

        if ((division & 0x8000) != 0) {
            // SMPTE timing — use default ticksPerBeat
            ticksPerBeat = 480;
        } else {
            ticksPerBeat = division;
        }

        // Parse all tracks, collect notes + tempo events
        List<NoteEvent> allEvents = new ArrayList<>();
        List<TempoEvent> allTempos = new ArrayList<>();

        for (int t = 0; t < numTracks; t++) {
            String trkHdr = readString(data, 4);
            if (!"MTrk".equals(trkHdr)) throw new IOException("Expected MTrk at pos " + pos);
            int trkLen = readInt32(data);
            int trkEnd = pos + trkLen;

            long absoluteTick = 0;
            int runningStatus = 0;

            while (pos < trkEnd) {
                long delta = readVarLen(data);
                absoluteTick += delta;

                int b = data[pos] & 0xFF;

                if (b == 0xFF) {
                    // Meta event
                    pos++;
                    int metaType = data[pos++] & 0xFF;
                    int metaLen = (int) readVarLen(data);
                    if (metaType == 0x51 && metaLen == 3) {
                        // Tempo change — add to tempo map
                        int newTempo = ((data[pos] & 0xFF) << 16)
                                | ((data[pos + 1] & 0xFF) << 8)
                                | (data[pos + 2] & 0xFF);
                        allTempos.add(new TempoEvent(absoluteTick, newTempo));
                    }
                    pos += metaLen;
                } else if (b == 0xF0 || b == 0xF7) {
                    // SysEx
                    pos++;
                    int sysLen = (int) readVarLen(data);
                    pos += sysLen;
                } else {
                    // MIDI event
                    if ((b & 0x80) != 0) {
                        runningStatus = b;
                        pos++;
                    }
                    int cmd = runningStatus & 0xF0;
                    int channel = runningStatus & 0x0F;

                    if (cmd == 0x90 || cmd == 0x80) {
                        int note = data[pos++] & 0xFF;
                        int vel = data[pos++] & 0xFF;
                        boolean isDown = (cmd == 0x90) && (vel > 0);
                        allEvents.add(new NoteEvent(absoluteTick, note, isDown, channel,
                                isDown ? vel : 0));
                    } else if (cmd == 0xA0 || cmd == 0xB0 || cmd == 0xE0) {
                        pos += 2; // 2-byte events
                    } else if (cmd == 0xC0 || cmd == 0xD0) {
                        pos += 1; // 1-byte events
                    } else {
                        pos++; // unknown, skip
                    }
                }
            }
            pos = trkEnd; // safety
        }

        // Sort tempo map by tick
        Collections.sort(allTempos, (a, b) -> Long.compare(a.tick, b.tick));
        // Ensure there's always a tempo at tick 0 (default 120 BPM = 500000 µs/beat)
        if (allTempos.isEmpty() || allTempos.get(0).tick > 0) {
            allTempos.add(0, new TempoEvent(0, 500000));
        }
        tempoMap = allTempos;

        // Sort note events by absolute tick (merge all tracks)
        Collections.sort(allEvents, (a, b) -> Long.compare(a.absoluteTick, b.absoluteTick));
        events = allEvents;
    }

    private String readString(byte[] data, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) (data[pos++] & 0xFF));
        return sb.toString();
    }

    private int readInt32(byte[] data) {
        return ((data[pos++] & 0xFF) << 24) | ((data[pos++] & 0xFF) << 16)
                | ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
    }

    private int readInt16(byte[] data) {
        return ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
    }

    private long readVarLen(byte[] data) {
        long val = 0;
        int b;
        do {
            b = data[pos++] & 0xFF;
            val = (val << 7) | (b & 0x7F);
        } while ((b & 0x80) != 0);
        return val;
    }

    private byte[] readAll(InputStream is) throws IOException {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
    }
}
