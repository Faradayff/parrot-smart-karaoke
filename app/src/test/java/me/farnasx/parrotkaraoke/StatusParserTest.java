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
            + "\"lines\":["
            + "{\"t\":6000,\"text\":\"Is this the real life?\"},"
            + "{\"t\":9000,\"text\":\"Is this just fantasy?\"},"
            + "{\"t\":12000,\"text\":\"Caught in a landslide\"}],"
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
        assertEquals(3, s.lines.size());
        assertEquals(9000L, s.lines.get(1).t);
        assertEquals("Is this just fantasy?", s.lines.get(1).text);
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
        assertTrue(s.lines.isEmpty());
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
        assertTrue(s.lines.isEmpty());
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
        assertTrue(s.lines.isEmpty());
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
                "{\"ok\":true,\"line\":0,\"lines\":["
                        + "{\"t\":1,\"text\":\"  \"},"
                        + "{\"t\":2,\"text\":\"hello\"},"
                        + "\"gibberish\"]}");
        assertEquals(1, s.lines.size());
        assertEquals("hello", s.lines.get(0).text);
    }

    @Test
    public void relayAppliedDelayParsesFieldsAndSkipsLines() {
        // New relay: delayMs present (>= 0), prevLine present, "lines" trimmed
        // (not sent). The parser must keep the served fields and not parse
        // the (absent) full lines array.
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
        assertTrue("relay-applied delay: full lines array must not be parsed",
                s.lines.isEmpty());
    }

    @Test
    public void zeroDelayIsAPresentDelay() {
        // delayMs=0 is a legitimate "no delay" from a new relay: present, and
        // the app must still treat it as relay-applied (not fall back).
        Status s = StatusParser.parse(
                "{\"ok\":true,\"playing\":true,\"delayMs\":0,"
                + "\"line\":0,\"lineText\":\"a\",\"prevLine\":\"\"}");
        assertEquals(0L, s.delayMs);
        assertEquals("", s.prevLine);
        assertTrue(s.lines.isEmpty());
    }

    @Test
    public void oldRelayWithoutDelayMsKeepsLinesParsed() {
        // Older relay: no delayMs -> -1 (fallback), so the full "lines" array
        // is parsed and the local re-derivation keeps working.
        Status s = StatusParser.parse(
                "{\"ok\":true,\"playing\":true,\"line\":0,\"lineText\":\"a\","
                + "\"lines\":[{\"t\":1000,\"text\":\"a\"},{\"t\":2000,\"text\":\"b\"}]}");
        assertEquals(-1L, s.delayMs);
        assertEquals(2, s.lines.size());
        assertEquals("", s.prevLine);
    }
}
