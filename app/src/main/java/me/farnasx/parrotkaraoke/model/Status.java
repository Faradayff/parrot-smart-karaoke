package me.farnasx.parrotkaraoke.model;

import java.util.ArrayList;

/**
 * Immutable-ish snapshot of one {@code GET /status} response.
 * All fields are safe to be empty; parse failures leave everything at defaults.
 */
public final class Status {
    public boolean ok;
    public boolean auth;
    public String error = "";

    public boolean playing;
    public long positionMs;
    public Track track;

    public boolean lyricsSynced;
    public int lyricsLines;

    /** Zero-based active line index, -1 when the relay didn't provide one. */
    public int line = -1;
    public String lineText = "";
    /** Text of the line above the active one ("" at the first line). */
    public String prevLine = "";
    public ArrayList<LyricLine> nextLines = new ArrayList<LyricLine>();

    /** Unsynced full-lyrics text (present only when there is no synced version). */
    public String plain = "";

    /**
     * Audio-delay compensation (ms) echoed back by the relay: the value the
     * relay applied when resolving {@code line} / {@code lineText} /
     * {@code prevLine} / {@code nextLines} at {@code positionMs − delayMs}
     * (informational; -1 = a relay predating the delay feature).
     */
    public long delayMs = -1L;

    /**
     * Relay state version (long-poll / "wait" mode). The relay increments it
     * every time the visible state changes (line, track, playing, auth…).
     * <p>-1 = the relay did not send the field (older relay: classic polling).
     * Its mere presence (≥ 0) is the client's feature detection.
     */
    public long version = -1L;
}
