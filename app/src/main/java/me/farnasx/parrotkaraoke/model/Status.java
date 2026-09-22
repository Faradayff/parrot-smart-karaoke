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
    public ArrayList<LyricLine> lines = new ArrayList<LyricLine>();
    public ArrayList<LyricLine> nextLines = new ArrayList<LyricLine>();

    /** Unsynced full-lyrics text (present only when there is no synced version). */
    public String plain = "";
}
