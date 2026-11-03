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
     * Lyrics resolution state, reported by the new relay: one of "synced",
     * "plain", "none" or "loading".
     * <p>"loading" = the track HAS lyrics but the relay has not resolved them
     * yet (first fetch in flight, or a provider refusal riding out the relay's
     * own backoff). While loading the relay reports neither lyrics nor an
     * error, so the client must show a quiet "loading" state — never an error,
     * never "no lyrics".
     * <p>"" = a relay predating this field; keep the classic error /
     * no-lyrics distinction.
     */
    public String lyricsStatus = "";

    /**
     * While {@code lyricsStatus == "loading"}: ≈ ms until the relay's next
     * attempt (operator hint; deliberately not part of the relay's change
     * fingerprint). -1 = not reported.
     */
    public long pendingMs = -1L;

    /**
     * Relay state version (long-poll / "wait" mode). The relay increments it
     * every time the visible state changes (line, track, playing, auth…).
     * <p>-1 = the relay did not send the field (older relay: classic polling).
     * Its mere presence (≥ 0) is the client's feature detection.
     */
    public long version = -1L;
}
