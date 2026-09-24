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

    @Test
    public void nextBlockFollowsEffectiveIndex() {
        ArrayList<LyricLine> ls = lines();
        // At position 10 s the relay considers index 1 active; unshifted
        // "next" lines are 2 and 3.
        int relayIdx = LyricIndex.indexOfLine(ls, 10000, 0);
        assertEquals(2, LyricIndex.nextBlock(ls, relayIdx, 3).size());

        // With a 1.2 s delay the effective index is 0: the band must follow
        // line 0 — "Is this just fantasy?" first, not the relay's "Caught
        // in a landslide" (which would be the 2nd-next line of the current).
        int effIdx = LyricIndex.indexOfLine(ls, 10000, 1200);
        ArrayList<LyricLine> effNext = LyricIndex.nextBlock(ls, effIdx, 3);
        assertEquals(3, effNext.size());
        assertEquals("Is this just fantasy?", effNext.get(0).text);
        assertEquals("Caught in a landslide", effNext.get(1).text);
        assertEquals("Open your eyes", effNext.get(2).text);
    }

    @Test
    public void nextBlockClippedAtEnd() {
        assertEquals("Open your eyes", LyricIndex.nextBlock(lines(), 2, 3).get(0).text);
        assertEquals(1, LyricIndex.nextBlock(lines(), 2, 3).size());
        assertEquals(0, LyricIndex.nextBlock(lines(), 3, 3).size());
    }

    @Test
    public void nextBlockInvalidInput() {
        assertEquals(0, LyricIndex.nextBlock(null, 0, 3).size());
        assertEquals(0, LyricIndex.nextBlock(new ArrayList<LyricLine>(), 0, 3).size());
        assertEquals(0, LyricIndex.nextBlock(lines(), -1, 3).size());
    }
}
