package me.farnasx.parrotkaraoke.model;

import java.util.ArrayList;

/**
 * Locally recomputes the active lyric line for a given playback position,
 * offset by an optional delay.
 *
 * <p>Use case: the car head unit plays the audio ~1 s late, so the lyrics
 * (driven by the true playback position) would run ahead of the heard audio.
 * A delay of 1.2 s makes the app render the line that the listener will
 * <em>hear</em> now, i.e. the line at {@code positionMs - delayMs}.
 *
 * <p>Pure and Android-free so it is unit-testable on the JVM.
 */
public final class LyricIndex {

    private LyricIndex() {
    }

    /**
     * @param lines      full synced-lyrics lines, sorted by ascending {@code t}
     * @param positionMs current playback position (ms)
     * @param delayMs    audio delay to compensate (ms); 0 = no shift
     * @return zero-based index of the last line with {@code t <= positionMs - delayMs},
     *         or -1 when the position (after the shift) is before the first line
     */
    public static int indexOfLine(ArrayList<LyricLine> lines, long positionMs, long delayMs) {
        if (lines == null || lines.size() == 0) {
            return -1;
        }
        long p = positionMs - delayMs;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).t <= p) {
                idx = i;
            } else {
                break; // lines are sorted; nothing further can match
            }
        }
        return idx;
    }

    /**
     * The next {@code count} lines after index {@code idx} (up to the end of
     * the list), so the caller can render the "coming up" band from the
     * <em>effective</em> (delay-shifted) index instead of the relay's
     * unshifted list.
     *
     * @return empty list when {@code lines} is empty or {@code idx} invalid
     */
    public static ArrayList<LyricLine> nextBlock(ArrayList<LyricLine> lines, int idx, int count) {
        ArrayList<LyricLine> out = new ArrayList<LyricLine>();
        if (lines == null || count <= 0 || idx < 0) {
            return out;
        }
        for (int i = idx + 1; i < lines.size() && out.size() < count; i++) {
            out.add(lines.get(i));
        }
        return out;
    }
}
