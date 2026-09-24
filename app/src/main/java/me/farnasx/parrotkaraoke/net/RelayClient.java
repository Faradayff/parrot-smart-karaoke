package me.farnasx.parrotkaraoke.net;

import android.os.Handler;
import android.os.Looper;

import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.StatusParser;

/**
 * Polls the relay {@code /status} endpoint on a background thread and pushes
 * results to the main looper.
 *
 * <p><b>Long-poll ("wait") mode.</b> When the relay answers with a
 * {@code version} field, the client switches from "poll every N ms" to
 * "ask the relay to hold the response until the visible state changes (or its
 * wait timeout elapses)". That turns a steady stream of requests (3,3/s at a
 * 300 ms interval) into about one request per lyric-line change, with the same
 * perceived latency — and no CPU at all while waiting, which is what the old
 * head unit needs. The switch is fully automatic: every request already carries
 * the wait parameters (a relay without the feature ignores them and answers
 * without a {@code version}, which keeps the classic polling mode).
 *
 * <p><b>Classic polling fallback</b> (relay without {@code version}): the
 * cadence adapts itself:
 * <ul>
 *   <li>fast (default 1 s) while a synced line is advancing,</li>
 *   <li>3 s while playing without a usable line,</li>
 *   <li>5 s when paused or waiting for a track,</li>
 *   <li>backoff (5 s / 10 s / 15 s) on persistent transport/relay errors.</li>
 * </ul>
 *
 * <p>Brief transport blips are retried quietly (a one-second socket hiccup
 * must not flip the screen to the error state); once they persist,
 * every failed attempt produces a {@link Diagnosis} (internet, DNS, relay
 * port, HTTP answer) via {@link Diagnostics}, so the UI can show what is
 * happening on each retry instead of a static "connecting" message.
 */
public final class RelayClient {

    public interface Callback {
        void onStatus(Status status);

        /** A poll failed; {@code diagnosis} explains why. */
        void onDiagnosis(Diagnosis diagnosis);

        /** A retry has been scheduled; the client is backing off now. */
        void onRetryScheduled();
    }

    /** Persistent relay error (e.g. Spotify not authenticated): slow down a lot. */
    private static final long RELAY_ERROR_DELAY_MS = 15000;
    private static final long NO_TRACK_DELAY_MS = 5000;
    private static final long PAUSED_DELAY_MS = 5000;
    private static final long PLAYING_NO_LINE_DELAY_MS = 3000;

    // ---------------------------------------------------------------- wait mode
    /** Ask the relay to hold the response until the state changes or this much time elapses. */
    static final int WAIT_TIMEOUT_MS = 8000;
    /** Read timeout while waiting: the relay's hold time + margin for the proxy. */
    static final int WAIT_READ_TIMEOUT_MS = WAIT_TIMEOUT_MS + 8000;
    /** Transport failures tolerated quietly before the first diagnosis screen. */
    static final int WAIT_SILENT_RETRIES = 4;
    /** Gap between silent retries. */
    private static final long WAIT_RETRY_DELAY_MS = 500;
    /**
     * An unchanged answer faster than this means the relay did not actually
     * hold the request: pace the next retry so a misbehaving relay cannot
     * hot-loop us.
     */
    static final long WAIT_UNCHANGED_MIN_HOLD_MS = 3000;
    /** Protective gap before re-waiting when the relay answered unchanged too fast. */
    static final long WAIT_UNCHANGED_GUARD_MS = 1000;

    private final Callback callback;
    /** Localized words for the diagnostic check details (device locale). */
    private final Diagnostics.Labels labels;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Configuration, re-read on every poll: the SettingsActivity can change it
    // without restarting the client.
    private volatile String url;
    private volatile String user;
    private volatile String pass;
    private volatile int baseIntervalMs;

    /**
     * Last state version seen from the relay; -1 = unknown yet. Once ≥ 0 the
     * client is in wait mode and passes it back as {@code sinceVersion}.
     */
    private volatile long lastSeenVersion = -1L;

    private volatile boolean running;
    private Thread thread;

