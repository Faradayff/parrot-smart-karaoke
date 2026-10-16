package me.farnasx.parrotkaraoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.StatusParser;

import org.junit.Test;

/**
 * Unit tests for the relay /status parser (mirrors the real payload shape).
 */
public class StatusParserTest {

    private static final String FULL = "{"
            + "\"ok\":true,\"auth\":true,\"playing\":true,\"positionMs\":42000,"
            + "\"updated\":\"2026-09-22T19:00:00Z\","
            + "\"version\":7,"
            + "\"track\":{\"id\":\"t1\",\"name\":\"Bohemian Rhapsody\",\"artist\":\"Queen\","
            + "\"album\":\"A Night at the Opera\",\"uri\":\"spotify:track:t1\",\"durMs\":354000,"
            + "\"cover\":[\"https://i.scdn.co/image/abc\"]},"
            + "\"lyricsSynced\":true,\"lyricsLines\":3,\"line\":1,"
            + "\"lineText\":\"Is this just fantasy?\","
            + "\"prevLine\":\"Is this the real life?\","
            + "\"nextLines\":[{\"t\":12000,\"text\":\"Caught in a landslide\"}]}"
            + "}";

    @Test
    public void parsesFullStatus() {
        Status s = StatusParser.parse(FULL);
        assertTrue(s.ok);
        assertTrue(s.auth);
        assertTrue(s.playing);
        assertEquals(42000L, s.positionMs);
        assertNotNull(s.track);
        assertEquals("t1", s.track.id);
        assertEquals("Bohemian Rhapsody", s.track.name);
        assertEquals("Queen", s.track.artist);
        assertEquals("A Night at the Opera", s.track.album);
        assertEquals(354000, s.track.durMs);
        assertEquals("https://i.scdn.co/image/abc", s.track.coverUrl);
        assertTrue(s.lyricsSynced);
        assertEquals(3, s.lyricsLines);
        assertEquals(1, s.line);
        assertEquals("Is this just fantasy?", s.lineText);
        assertEquals("Is this the real life?", s.prevLine);
        assertEquals(1, s.nextLines.size());
        assertEquals("", s.plain);
        assertEquals(7L, s.version);
    }

    @Test
    public void missingVersionStaysMinusOne() {
        Status s = StatusParser.parse("{\"ok\":true,\"auth\":true,\"playing\":false}");
        assertEquals(-1L, s.version);
    }

    @Test
    public void nullVersionStaysMinusOne() {
        Status s = StatusParser.parse("{\"ok\":true,\"version\":null}");
        assertEquals(-1L, s.version);
    }

    @Test
    public void zeroVersionIsAValidVersion() {
        // 0 is a legitimate first state version: wait mode must stay enabled.
        Status s = StatusParser.parse("{\"ok\":true,\"version\":0}");
        assertEquals(0L, s.version);
    }

    @Test
    public void parsesUnauthenticated() {
        Status s = StatusParser.parse(
                "{\"ok\":false,\"auth\":false,\"error\":\"spotify not authenticated\"}");
        assertFalse(s.ok);
        assertFalse(s.auth);
        assertEquals("spotify not authenticated", s.error);
        assertNull(s.track);
        assertEquals(-1, s.line);
    }

    @Test
    public void parsesRelayError() {
        Status s = StatusParser.parse(
                "{\"ok\":false,\"auth\":true,\"error\":\"boom\"}");
        assertFalse(s.ok);
        assertTrue(s.auth);
        assertEquals("boom", s.error);
    }

    @Test
    public void parsesPlainLyrics() {
        Status s = StatusParser.parse(
                "{\"ok\":true,\"auth\":true,\"playing\":false,"
                        + "\"track\":{\"id\":\"t2\",\"name\":\"X\",\"artist\":\"Y\"},"
                        + "\"lyricsSynced\":false,\"plain\":\"line one\\nline two\"}");
        assertTrue(s.ok);
        assertFalse(s.playing);
        assertNotNull(s.track);
        assertEquals("t2", s.track.id);
        assertFalse(s.lyricsSynced);
        assertEquals("line one\nline two", s.plain);
        assertTrue(s.nextLines.isEmpty());
        assertEquals(-1, s.line);
    }

    @Test
    public void missingTrackIsNull() {
        Status s = StatusParser.parse("{\"ok\":true,\"auth\":true,\"playing\":false}");
        assertTrue(s.ok);
        assertNull(s.track);
    }

