package me.farnasx.parrotkaraoke.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.Track;

import org.junit.Test;

/**
 * Unit tests for the RelayClient's pure decision logic (no Android, no threads).
 */
public class RelayClientTest {

    @Test
    public void firstFailuresAreRetriedSilently() {
        for (int n = 1; n <= RelayClient.WAIT_SILENT_RETRIES; n++) {
            assertTrue("failure " + n,
                    RelayClient.shouldRetrySilently(n, new IOException("socket down")));
        }
    }

    @Test
    public void failuresBeyondTheSilentWindowEscalate() {
        assertFalse(RelayClient.shouldRetrySilently(
                RelayClient.WAIT_SILENT_RETRIES + 1, new IOException("socket down")));
    }

    @Test
    public void httpStatusAnswersAlwaysEscalateAtOnce() {
        // A definitive 401/404/5xx from the relay is not a "blip": even the
        // first one must produce a diagnosis right away.
        assertFalse(RelayClient.shouldRetrySilently(
                1, new Http.HttpException(401, "unauthorized")));
    }

    @Test
    public void escalationAttemptCountsFromOneAfterTheSilentWindow() {
        assertEquals(1, RelayClient.escalationAttempt(RelayClient.WAIT_SILENT_RETRIES + 1));
        assertEquals(2, RelayClient.escalationAttempt(RelayClient.WAIT_SILENT_RETRIES + 2));
        assertEquals(4, RelayClient.escalationAttempt(RelayClient.WAIT_SILENT_RETRIES + 4));
    }

    @Test
    public void backoffLadderIsFiveTenFifteenSeconds() {
        assertEquals(5000L, RelayClient.backoff(1));
        assertEquals(10000L, RelayClient.backoff(2));
        assertEquals(10000L, RelayClient.backoff(3));
        assertEquals(15000L, RelayClient.backoff(4));
        assertEquals(15000L, RelayClient.backoff(12));
    }

    @Test
    public void waitUrlAppendsParams() {
        assertEquals("http://relay/status?wait=1&timeoutMs=" + RelayClient.WAIT_TIMEOUT_MS
                + "&sinceVersion=42", RelayClient.waitUrl("http://relay/status", 42L));
    }

    @Test
    public void waitUrlKeepsAnExistingQuery() {
        assertEquals("http://relay/status?x=1&wait=1&timeoutMs=" + RelayClient.WAIT_TIMEOUT_MS
                + "&sinceVersion=-1", RelayClient.waitUrl("http://relay/status?x=1", -1L));
    }

    @Test
    public void readTimeoutIsLongerInWaitMode() {
        assertTrue(RelayClient.WAIT_READ_TIMEOUT_MS > RelayClient.WAIT_TIMEOUT_MS);
        assertTrue(RelayClient.WAIT_READ_TIMEOUT_MS > Http.READ_TIMEOUT_MS);
    }

    @Test
    public void delayForFollowsTheClassicRules() {
        int base = 1000;
        assertEquals(15000L, RelayClient.delayFor(unavailable(), base));
        assertEquals(5000L, RelayClient.delayFor(noTrack(), base));

        Status withLine = noTrack();
        withLine.playing = true;
        withLine.line = 3;
        withLine.lineText = "hello";
        assertEquals(base, RelayClient.delayFor(withLine, base));

        Status playingNoLine = noTrack();
        playingNoLine.playing = true;
        assertEquals(Math.max(base, 3000L), RelayClient.delayFor(playingNoLine, base));

        Status paused = noTrack();
        assertTrue(RelayClient.delayFor(paused, base) >= 5000L);
    }

    // ------------------------------------------------------------ fixtures

    private static Status ok() {
        Status s = new Status();
        s.ok = true;
        s.auth = true;
        return s;
    }

    private static Status noTrack() {
        Status s = ok();
        s.track = new Track();
        s.track.id = "t1";
        return s;
    }

    private static Status unavailable() {
        Status s = new Status();
        s.ok = false;
        s.error = "boom";
        return s;
    }
}