    /**
     * Deadline until which the next classic-mode sleep must be shortened:
     * sent by a control action (next / play-pause) so the fresh state is not
     * held back by a 3-5 s cadence. One-shot; in wait mode it is irrelevant
     * because the held request already wakes up on state changes.
     */
    private volatile long nudgeUntilMs;

    public RelayClient(Callback callback, String url, String user, String pass,
                        int baseIntervalMs, Diagnostics.Labels labels) {
        this.callback = callback;
        this.url = url;
        this.user = user;
        this.pass = pass;
        this.baseIntervalMs = Math.max(250, baseIntervalMs);
        this.labels = (labels != null) ? labels : new Diagnostics.English();
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "relay-client");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void shutdown() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Ask the poller to pick up the state soon (a control action was just
     * sent and will change it). In classic polling mode this caps the next
     * sleep; in wait mode the held request is already waking on any change.
     */
    public void nudge() {
        nudgeUntilMs = System.currentTimeMillis() + 5000;
    }

    /** Remaining nudge time, consumed once; 0 when none is pending. */
    private long nudgeLeftMs() {
        long left = nudgeUntilMs - System.currentTimeMillis();
        nudgeUntilMs = 0L;
        return (left > 0) ? left : 0L;
    }

    private void loop() {
        int consecutiveFailures = 0;
        while (running) {
            long startMs = System.currentTimeMillis();
            Status status;
            Exception failure = null;
            try {
                long since = lastSeenVersion;
                String requestUrl = waitUrl(url, since);
                // Wait mode may legitimately take the relay's whole hold period
                // before a byte arrives; classic mode only needs the short one.
                int readTimeoutMs = (since >= 0) ? WAIT_READ_TIMEOUT_MS : Http.READ_TIMEOUT_MS;
                byte[] body = Http.get(requestUrl, user, pass, readTimeoutMs);
                status = StatusParser.parse(new String(body, "UTF-8"));
            } catch (Exception e) {
                status = null;
                failure = e;
            }

            if (status != null) {
                consecutiveFailures = 0;

                if (status.version >= 0) {
                    // ------------------------------------------------- wait mode
                    boolean changed = (lastSeenVersion < 0)
                            || (status.version != lastSeenVersion);
                    lastSeenVersion = status.version;
                    postStatus(status);
                    if (changed) {
                        // State advanced: render it and immediately ask the
                        // relay to hold the next response until it changes again.
                        continue;
                    }
                    // State did not change while we waited (normal case: the
                    // relay hit its own timeout and sent the unchanged state).
                    long heldMs = System.currentTimeMillis() - startMs;
                    if (heldMs < WAIT_UNCHANGED_MIN_HOLD_MS) {
                        // The relay answered without actually holding the
                        // request: pace ourselves so we do not hammer it.
                        sleepInterruptible(WAIT_UNCHANGED_GUARD_MS);
                    }
                    continue;
                }

                // The relay does not (or no longer) advertise a state version:
                // classic adaptive polling.
                lastSeenVersion = -1L;
                postStatus(status);
                long delay = delayFor(status, baseIntervalMs);
                long nudgeLeft = nudgeLeftMs();
                if (nudgeLeft > 0 && nudgeLeft < delay) {
                    delay = nudgeLeft;
                }
                sleepInterruptible(delay);
            } else {
                // --------------------------------------------------- failures
                consecutiveFailures++;
                if (shouldRetrySilently(consecutiveFailures, failure)) {
                    // Brief blip (socket dropped, proxy hiccup): keep the last
                    // good state on screen and simply try again in a moment.
                    sleepInterruptible(WAIT_RETRY_DELAY_MS);
                    continue;
                }

                int attempt = escalationAttempt(consecutiveFailures);
                long delay = backoff(attempt);
                // Run the connectivity probes and build the diagnosis for the
                // UI. Guarded: a failing diagnostic must never kill the poller.
                Diagnosis diagnosis = null;
                try {
                    diagnosis = Diagnostics.failedAttempt(url, attempt, failure, delay, labels);
                } catch (Exception diagEx) {
                    diagnosis = null;
                }
                postDiagnosis(diagnosis, failure, attempt, delay);
                sleepInterruptible(delay);
            }
        }
    }

