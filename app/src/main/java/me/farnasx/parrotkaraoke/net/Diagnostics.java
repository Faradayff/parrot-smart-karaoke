package me.farnasx.parrotkaraoke.net;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;

/**
 * Turns a failed relay attempt into a human-readable {@link Diagnosis} by
 * running a few cheap network probes, so the app can show on screen <i>why</i>
 * it is not connected:
 *
 * <ul>
 *   <li>general internet reachability (TCP probe to a public host — a raw
 *       ICMP ping would need privileged sockets, so a TCP handshake is used),</li>
 *   <li>DNS resolution of the relay host,</li>
 *   <li>TCP reachability of the relay port,</li>
 *   <li>the HTTP answer itself (e.g. 401 = credentials rejected).</li>
 * </ul>
 *
 * If the relay already returned an HTTP status code, every lower layer
 * demonstrably worked, so no extra probes are run and those checks are shown
 * as implied-pass.
 */
public final class Diagnostics {

    /** Public host used to detect "is there any internet at all". */
    public static final String PROBE_HOST = "1.1.1.1";
    public static final int PROBE_PORT = 443;
    private static final int PROBE_TIMEOUT_MS = 3000;

    private Diagnostics() {
    }

    /** Runs the probes for a failed attempt and returns the diagnosis. */
    public static Diagnosis failedAttempt(String relayUrl, int attempt,
                                          Exception e, long nextRetryMs) {
        final long start = System.currentTimeMillis();

        String host = null;
        int port = 80;
        try {
            final URL u = new URL(relayUrl);
            host = u.getHost();
            final boolean https = "https".equalsIgnoreCase(u.getProtocol());
            port = (u.getPort() != -1) ? u.getPort() : (https ? 443 : 80);
        } catch (Exception parseEx) {
            host = null;
        }

        int httpCode = -1;
        String transport = null;
        final boolean httpError = (e instanceof Http.HttpException);
        if (httpError) {
            httpCode = ((Http.HttpException) e).code;
        } else if (e instanceof UnknownHostException) {
            transport = "dns";
        } else if (e instanceof SocketTimeoutException) {
            transport = "timeout";
        } else if (e instanceof ConnectException) {
            transport = "refused";
        } else {
            transport = text(e);
        }

        Boolean internet = null;
        Boolean dns = null;
        String dnsIp = null;
        Boolean relayTcp = null;
        if (host != null && host.length() > 0 && !httpError) {
            internet = probeTcp(PROBE_HOST, PROBE_PORT);
            try {
                final InetAddress a = InetAddress.getByName(host);
                dns = Boolean.TRUE;
                dnsIp = a.getHostAddress();
            } catch (Exception ex) {
                dns = Boolean.FALSE;
            }
            if (Boolean.TRUE.equals(dns)) {
                relayTcp = probeTcp(host, port);
            }
        }

        final Diagnosis d = diagnose(host, port, attempt,
                internet, dns, dnsIp, relayTcp, transport, httpCode,
                System.currentTimeMillis() - start);
        d.nextRetryMs = nextRetryMs;
        return d;
    }

