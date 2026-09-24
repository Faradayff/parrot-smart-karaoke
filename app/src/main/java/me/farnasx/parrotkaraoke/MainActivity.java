package me.farnasx.parrotkaraoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import me.farnasx.parrotkaraoke.model.LyricIndex;
import me.farnasx.parrotkaraoke.model.LyricLine;
import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.Track;
import me.farnasx.parrotkaraoke.net.CoverLoader;
import me.farnasx.parrotkaraoke.net.Diagnosis;
import me.farnasx.parrotkaraoke.net.Http;
import me.farnasx.parrotkaraoke.net.RelayClient;
import me.farnasx.parrotkaraoke.util.Prefs;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;

/**
 * Single-screen lyrics viewer for the Parrot head unit.
 * Purely presentational: everything (track, active line index, line text)
 * comes from the relay via {@link RelayClient}.
 */
public class MainActivity extends Activity {

    private static final int COLOR_CURRENT_ACTIVE = 0xFF1ED760;
    private static final int COLOR_CURRENT_PAUSED = 0xFF67716A;
    private static final int COLOR_OTHER_ACTIVE = 0xFF8A9098;
    private static final int COLOR_OTHER_PAUSED = 0xFF4A5057;
    private static final int COLOR_PLAIN_ACTIVE = 0xFFC8CCD0;
    private static final int COLOR_PLAIN_PAUSED = 0xFF6B7280;

    private TextView title;
    private TextView subtitle;
    private ImageView cover;
    private LinearLayout band;
    private TextView prev1;
    private TextView current;
    private TextView next1;
    private TextView next2;
    private TextView next3;
    private TextView[] bandNext = new TextView[3];
    private ScrollView plainScroll;
    private TextView plainText;
    private LinearLayout msgBox;
    private TextView msgMain;
    private TextView msgDetail;
    private TextView footer;

    private ImageButton btnPrev;
    private ImageButton btnPlayPause;
    private ImageButton btnNext;

    private RelayClient client;
    private String lastTrackId;

    /** Playback state reported by the relay; drives the play/pause icon. */
    private boolean playing = false;

    /** Last icon used by the play/pause button, so an unchanged state
     *  never forces a relayout on this head unit. */
    private int lastPlayIcon = -1;

    /** Last enabled state of the transport row. */
    private boolean controlsEnabled = true;

    /**
     * Relay credentials + base URL for the transport row: the same snapshot
     * the client was built with (read once in onCreate, like the rest of the
     * poller configuration).
     */
    private String relayUrl;
    private String relayUser;
    private String relayPass;

    /**
     * Last text pushed to each TextView, so a poll that brings back unchanged
     * content is a true no-op: on this old head unit every setText is a
     * relayout + redraw, and repeated polls with the same words used to cost
     * a full layout/render cycle for nothing.
     */
    private final IdentityHashMap<TextView, String> lastText =
            new IdentityHashMap<TextView, String>();

    private void setTextViewText(TextView v, CharSequence text) {
        String s = String.valueOf(text);
        String prev = lastText.get(v);
        if (prev != null && prev.equals(s)) {
            return;
        }
        v.setText(text);
        lastText.put(v, s);
    }

    /** Audio-delay compensation (ms) applied to the active line; from settings. */
    private int delayMs = Prefs.DEFAULT_DELAY_MS;

    /**
     * Debug mode (settings toggle): when off, error screens show only the
     * short headline ("no internet", "relay down", "credentials rejected");
     * when on, the full diagnostic list is appended (checks, HTTP answer,
     * elapsed time). Re-read on every resume; if the failure screen is up it
     * is redrawn at once so a change in Settings applies immediately.
     */
    private boolean debugMode = Prefs.DEFAULT_DEBUG;

    /**
     * Last failed attempt, kept so the failure screen can be redrawn against
     * the current {@link #debugMode} (e.g. right after the user toggled it in
     * Settings) without waiting for the next failed poll.
     */
    private Diagnosis lastDiagnosis;

    /** Live countdown text for "next retry in N s", ticked by a 500 ms timer. */
    private TextView retryLine;
    private final android.os.Handler tickerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable tickerRunnable;
    private long retryDueAt;
    private static final long TICK_MS = 500L;

