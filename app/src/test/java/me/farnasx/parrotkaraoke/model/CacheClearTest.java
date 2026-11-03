package me.farnasx.parrotkaraoke.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for CacheClearResult.parse (no Android, no threads).
 */
public class CacheClearTest {

    @Test
    public void parsesTheRelayReport() {
        CacheClearResult r = CacheClearResult.parse(
                "{\"ok\":true,\"removed\":137,\"freedBytes\":402112}");
        assertTrue(r.ok);
        assertEquals(137, r.removed);
        assertEquals(402112L, r.freedBytes);
    }

    @Test
    public void missingNumbersFallBackToDefaults() {
        CacheClearResult r = CacheClearResult.parse("{\"ok\":true}");
        assertTrue(r.ok);
        assertEquals(0, r.removed);
        assertEquals(-1L, r.freedBytes);
    }

    @Test
    public void relayErrorIsParsedToo() {
        CacheClearResult r = CacheClearResult.parse(
                "{\"ok\":false,\"error\":\"state volume not writable\"}");
        assertFalse(r.ok);
    }

    @Test
    public void garbageLeavesDefaults() {
        CacheClearResult r = CacheClearResult.parse("nope, not json");
        assertFalse(r.ok);
        assertEquals(0, r.removed);
        assertEquals(-1L, r.freedBytes);
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertFalse(CacheClearResult.parse(null).ok);
        assertFalse(CacheClearResult.parse("").ok);
    }
}