    /**
     * Pure classification (no sockets), so it is unit-testable.
     *
     * @param host       relay host (null = the URL is not parseable)
     * @param port       relay port
     * @param attempt    1-based retry counter
     * @param internetOk null = not probed, otherwise the internet probe result
     * @param dnsOk      null = not probed
     * @param dnsIp      resolved address for display, or null
     * @param relayTcpOk null = not probed
     * @param transport  "dns" | "timeout" | "refused" | free-text reason, or
     *                   null when an HTTP answer was received
     * @param httpCode   HTTP status received, -1 when none
     * @param elapsedMs  attempt + probe duration
     */
    public static Diagnosis diagnose(String host, int port, int attempt,
                                     Boolean internetOk, Boolean dnsOk, String dnsIp,
                                     Boolean relayTcpOk, String transport,
                                     int httpCode, long elapsedMs) {
        final Diagnosis d = new Diagnosis(attempt, elapsedMs);

        if (host == null || host.length() == 0) {
            d.checks.add(Diagnosis.Check.fail(Diagnosis.Check.KIND_URL, "invalid URL"));
            d.headline = Diagnosis.HL_INVALID_URL;
            return d;
        }
        final String label = host + ":" + port;
        d.checks.add(Diagnosis.Check.ok(Diagnosis.Check.KIND_URL, label));

        final boolean implied = httpCode >= 400; // we got a response: lower layers worked
        final String impliedDetail = "implied (relay answered)";

        // Internet
        if (implied) {
            d.checks.add(Diagnosis.Check.ok(Diagnosis.Check.KIND_INTERNET, impliedDetail));
        } else if (internetOk == null) {
            d.checks.add(Diagnosis.Check.skip(Diagnosis.Check.KIND_INTERNET, "not checked"));
        } else if (internetOk) {
            d.checks.add(Diagnosis.Check.ok(Diagnosis.Check.KIND_INTERNET,
                    "TCP " + PROBE_HOST + ":" + PROBE_PORT));
        } else {
            d.checks.add(Diagnosis.Check.fail(Diagnosis.Check.KIND_INTERNET,
                    "TCP " + PROBE_HOST + ":" + PROBE_PORT + " unreachable"));
        }

        // DNS
        if (implied) {
            d.checks.add(Diagnosis.Check.ok(Diagnosis.Check.KIND_DNS, impliedDetail));
        } else if (Boolean.FALSE.equals(dnsOk)) {
            d.checks.add(Diagnosis.Check.fail(Diagnosis.Check.KIND_DNS, host + " not resolvable"));
        } else if (Boolean.TRUE.equals(dnsOk)) {
            d.checks.add(Diagnosis.Check.ok(Diagnosis.Check.KIND_DNS,
                    dnsIp != null && dnsIp.length() > 0 ? host + " -> " + dnsIp : host));
        } else {
            d.checks.add(Diagnosis.Check.skip(Diagnosis.Check.KIND_DNS, "not checked"));
        }

        // Relay port
        if (implied) {
            d.checks.add(Diagnosis.Check.ok(Diagnosis.Check.KIND_RELAY_PORT, impliedDetail));
        } else if (Boolean.TRUE.equals(relayTcpOk)) {
            d.checks.add(Diagnosis.Check.ok(Diagnosis.Check.KIND_RELAY_PORT, label + " open"));
        } else if (Boolean.FALSE.equals(relayTcpOk)) {
            d.checks.add(Diagnosis.Check.fail(Diagnosis.Check.KIND_RELAY_PORT, label + " unreachable"));
        } else {
            d.checks.add(Diagnosis.Check.skip(Diagnosis.Check.KIND_RELAY_PORT, "not checked"));
        }

        // HTTP answer
        if (httpCode >= 400) {
            d.checks.add(Diagnosis.Check.fail(Diagnosis.Check.KIND_HTTP, "HTTP " + httpCode));
        } else {
            d.checks.add(Diagnosis.Check.skip(Diagnosis.Check.KIND_HTTP, "no response"));
        }

        // Headline (most likely root cause first)
        if (implied) {
            if (httpCode == 401) {
                d.headline = Diagnosis.HL_CREDENTIALS;
            } else if (httpCode == 403) {
                d.headline = Diagnosis.HL_FORBIDDEN;
            } else if (httpCode == 404) {
                d.headline = Diagnosis.HL_NOT_FOUND;
            } else {
                d.headline = Diagnosis.HL_HTTP_OTHER;
                d.headlineArg = httpCode;
            }
        } else if (Boolean.FALSE.equals(internetOk)) {
            d.headline = Diagnosis.HL_NO_INTERNET;
        } else if (Boolean.FALSE.equals(dnsOk)) {
            d.headline = Diagnosis.HL_DNS_FAIL;
            d.headlineText = host;
        } else if ("refused".equals(transport)) {
            d.headline = Diagnosis.HL_REFUSED;
            d.headlineText = label;
        } else if ("timeout".equals(transport) && Boolean.TRUE.equals(relayTcpOk)) {
            d.headline = Diagnosis.HL_READ_TIMEOUT;
            d.headlineText = label;
        } else if ("timeout".equals(transport)) {
            d.headline = Diagnosis.HL_UNREACHABLE;
            d.headlineText = label;
        } else {
            d.headline = Diagnosis.HL_OTHER;
            d.headlineText = transport != null && transport.length() > 0
                    ? transport : "unknown error";
        }
        return d;
    }

    /** Minimal diagnosis used when the normal one could not be built. */
    public static Diagnosis fallback(int attempt, String reason, long nextRetryMs) {
        final Diagnosis d = new Diagnosis(attempt, 0);
        d.checks.add(Diagnosis.Check.skip(Diagnosis.Check.KIND_INTERNET, "diagnostic unavailable"));
        d.checks.add(Diagnosis.Check.skip(Diagnosis.Check.KIND_DNS, "diagnostic unavailable"));
        d.checks.add(Diagnosis.Check.skip(Diagnosis.Check.KIND_RELAY_PORT, "diagnostic unavailable"));
        d.checks.add(Diagnosis.Check.fail(Diagnosis.Check.KIND_HTTP,
                (reason != null && reason.length() > 0) ? reason : "unknown error"));
        d.headline = Diagnosis.HL_OTHER;
        d.headlineText = (reason != null && reason.length() > 0) ? reason : "unknown error";
        d.nextRetryMs = nextRetryMs;
        return d;
    }

    /** TCP connect + close, bounded by a timeout. True on success. */
    private static Boolean probeTcp(String host, int port) {
        final Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return Boolean.TRUE;
        } catch (Exception ex) {
            return Boolean.FALSE;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String text(Exception e) {
        final String m = e.getMessage();
        return (m != null && m.length() > 0) ? m : e.getClass().getSimpleName();
    }
}