    private void postStatus(final Status s) {
        main.post(new Runnable() {
            public void run() {
                callback.onStatus(s);
            }
        });
    }

    private void postDiagnosis(Diagnosis diagnosis, Exception failure,
                               final int attempt, final long nextRetryMs) {
        final String reason = (failure == null) ? null : failure.toString();
        main.post(new Runnable() {
            public void run() {
                if (diagnosis != null) {
                    callback.onDiagnosis(diagnosis);
                } else {
                    // The diagnostic itself failed: still show the error.
                    callback.onDiagnosis(Diagnostics.fallback(
                            attempt, reason, nextRetryMs, labels));
                }
                // Announce the next retry so the UI can count it down.
                callback.onRetryScheduled();
            }
        });
    }

    // ------------------------------------------------------- pure decisions
    // (package-visible + static: unit-testable on the JVM without Android)

    /**
     * True when the Nth consecutive transport failure should be retried
     * quietly (short delay, no diagnosis screen). HTTP status answers from
     * the relay (4xx/5xx) are definitive and always escalate at once.
     */
    static boolean shouldRetrySilently(int consecutiveFailures, Exception failure) {
        if (failure instanceof Http.HttpException) {
            return false;
        }
        return consecutiveFailures <= WAIT_SILENT_RETRIES;
    }

    /** 1-based attempt number reported once the silent window is past. */
    static int escalationAttempt(int consecutiveFailures) {
        int beyond = consecutiveFailures - WAIT_SILENT_RETRIES;
        return (beyond <= 0) ? 1 : beyond;
    }

    /** Classic-mode cadence for a successful response. */
    static long delayFor(Status s, int baseIntervalMs) {
        if (!s.ok) {
            return RELAY_ERROR_DELAY_MS;
        }
        if (s.track == null) {
            return NO_TRACK_DELAY_MS;
        }
        if (s.playing && s.line >= 0 && s.lineText.length() > 0) {
            return baseIntervalMs;
        }
        if (s.playing) {
            return Math.max(baseIntervalMs, PLAYING_NO_LINE_DELAY_MS);
        }
        return Math.max(baseIntervalMs, PAUSED_DELAY_MS);
    }

    /** Backoff ladder for escalated failures: 5 s / 10 s / 15 s. */
    static long backoff(int consecutiveErrors) {
        if (consecutiveErrors <= 1) {
            return 5000;
        }
        if (consecutiveErrors <= 3) {
            return 10000;
        }
        return 15000;
    }

    /**
     * Derives the relay control URL from the configured status URL:
     * {@code http://host/status} → {@code http://host/control?action=next}.
     * A trailing {@code /status} (or an already-present {@code /control}) is
     * stripped; everything before it — scheme, host, and base paths like
     * {@code /lyrics} — is kept.
     *
     * @param action one of {@code next}, {@code prev}, {@code pause}, {@code resume}
     */
    public static String controlUrlFor(String statusUrl, String action) {
        String base = (statusUrl == null) ? "" : statusUrl.trim();
        int q = base.indexOf('?');
        if (q >= 0) {
            base = base.substring(0, q);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/status")) {
            base = base.substring(0, base.length() - "/status".length());
        } else if (base.endsWith("/control")) {
            base = base.substring(0, base.length() - "/control".length());
        }
        return base + "/control?action=" + action;
    }

    /** Appends the long-poll query parameters to the relay status URL. */
    static String waitUrl(String baseUrl, long sinceVersion) {
        String u = (baseUrl == null) ? "" : baseUrl;
        String sep = u.indexOf('?') >= 0 ? "&" : "?";
        return u + sep + "wait=1&timeoutMs=" + WAIT_TIMEOUT_MS
                + "&sinceVersion=" + sinceVersion;
    }

    private void sleepInterruptible(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (running) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(left, 250));
            } catch (InterruptedException ie) {
                return;
            }
        }
    }
}
