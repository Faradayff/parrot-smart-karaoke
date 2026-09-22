package me.farnasx.parrotkaraoke.net;

import android.os.Handler;
import android.os.Looper;

import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.StatusParser;

import java.io.IOException;

/**
 * Polls the relay {@code /status} endpoint on a background thread and pushes
 * results to the main looper.
 *
 * <p>The polling cadence adapts itself:
 * <ul>
 *   <li>fast (default 1 s) while a synced line is advancing,</li>
 *   <li>3 s while playing without a usable line,</li>
 *   <li>5 s when paused or waiting for a track,</li>
 *   <li>backoff (5 s / 10 s / 15 s) on transport errors and persistent relay errors.</li>
 * </ul>
 */
public final class RelayClient {

    public interface Callback {
        void onStatus(Status status);

        void onTransportError(String message);
    }

    /** Persistent relay error (e.g. Spotify not authenticated): slow down a lot. */
    private static final long RELAY_ERROR_DELAY_MS = 15000;
    private static final long NO_TRACK_DELAY_MS = 5000;
    private static final long PAUSED_DELAY_MS = 5000;
    private static final long PLAYING_NO_LINE_DELAY_MS = 3000;

    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Configuration, re-read on every poll: the SettingsActivity can change it
    // without restarting the client.
    private volatile String url;
    private volatile String user;
    private volatile String pass;
    private volatile int baseIntervalMs;

    private volatile boolean running;
    private Thread thread;

    public RelayClient(Callback callback, String url, String user, String pass,
                        int baseIntervalMs) {
        this.callback = callback;
        this.url = url;
        this.user = user;
        this.pass = pass;
        this.baseIntervalMs = Math.max(250, baseIntervalMs);
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

    private void loop() {
        int consecutiveErrors = 0;
        while (running) {
            Status status;
            String errorMsg;
            long delayMs;
            try {
                byte[] body = Http.get(url, user, pass);
                String text = new String(body, "UTF-8");
                status = StatusParser.parse(text);
                errorMsg = null;
                consecutiveErrors = 0;
                delayMs = delayFor(status);
            } catch (final Exception e) {
                IOException io = (e instanceof IOException) ? (IOException) e
                        : new IOException(String.valueOf(e));
                status = null;
                errorMsg = describe(io);
                consecutiveErrors++;
                delayMs = backoff(consecutiveErrors);
            }

            if (status != null) {
                final Status ok = status;
                main.post(new Runnable() {
                    public void run() {
                        callback.onStatus(ok);
                    }
                });
            } else {
                final String msg = errorMsg;
                main.post(new Runnable() {
                    public void run() {
                        callback.onTransportError(msg);
                    }
                });
            }
            sleepInterruptible(delayMs);
        }
    }

    private long delayFor(Status s) {
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

    private static long backoff(int consecutiveErrors) {
        if (consecutiveErrors <= 1) {
            return 5000;
        }
        if (consecutiveErrors <= 3) {
            return 10000;
        }
        return 15000;
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) {
            m = e.getClass().getSimpleName();
        }
        return m;
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