    private static final SimpleDateFormat HMS =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        title = (TextView) findViewById(R.id.title);
        subtitle = (TextView) findViewById(R.id.subtitle);
        cover = (ImageView) findViewById(R.id.cover);
        band = (LinearLayout) findViewById(R.id.band);
        prev1 = (TextView) findViewById(R.id.prev1);
        current = (TextView) findViewById(R.id.current);
        next1 = (TextView) findViewById(R.id.next1);
        next2 = (TextView) findViewById(R.id.next2);
        next3 = (TextView) findViewById(R.id.next3);
        bandNext[0] = next1;
        bandNext[1] = next2;
        bandNext[2] = next3;
        plainScroll = (ScrollView) findViewById(R.id.plainScroll);
        plainText = (TextView) findViewById(R.id.plainText);
        msgBox = (LinearLayout) findViewById(R.id.msgBox);
        msgMain = (TextView) findViewById(R.id.msgMain);
        msgDetail = (TextView) findViewById(R.id.msgDetail);
        retryLine = (TextView) findViewById(R.id.retryLine);
        footer = (TextView) findViewById(R.id.footer);

        View btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        btnPrev = (ImageButton) findViewById(R.id.btnPrev);
        btnPlayPause = (ImageButton) findViewById(R.id.btnPlayPause);
        btnNext = (ImageButton) findViewById(R.id.btnNext);

