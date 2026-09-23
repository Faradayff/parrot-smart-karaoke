package me.farnasx.parrotkaraoke;

import static org.junit.Assert.assertEquals;

import me.farnasx.parrotkaraoke.model.LyricIndex;
import me.farnasx.parrotkaraoke.model.LyricLine;

import org.junit.Test;

import java.util.ArrayList;

/** Unit tests for the local active-line re-derivation (delay compensation). */
public class LyricIndexTest {

    private static ArrayList<LyricLine> lines() {
        ArrayList<LyricLine> out = new ArrayList<LyricLine>();
        out.add(new LyricLine(6000L, "Is this the real life?"));
        out.add(new LyricLine(9000L, "Is this just fantasy?"));
        out.add(new LyricLine(12000L, "Caught in a landslide"));
        out.add(new LyricLine(15000L, "Open your eyes"));
        return out;
    }

    @Test
    public void positiveDelayShiftsToEarlierLine() {
        // At position 10 s the active line is "Is this just fantasy?" (t=9 s).
        assertEquals(1, LyricIndex.indexOfLine(lines(), 10000, 0));
        // With a 1.2 s car-audio delay, the listener is still hearing the
        // line the app showed at 8.8 s.
        assertEquals(0, LyricIndex.indexOfLine(lines(), 10000, 1200));
    }

    @Test
    public void delayBeforeFirstLineReturnsMinusOne() {
        // Position 6.5 s minus a 10 s delay is before the first line.
        assertEquals(-1, LyricIndex.indexOfLine(lines(), 6500, 10000));
    }

    @Test
    public void zeroDelayMatchesPosition() {
        // Exactly on a timestamp still selects that line (t <= position).
        assertEquals(2, LyricIndex.indexOfLine(lines(), 12000, 0));
        // Just after the last line keeps the last line.
        assertEquals(3, LyricIndex.indexOfLine(lines(), 999999, 0));
    }

    @Test
    public void delayAfterLastLineKeepsLastLine() {
        assertEquals(3, LyricIndex.indexOfLine(lines(), 999999, 500));
    }

    @Test
    public void delayCrossesLineBoundary() {
        // Position 10 s -> index 1 (line at 9 s).
        assertEquals(1, LyricIndex.indexOfLine(lines(), 10000, 0));
        // A 0.1 s delay keeps index 1 (10 s - 0.1 s = 9.9 s > 9 s)…
        assertEquals(1, LyricIndex.indexOfLine(lines(), 10000, 100));
        // …but 1.1 s of delay crosses back to index 0 (8.9 s < 9 s).
        assertEquals(0, LyricIndex.indexOfLine(lines(), 10000, 1100));
    }

    @Test
    public void emptyAndNullLines() {
        assertEquals(-1, LyricIndex.indexOfLine(null, 10000, 0));
        assertEquals(-1, LyricIndex.indexOfLine(new ArrayList<LyricLine>(), 10000, 0));
    }
}