    @Test
    public void malformedJsonGivesDefaults() {
        Status s = StatusParser.parse("{not json at all");
        assertFalse(s.ok);
        assertNull(s.track);
        assertEquals(-1, s.line);
    }

    @Test
    public void nullAndEmptyInput() {
        assertFalse(StatusParser.parse(null).ok);
        assertFalse(StatusParser.parse("").ok);
    }

    @Test
    public void nullLineStaysMinusOne() {
        Status s = StatusParser.parse("{\"ok\":true,\"line\":null,\"lineText\":\"x\"}");
        assertEquals(-1, s.line);
        assertEquals("x", s.lineText);
    }

    @Test
    public void blankLyricLinesAreSkipped() {
        Status s = StatusParser.parse(
                "{\"ok\":true,\"line\":0,\"nextLines\":["
                        + "{\"t\":1,\"text\":\"  \"},"
                        + "{\"t\":2,\"text\":\"hello\"},"
                        + "\"gibberish\"]}");
        assertEquals(1, s.nextLines.size());
        assertEquals("hello", s.nextLines.get(0).text);
    }

    @Test
    public void relayAppliedDelayParsesBandFields() {
        Status s = StatusParser.parse(
                "{\"ok\":true,\"auth\":true,\"playing\":true,\"positionMs\":42000,"
                + "\"version\":7,\"delayMs\":1200,\"lyricsSynced\":true,\"line\":2,"
                + "\"lineText\":\"Caught in a landslide\","
                + "\"prevLine\":\"Is this just fantasy?\","
                + "\"nextLines\":[{\"t\":15000,\"text\":\"Open your eyes\"}]}");
        assertEquals(1200L, s.delayMs);
        assertEquals("Is this just fantasy?", s.prevLine);
        assertEquals(2, s.line);
        assertEquals("Caught in a landslide", s.lineText);
        assertEquals(1, s.nextLines.size());
    }

    @Test
    public void zeroDelayIsAValidDelay() {
        // delayMs=0 is a legitimate "no delay" value from the relay.
        Status s = StatusParser.parse(
                "{\"ok\":true,\"playing\":true,\"delayMs\":0,"
                + "\"line\":0,\"lineText\":\"a\",\"prevLine\":\"\"}");
        assertEquals(0L, s.delayMs);
        assertEquals("", s.prevLine);
        assertEquals(0, s.line);
        assertEquals("a", s.lineText);
    }

    @Test
    public void nonBreakingUnicodeSpacesBecomeWrappable() {
        // Lyrics often carry no-break/invisible Unicode (nbsp, word joiner,
        // zero-width space, BOM); Android will not break lines at them, so
        // they must come out as ordinary spaces (or dropped, in the BOM case).
        Status s = StatusParser.parse(
                "{\"ok\":true,\"playing\":true,\"line\":1,"
                + "\"lineText\":\"A\u00A0Dios\u2060le\u200Bpedí\","
                + "\"prevLine\":\"\u00A0\","
                + "\"nextLines\":[{\"t\":1,\"text\":\"\uFEFFy\u2007no me\"},"
                + "{\"t\":2,\"text\":\"\u00A0\u00A0\"}]}");
        assertEquals("A Dios le pedí", s.lineText);
        assertEquals("", s.prevLine);
        assertEquals(1, s.nextLines.size());
        assertEquals("y no me", s.nextLines.get(0).text);
    }

    @Test
    public void plainLyricsKeepTheirNewlines() {
        Status s = StatusParser.parse(
                "{\"ok\":true,\"playing\":false,"
                + "\"plain\":\"line one\u00A0x\\nline two\"}");
        assertEquals("line one x\nline two", s.plain);
    }

    @Test
    public void oldRelayWithoutDelayMsKeepsDefault() {
        // A relay predating the delay feature does not send delayMs -> -1. The
        // served line/lineText are still parsed; a stray "lines" array, if any,
        // is simply ignored.
        Status s = StatusParser.parse(
                "{\"ok\":true,\"playing\":true,\"line\":0,\"lineText\":\"a\","
                + "\"lines\":[{\"t\":1000,\"text\":\"a\"},{\"t\":2000,\"text\":\"b\"}]}");
        assertEquals(-1L, s.delayMs);
        assertEquals(0, s.line);
        assertEquals("a", s.lineText);
        assertEquals("", s.prevLine);
    }
}