        btnPrev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendControl("prev");
            }
        });
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // The icon already reflects the last reported state, so the
                // command is its opposite: playing → pause, paused → resume.
                sendControl(playing ? "pause" : "resume");
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendControl("next");
            }
        });

        showBoot();

        String url = Prefs.get(this, Prefs.KEY_URL, Prefs.DEFAULT_URL);
        String user = Prefs.get(this, Prefs.KEY_USER, Prefs.DEFAULT_USER);
        String pass = Prefs.get(this, Prefs.KEY_PASS, Prefs.DEFAULT_PASS);
        relayUrl = url;
        relayUser = user;
        relayPass = pass;
        int ms = Prefs.getInt(this, Prefs.KEY_POLL_MS, Prefs.DEFAULT_POLL_MS);
        client = new RelayClient(new RelayClient.Callback() {
            public void onStatus(Status s) {
                handleStatus(s);
            }

            public void onDiagnosis(Diagnosis d) {
                showDiagnosis(d);
            }

            public void onRetryScheduled() {
                // The diagnosis screen already shows the retry; its ticker
                // counts it down live.
            }
        }, url, user, pass, ms, new ResourceLabels(this));
        client.start();
    }

    public void onResume() {
        super.onResume();
        // Reload so a value changed in the settings screen applies immediately.
        delayMs = Prefs.getInt(this, Prefs.KEY_DELAY_MS, Prefs.DEFAULT_DELAY_MS);
        boolean newDebug = Prefs.getBool(this, Prefs.KEY_DEBUG, Prefs.DEFAULT_DEBUG);
        if (newDebug != debugMode) {
            debugMode = newDebug;
            if (lastDiagnosis != null) {
                // The failure screen is up: redraw it right away so the toggle
                // takes effect without waiting for the next failed attempt.
                renderErrorState();
            }
        }
    }

    public void onDestroy() {
        clearRetryTicker();
        if (client != null) {
            client.shutdown();
            client = null;
        }
        super.onDestroy();
    }

    // -------------------------------------------------- transport controls

    /**
     * Sends a transport command (next / prev / pause / resume) to the relay's
     * {@code /control} endpoint. Fire-and-forget: the status poller reflects
     * the new state (immediately in wait mode, within the nudged cadence in
     * classic mode), and a silent failure here is acceptable — the next poll
     * still shows the truth, and the debug log keeps what was sent.
     */
    private void sendControl(final String action) {
        if (relayUrl == null) {
            return;
        }
        final String url = RelayClient.controlUrlFor(relayUrl, action);
        final String u = relayUser;
        final String p = relayPass;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Http.post(url, u, p);
                } catch (IOException ignored) {
                    // See above: the next status poll will reconcile.
                }
                if (client != null) {
                    client.nudge();
                }
            }
        }, "control");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Makes the play/pause button show the opposite of the current state
     * (Spotify convention): playing → pause icon, paused → play icon.
     */
    private void updatePlayIcon() {
        int res = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        if (res != lastPlayIcon) {
            btnPlayPause.setImageResource(res);
            lastPlayIcon = res;
        }
    }

    /**
     * The transport row is only useful when the relay is reachable and owns
     * a track; otherwise the buttons would click into the void. The default
     * pressed-dim of {@code setEnabled} gives the disabled look (API 10 has
     * no ripples).
     */
    private void setControlsEnabled(boolean enabled) {
        if (enabled == controlsEnabled) {
            return;
        }
        controlsEnabled = enabled;
        btnPrev.setEnabled(enabled);
        btnPlayPause.setEnabled(enabled);
        btnNext.setEnabled(enabled);
    }

    // ---------------------------------------------------------------- UI

    private void showView(View active) {
        band.setVisibility(active == band ? View.VISIBLE : View.GONE);
        plainScroll.setVisibility(active == plainScroll ? View.VISIBLE : View.GONE);
        msgBox.setVisibility(active == msgBox ? View.VISIBLE : View.GONE);
    }

    private void showBoot() {
        setTextViewText(title, getString(R.string.app_name));
        setTextViewText(subtitle, "");
        showPlainMessage(getString(R.string.connecting), "");
        setTextViewText(footer, getString(R.string.footer_connecting));
    }

    private void showPlainMessage(String main, String detail) {
        showView(msgBox);
        setTextViewText(msgMain, main);
        if (nonEmpty(detail)) {
            msgDetail.setVisibility(View.VISIBLE);
            setTextViewText(msgDetail, detail);
        } else {
            msgDetail.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------- connectivity checks

    /**
     * Shows a failed relay attempt: what was tried, which check failed, and
     * a live countdown of the next retry, so the screen keeps "alive" while
     * the client works in the background.
     */
    private void showDiagnosis(Diagnosis d) {
        lastDiagnosis = d;
        renderErrorState();
    }

    /**
     * (Re)draws the failure screen according to the current {@link #debugMode}:
     * the short headline is always shown, the full check list only in debug
     * mode; the attempt number (footer) and the retry countdown stay in both.
     */
    private void renderErrorState() {
        Diagnosis d = lastDiagnosis;
        if (d == null) {
            return;
        }
        showPlainMessage(headlineFor(d), debugMode ? detailFor(d) : "");
        setTextViewText(footer, getString(R.string.footer_offline_attempt, d.attempt));

        if (d.nextRetryMs > 0) {
            retryDueAt = System.currentTimeMillis() + d.nextRetryMs;
            retryLine.setVisibility(View.VISIBLE);
            ensureTicker();
            tickRetry();
        } else {
            clearRetryTicker();
        }
    }

    private void ensureTicker() {
        if (tickerRunnable == null) {
            tickerRunnable = new Runnable() {
                public void run() {
                    tickRetry();
                }
            };
        }
        tickerHandler.removeCallbacks(tickerRunnable);
        tickerHandler.postDelayed(tickerRunnable, TICK_MS);
    }

    private void tickRetry() {
        if (retryDueAt <= 0 || retryLine == null) {
            return;
        }
        long leftMs = retryDueAt - System.currentTimeMillis();
        if (leftMs > 1000) {
            retryLine.setText(getString(R.string.retry_in, (int) (leftMs / 1000 + 0.5)));
            tickerHandler.postDelayed(tickerRunnable, TICK_MS);
        } else {
            retryLine.setText(R.string.retry_due_now);
        }
    }

    private void clearRetryTicker() {
        if (tickerRunnable != null) {
            tickerHandler.removeCallbacks(tickerRunnable);
        }
        retryDueAt = 0;
        if (retryLine != null) {
            retryLine.setVisibility(View.GONE);
        }
    }

    private String headlineFor(Diagnosis d) {
        switch (d.headline) {
            case Diagnosis.HL_INVALID_URL:
                return getString(R.string.diag_bad_url);
            case Diagnosis.HL_CREDENTIALS:
                return getString(R.string.diag_credentials);
            case Diagnosis.HL_FORBIDDEN:
                return getString(R.string.diag_forbidden);
            case Diagnosis.HL_NOT_FOUND:
                return getString(R.string.diag_not_found);
            case Diagnosis.HL_HTTP_OTHER:
                return getString(R.string.diag_http_other, d.headlineArg);
            case Diagnosis.HL_NO_INTERNET:
                return getString(R.string.diag_no_internet);
            case Diagnosis.HL_DNS_FAIL:
                return getString(R.string.diag_dns_fail, safe(d.headlineText));
            case Diagnosis.HL_REFUSED:
                return getString(R.string.diag_refused, safe(d.headlineText));
            case Diagnosis.HL_READ_TIMEOUT:
                return getString(R.string.diag_read_timeout, safe(d.headlineText));
            case Diagnosis.HL_UNREACHABLE:
                return getString(R.string.diag_unreachable, safe(d.headlineText));
            default:
                return getString(R.string.diag_other, safe(d.headlineText));
        }
    }

    private String detailFor(Diagnosis d) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.diag_attempt_line, d.attempt,
                HMS.format(new Date(d.timeMs))));
        for (int i = 0; i < d.checks.size(); i++) {
            Diagnosis.Check c = d.checks.get(i);
            sb.append('\n').append(stateMark(c)).append("  ").append(checkLabel(c));
            if (c.detail != null && c.detail.length() > 0) {
                sb.append("  ").append(c.detail);
            }
        }
        if (d.elapsedMs > 1000) {
            sb.append('\n').append(getString(R.string.diag_elapsed, (int) (d.elapsedMs / 1000)));
        }
        return sb.toString();
    }

    private static String stateMark(Diagnosis.Check c) {
        switch (c.state) {
            case Diagnosis.Check.STATE_OK:
                return "OK";
            case Diagnosis.Check.STATE_FAIL:
                return "FAIL";
            default:
                return "SKIP";
        }
    }

    private String checkLabel(Diagnosis.Check c) {
        switch (c.kind) {
            case Diagnosis.Check.KIND_URL:
                return getString(R.string.diag_label_url);
            case Diagnosis.Check.KIND_INTERNET:
                return getString(R.string.diag_label_internet);
            case Diagnosis.Check.KIND_DNS:
                return getString(R.string.diag_label_dns);
            case Diagnosis.Check.KIND_RELAY_PORT:
                return getString(R.string.diag_label_port);
            case Diagnosis.Check.KIND_HTTP:
                return getString(R.string.diag_label_http);
            default:
                return "";
        }
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    /** Runs on the main thread (RelayClient posts there). */
    private void handleStatus(Status s) {
        clearRetryTicker();
        // Any live answer — even a relay-reported error — replaces the
        // failure screen: a stale diagnosis must not be redrawn when the
        // user comes back.
        lastDiagnosis = null;

        // Transport row: the icon follows the last reported state and the
        // buttons are only enabled when there is a session and a track to
        // control.
        boolean isPlaying = s.ok && s.playing;
        if (isPlaying != playing) {
            playing = isPlaying;
            updatePlayIcon();
        }
        setControlsEnabled(s.ok && s.track != null);

        if (!s.ok) {
            String detail = s.auth
                    ? (s.error == null ? "" : s.error)
                    : getString(R.string.auth_error_detail);
            showPlainMessage(getString(R.string.relay_error), detail);
            setTextViewText(footer, getString(R.string.footer_relay_error));
            return;
        }

        if (s.track == null) {
            lastTrackId = null;
            setTextViewText(title, getString(R.string.app_name));
            setTextViewText(subtitle, "");
            hideCover();
            showPlainMessage(getString(R.string.no_track), "");
            setTextViewText(footer, getString(R.string.footer_online));
            return;
        }

        boolean newTrack = (lastTrackId == null)
                || (s.track.id != null && !lastTrackId.equals(s.track.id));
        if (newTrack) {
            if (s.track.id != null && s.track.id.length() > 0) {
                lastTrackId = s.track.id;
            }
            updateHeader(s.track);
        }

        boolean active = s.playing;

        // If the user set an audio-delay compensation (e.g. the car audio
        // chain plays ~1 s late), re-derive the active line at
        // positionMs - delayMs, so the words match what is heard now.
        int effIdx = s.line;
        String effText = s.lineText;
        ArrayList<LyricLine> effNext = null;
        if (delayMs > 0 && s.lines != null && s.lines.size() > 0) {
            int li = LyricIndex.indexOfLine(s.lines, s.positionMs, delayMs);
            if (li >= 0) {
                effIdx = li;
                effText = s.lines.get(li).text;
                // The relay's nextLines are anchored at its unshifted index;
                // derive them from the effective index so the band follows
                // the (delay-shifted) current line.
                effNext = LyricIndex.nextBlock(s.lines, li, bandNext.length);
            }
        }

        boolean hasBand = effIdx >= 0
                && (nonEmpty(effText) || (s.lines != null && s.lines.size() > 0));
        if (hasBand) {
            renderBand(s, effIdx, effText, effNext, active);
        } else if (nonEmpty(s.plain)) {
            renderPlain(s.plain, active);
        } else {
            // Show the relay error (e.g. a transient lrclib 503) as detail if any.
            showPlainMessage(getString(R.string.no_lyrics),
                    nonEmpty(s.error) ? s.error : "");
            setTextViewText(footer, getString(active ? R.string.footer_online : R.string.footer_paused));
        }
    }

    private void updateHeader(final Track t) {
        String nm = nonEmpty(t.name) ? t.name : t.artist;
        setTextViewText(title, nonEmpty(nm) ? nm : getString(R.string.unknown_title));
        StringBuilder sb = new StringBuilder();
        if (nonEmpty(t.artist)) {
            sb.append(t.artist);
        }
        if (nonEmpty(t.album)) {
            if (sb.length() > 0) {
                sb.append(" ");
                sb.append('\u00b7');
                sb.append(" ");
            }
            sb.append(t.album);
        }
        setTextViewText(subtitle, sb.toString());

        final String tag = lastTrackId;
        // New track: hide the previous cover until (and unless) the new one
        // arrives — GONE rather than INVISIBLE so a track without art does
        // not leave a grey square behind.
        hideCover();
        if (nonEmpty(t.coverUrl)) {
            CoverLoader.load(t.coverUrl, new CoverLoader.Callback() {
                public void onFinished(Bitmap b, String returnedTag) {
                    boolean same = (tag == null && returnedTag == null)
                            || (tag != null && tag.equals(returnedTag));
                    if (!same) {
                        return;
                    }
                    if (b != null) {
                        cover.setImageBitmap(b);
                        cover.setVisibility(View.VISIBLE);
                    } else {
                        // The URL was not a usable image: show no cover.
                        cover.setVisibility(View.GONE);
                    }
                }
            }, tag);
        }
    }

    private void hideCover() {
        cover.setImageBitmap(null);
        cover.setVisibility(View.GONE);
    }

    private void renderBand(Status s, int effIdx, String effText,
                            ArrayList<LyricLine> effNext, boolean active) {
        showView(band);
        ArrayList<LyricLine> all = s.lines;
        int n = (all == null) ? 0 : all.size();
        int idx = effIdx;
        if (idx < 0) {
            idx = 0;
        }
        if (n > 0 && idx >= n) {
            idx = n - 1;
        }

        // One previous line above the big current one (see activity_main).
        setBandSlot(prev1, (n > 0 && idx - 1 >= 0) ? all.get(idx - 1).text : "");

        String cur = nonEmpty(effText) ? effText
                : (n > 0 ? all.get(idx).text : "");
        setTextViewText(current, cur);

        for (int i = 0; i < bandNext.length; i++) {
            String xt = "";
            if (effNext != null && effNext.size() > i) {
                xt = effNext.get(i).text;
            } else if (s.nextLines != null && s.nextLines.size() > i) {
                xt = s.nextLines.get(i).text;
            } else if (n > 0 && idx + 1 + i < n) {
                xt = all.get(idx + 1 + i).text;
            }
            setBandSlot(bandNext[i], xt);
        }

        int curColor = active ? COLOR_CURRENT_ACTIVE : COLOR_CURRENT_PAUSED;
        int othColor = active ? COLOR_OTHER_ACTIVE : COLOR_OTHER_PAUSED;
        current.setTextColor(curColor);
        prev1.setTextColor(othColor);
        next1.setTextColor(othColor);
        next2.setTextColor(othColor);
        next3.setTextColor(othColor);

        setTextViewText(footer, getString(active ? R.string.footer_online : R.string.footer_paused));
    }

    private void setBandSlot(TextView v, String text) {
        boolean show = nonEmpty(text);
        v.setVisibility(show ? View.VISIBLE : View.GONE);
        // Hidden slots keep their text (never cleared), so an unchanged text
        // can safely skip the setText on the way back to visible.
        if (show) {
            setTextViewText(v, text);
        }
    }

    private void renderPlain(String text, boolean active) {
        showView(plainScroll);
        setTextViewText(plainText, text);
        plainText.setTextColor(active ? COLOR_PLAIN_ACTIVE : COLOR_PLAIN_PAUSED);
        plainScroll.scrollTo(0, 0);
        setTextViewText(footer, getString(active ? R.string.footer_online : R.string.footer_paused));
    }

    private static boolean nonEmpty(String s) {
        return s != null && s.trim().length() > 0;
    }
}
