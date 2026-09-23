package me.farnasx.parrotkaraoke.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the diagnostic classification (the pure part of
 * {@link Diagnostics}); the socket probes are not exercised here.
 */
public class DiagnosticsTest {

    private static final String HOST = "lyrics.example";
    private static final int PORT = 80;

    private static Diagnosis.Check byKind(Diagnosis d, int kind) {
        for (int i = 0; i < d.checks.size(); i++) {
            if (d.checks.get(i).kind == kind) {
                return d.checks.get(i);
            }
        }
        throw new AssertionError("check kind " + kind + " not found");
    }

    private Diagnosis diagnose(Boolean internet, Boolean dns, String dnsIp,
                               Boolean relayTcp, String transport, int httpCode) {
        return Diagnostics.diagnose(HOST, PORT, 3, internet, dns, dnsIp,
                relayTcp, transport, httpCode, 42);
    }

    @Test
    public void http401IsRejectedCredentials() {
        Diagnosis d = diagnose(null, null, null, null, null, 401);
        assertEquals(Diagnosis.HL_CREDENTIALS, d.headline);
        assertEquals(Diagnosis.Check.STATE_FAIL, byKind(d, Diagnosis.Check.KIND_HTTP).state);
        assertEquals("HTTP 401", byKind(d, Diagnosis.Check.KIND_HTTP).detail);
        // We got a response, so the lower layers are implied-pass.
        assertEquals(Diagnosis.Check.STATE_OK, byKind(d, Diagnosis.Check.KIND_INTERNET).state);
        assertEquals(Diagnosis.Check.STATE_OK, byKind(d, Diagnosis.Check.KIND_DNS).state);
        assertEquals(Diagnosis.Check.STATE_OK, byKind(d, Diagnosis.Check.KIND_RELAY_PORT).state);
    }

    @Test
    public void http403IsForbidden() {
        assertEquals(Diagnosis.HL_FORBIDDEN, diagnose(null, null, null, null, null, 403).headline);
    }

    @Test
    public void http404IsNotFound() {
        assertEquals(Diagnosis.HL_NOT_FOUND, diagnose(null, null, null, null, null, 404).headline);
    }

    @Test
    public void http500IsGenericWithCode() {
        Diagnosis d = diagnose(null, null, null, null, null, 500);
        assertEquals(Diagnosis.HL_HTTP_OTHER, d.headline);
        assertEquals(500, d.headlineArg);
    }

    @Test
    public void unparseableUrlFailsOnlyTheUrlCheck() {
        Diagnosis d = Diagnostics.diagnose(null, -1, 1,
                null, null, null, null, null, -1, 0);
        assertEquals(Diagnosis.HL_INVALID_URL, d.headline);
        assertEquals(1, d.checks.size());
        assertEquals(Diagnosis.Check.STATE_FAIL, d.checks.get(0).state);
        assertEquals(Diagnosis.Check.KIND_URL, d.checks.get(0).kind);
    }

    @Test
    public void deadInternetBeatsEverythingElse() {
        Diagnosis d = diagnose(Boolean.FALSE, Boolean.FALSE, null, null, "dns", -1);
        assertEquals(Diagnosis.HL_NO_INTERNET, d.headline);
        assertEquals(Diagnosis.Check.STATE_FAIL, byKind(d, Diagnosis.Check.KIND_INTERNET).state);
    }

    @Test
    public void unresolvableHostIsADnsFailure() {
        Diagnosis d = diagnose(Boolean.TRUE, Boolean.FALSE, null, null, "dns", -1);
        assertEquals(Diagnosis.HL_DNS_FAIL, d.headline);
        assertEquals(HOST, d.headlineText);
        assertEquals(Diagnosis.Check.STATE_FAIL, byKind(d, Diagnosis.Check.KIND_DNS).state);
        // Without a name, the relay-port probe is skipped, not failed.
        assertEquals(Diagnosis.Check.STATE_SKIP, byKind(d, Diagnosis.Check.KIND_RELAY_PORT).state);
    }

    @Test
    public void refusedConnectionIsADiagnosis() {
        Diagnosis d = diagnose(Boolean.TRUE, Boolean.TRUE, "10.0.0.5",
                Boolean.FALSE, "refused", -1);
        assertEquals(Diagnosis.HL_REFUSED, d.headline);
        assertEquals(HOST + ":" + PORT, d.headlineText);
        assertEquals(Diagnosis.Check.STATE_FAIL, byKind(d, Diagnosis.Check.KIND_RELAY_PORT).state);
    }

    @Test
    public void timeoutWithOpenPortMeansTheRelayDidNotAnswer() {
        Diagnosis d = diagnose(Boolean.TRUE, Boolean.TRUE, "10.0.0.5",
                Boolean.TRUE, "timeout", -1);
        assertEquals(Diagnosis.HL_READ_TIMEOUT, d.headline);
        assertEquals(Diagnosis.Check.STATE_OK, byKind(d, Diagnosis.Check.KIND_RELAY_PORT).state);
        // No HTTP answer at all.
        assertEquals(Diagnosis.Check.STATE_SKIP, byKind(d, Diagnosis.Check.KIND_HTTP).state);
    }

    @Test
    public void timeoutWithClosedPortMeansUnreachable() {
        Diagnosis d = diagnose(Boolean.TRUE, Boolean.TRUE, "10.0.0.5",
                Boolean.FALSE, "timeout", -1);
        assertEquals(Diagnosis.HL_UNREACHABLE, d.headline);
    }

    @Test
    public void otherErrorsCarryTheReason() {
        Diagnosis d = diagnose(Boolean.TRUE, Boolean.TRUE, "10.0.0.5",
                Boolean.FALSE, "boom from the proxy", -1);
        assertEquals(Diagnosis.HL_OTHER, d.headline);
        assertEquals("boom from the proxy", d.headlineText);
    }

    @Test
    public void metadataIsPreserved() {
        Diagnosis d = diagnose(Boolean.TRUE, Boolean.TRUE, "10.0.0.5",
                Boolean.FALSE, "refused", -1);
        assertEquals(3, d.attempt);
        assertEquals(42, d.elapsedMs);
        assertEquals(0, d.headlineArg);
    }

    @Test
    public void fallbackKeepsTheRawReason() {
        Diagnosis d = Diagnostics.fallback(2, "java.net.SocketTimeoutException: blocked", 15000);
        assertEquals(Diagnosis.HL_OTHER, d.headline);
        assertEquals("java.net.SocketTimeoutException: blocked", d.headlineText);
        assertEquals(15000, d.nextRetryMs);
        assertEquals(4, d.checks.size());
    }
}
