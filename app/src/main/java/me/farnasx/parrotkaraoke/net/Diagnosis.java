package me.farnasx.parrotkaraoke.net;

import java.util.ArrayList;

/**
 * Snapshot of a failed relay attempt, built by {@link Diagnostics}.
 *
 * <p>Pure data (no Android imports) so the classification logic stays
 * unit-testable on the JVM. The UI maps {@code headline} and
 * {@link Check#kind} to localized strings.
 */
public final class Diagnosis {

    /** One line of the on-screen check list. */
    public static final class Check {
        public static final int KIND_URL = 0;
        public static final int KIND_INTERNET = 1;
        public static final int KIND_DNS = 2;
        public static final int KIND_RELAY_PORT = 3;
        public static final int KIND_HTTP = 4;

        public static final int STATE_OK = 0;
        public static final int STATE_FAIL = 1;
        public static final int STATE_SKIP = 2;

        public final int kind;
        public final int state;
        /** Free detail text (host, address, HTTP code, reason); may be null. */
        public final String detail;

        private Check(int kind, int state, String detail) {
            this.kind = kind;
            this.state = state;
            this.detail = detail;
        }

        public static Check ok(int kind, String detail) {
            return new Check(kind, STATE_OK, detail);
        }

        public static Check fail(int kind, String detail) {
            return new Check(kind, STATE_FAIL, detail);
        }

        public static Check skip(int kind, String detail) {
            return new Check(kind, STATE_SKIP, detail);
        }
    }

    /** Headline kinds (the UI maps them to string resources). */
    public static final int HL_INVALID_URL = 0;
    public static final int HL_CREDENTIALS = 1;
    public static final int HL_FORBIDDEN = 2;
    public static final int HL_NOT_FOUND = 3;
    public static final int HL_HTTP_OTHER = 4;
    public static final int HL_NO_INTERNET = 5;
    public static final int HL_DNS_FAIL = 6;
    public static final int HL_REFUSED = 7;
    public static final int HL_READ_TIMEOUT = 8;
    public static final int HL_UNREACHABLE = 9;
    public static final int HL_OTHER = 10;

    /** 1-based retry counter from the client. */
    public final int attempt;
    /** Wall-clock time of the run. */
    public final long timeMs;
    /** How long the attempt + probes took. */
    public final long elapsedMs;
    /** One of the HL_* codes. */
    public int headline;
    /** Extra argument for the headline (e.g. the HTTP code); 0 when unused. */
    public int headlineArg;
    /** Optional headline context (host:port, reason); may be null. */
    public String headlineText;
    /** Check-list lines, in the order they should be shown. */
    public final ArrayList<Check> checks;
    /** Filled by the caller before display: when the next retry happens. */
    public long nextRetryMs;

    Diagnosis(int attempt, long elapsedMs) {
        this.attempt = attempt;
        this.timeMs = System.currentTimeMillis();
        this.elapsedMs = elapsedMs;
        this.headline = HL_OTHER;
        this.headlineArg = 0;
        this.headlineText = null;
        this.checks = new ArrayList<Check>();
        this.nextRetryMs = 0;
    }
}
