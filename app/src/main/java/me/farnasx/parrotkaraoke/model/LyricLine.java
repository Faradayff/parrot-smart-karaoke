package me.farnasx.parrotkaraoke.model;

/** One timestamped lyric line ({@code t} = milliseconds into the song). */
public final class LyricLine {
    public long t;
    public String text;

    public LyricLine(long t, String text) {
        this.t = t;
        this.text = text == null ? "" : text;
    }
}
