package me.farnasx.parrotkaraoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import me.farnasx.parrotkaraoke.model.CacheClearResult;
import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.StatusParser;
import me.farnasx.parrotkaraoke.model.Track;
import me.farnasx.parrotkaraoke.net.Http;
import me.farnasx.parrotkaraoke.net.RelayClient;
import me.farnasx.parrotkaraoke.util.Prefs;

/**
 * Minimal settings screen (relay URL, Basic Auth, poll interval, lyrics
 * delay) plus two dangerous relay-maintenance actions: dropping ONE song's
 * cached lyrics ({@code POST /cache/track}) and wiping the whole relay
 * lyrics cache ({@code POST /cache/clear}).
 */
public class SettingsActivity extends Activity {

    private EditText urlEt;
    private EditText userEt;
    private EditText passEt;
    private EditText pollEt;
    private TextView delayValue;
    private CheckBox debugCh;
    private Button trackClearBtn;
    private Button cacheClearBtn;

    /** Live delay value in ms, adjusted with -/+ and persisted on SAVE. */
    private int delayMs;

    /** True while a single-track clear (status lookup + POST) is in flight. */
    private boolean trackBusy;

    /** True while a cache-clear POST is in flight; blocks a second fire. */
    private boolean cacheBusy;

    /** Results of the background wipe come back to the UI through this. */
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * The wipe can spend a while deleting the relay's cache files off its
     * disk; a bit more grace than the 6 s transport commands get.
     */
    private static final int CACHE_CLEAR_READ_TIMEOUT_MS = 15000;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        urlEt = (EditText) findViewById(R.id.setUrl);
        userEt = (EditText) findViewById(R.id.setUser);
        passEt = (EditText) findViewById(R.id.setPass);
        pollEt = (EditText) findViewById(R.id.setPoll);
        delayValue = (TextView) findViewById(R.id.delayValue);
        debugCh = (CheckBox) findViewById(R.id.setDebug);

        // android:inputType is an API 11 manifest attribute; set it programmatically
        // so this also works on 2.3.x (InputType API 1).
        passEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pollEt.setInputType(InputType.TYPE_CLASS_NUMBER);

        urlEt.setText(Prefs.get(this, Prefs.KEY_URL, Prefs.DEFAULT_URL));
        userEt.setText(Prefs.get(this, Prefs.KEY_USER, Prefs.DEFAULT_USER));
        passEt.setText(Prefs.get(this, Prefs.KEY_PASS, Prefs.DEFAULT_PASS));
        pollEt.setText(String.valueOf(Prefs.getInt(this, Prefs.KEY_POLL_MS, Prefs.DEFAULT_POLL_MS)));

        delayMs = Prefs.getInt(this, Prefs.KEY_DELAY_MS, Prefs.DEFAULT_DELAY_MS);
        updateDelayDisplay();

        debugCh.setChecked(Prefs.getBool(this, Prefs.KEY_DEBUG, Prefs.DEFAULT_DEBUG));

        Button delayMinus = (Button) findViewById(R.id.btnDelayMinus);
        delayMinus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                changeDelay(-Prefs.DELAY_STEP_MS);
            }
        });
        Button delayPlus = (Button) findViewById(R.id.btnDelayPlus);
        delayPlus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                changeDelay(+Prefs.DELAY_STEP_MS);
            }
        });
        delayValue.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                delayMs = Prefs.DEFAULT_DELAY_MS;
                updateDelayDisplay();
            }
        });

        Button save = (Button) findViewById(R.id.btnSave);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });

        trackClearBtn = (Button) findViewById(R.id.btnCacheTrackClear);
        trackClearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startTrackClear();
            }
        });

        cacheClearBtn = (Button) findViewById(R.id.btnCacheClear);
        cacheClearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmCacheClear();
            }
        });
    }

    private void changeDelay(int deltaMs) {
        delayMs += deltaMs;
        if (delayMs < Prefs.DELAY_MIN_MS) {
            delayMs = Prefs.DELAY_MIN_MS;
        }
        if (delayMs > Prefs.DELAY_MAX_MS) {
            delayMs = Prefs.DELAY_MAX_MS;
        }
        updateDelayDisplay();
    }

    private void updateDelayDisplay() {
        // In 0.1 s steps the value is always a multiple of 100 ms. The decimal
        // separator comes from the locale (dot in English, comma in Spanish).
        int tenths = delayMs / 100;
        delayValue.setText(getString(R.string.settings_delay_value,
                tenths / 10, tenths % 10));
    }

    private void save() {
        String url = urlEt.getText().toString().trim();
        if (url.length() == 0) {
            url = Prefs.DEFAULT_URL;
        }

        int ms;
        try {
            ms = Integer.parseInt(pollEt.getText().toString().trim());
        } catch (Exception e) {
            ms = Prefs.DEFAULT_POLL_MS;
        }
        if (ms < 250) {
            ms = 250;
        }
        if (ms > 60000) {
            ms = 60000;
        }

        SharedPreferences.Editor ed = Prefs.sp(this).edit();
        ed.putString(Prefs.KEY_URL, url);
        ed.putString(Prefs.KEY_USER, userEt.getText().toString().trim());
        ed.putString(Prefs.KEY_PASS, passEt.getText().toString().trim());
        ed.putInt(Prefs.KEY_POLL_MS, ms);
        ed.putInt(Prefs.KEY_DELAY_MS, delayMs);
        ed.putBoolean(Prefs.KEY_DEBUG, debugCh.isChecked());
        ed.commit();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------------------------------- dangerous (scoped): one song's lyric

    /**
     * Asks the relay to forget the cached lyric of the current track
     * ({@code POST /cache/track?id=&artist=}). The track identity — the
     * id + artist pair — is read live from a one-shot {@code GET /status}
     * (it is the relay's own cache key, so the app round-trips exactly
     * what the relay reports) and is displayed in the confirmation dialog.
     * Same double-confirmation as the full wipe; the rest of the cache is
     * not touched.
     */
    private void startTrackClear() {
        if (trackBusy) {
            return;
        }
        final String url = currentUrl();
        if (url.length() == 0) {
            Toast.makeText(this, R.string.diag_bad_url, Toast.LENGTH_SHORT).show();
            return;
        }
        trackBusy = true;
        trackClearBtn.setEnabled(false);
        trackClearBtn.setText(R.string.settings_cache_working);

        final String u = userEt.getText().toString().trim();
        final String p = passEt.getText().toString().trim();
        Thread t = new Thread(new Runnable() {
            public void run() {
                String statusJson = null;
                Exception fail = null;
                try {
                    byte[] body = Http.get(url, u, p);
                    statusJson = new String(body, "UTF-8");
                } catch (Exception e) {
                    fail = e;
                }
                final String sJson = statusJson;
                final Exception failure = fail;
                main.post(new Runnable() {
                    public void run() {
                        onTrackLookupDone(failure, sJson);
                    }
                });
            }
        }, "cache-track-lookup");
        t.setDaemon(true);
        t.start();
    }

    private void onTrackLookupDone(Exception failure, String statusJson) {
        if (isFinishing()) {
            // The user left the settings screen mid-lookup: the GET is
            // read-only, so the relay still holds everything on its side.
            resetTrackBusy();
            return;
        }

        if (failure != null) {
            resetTrackBusy();
            showTrackClearFail(failure);
            return;
        }

        Status s = StatusParser.parse(statusJson);
        if (s.track == null || s.track.id == null || s.track.id.length() == 0) {
            // The relay is not serving a track: there is nothing to target.
            resetTrackBusy();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_cache_track_failed)
                    .setMessage(R.string.settings_cache_track_no_track)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        // The button stays "Working…" until the confirmation resolves: a
        // second click mid-decision must not fire another lookup.
        final Track track = s.track;
        String name = (track.name.length() > 0) ? track.name : "-";
        String artist = (track.artist.length() > 0) ? track.artist : "-";
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_cache_track_title)
                .setMessage(getString(R.string.settings_cache_track_msg, name, artist))
                .setNegativeButton(R.string.settings_cache_clear_cancel, null)
                .setPositiveButton(R.string.settings_cache_clear_ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                sendTrackClear(track);
                            }
                        })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface d) {
                        resetTrackBusy();
                    }
                })
                .show();
    }

    private void sendTrackClear(final Track track) {
        final String trackUrl =
                RelayClient.cacheTrackUrlFor(currentUrl(), track.id, track.artist);
        final String u = userEt.getText().toString().trim();
        final String p = passEt.getText().toString().trim();

        Thread t = new Thread(new Runnable() {
            public void run() {
                String report = null;
                Exception fail = null;
                try {
                    report = Http.post(trackUrl, u, p);
                } catch (Exception e) {
                    fail = e;
                }
                final String rep = report;
                final Exception failure = fail;
                main.post(new Runnable() {
                    public void run() {
                        onTrackClearDone(failure, rep);
                    }
                });
            }
        }, "cache-track-clear");
        t.setDaemon(true);
        t.start();
    }

    private void onTrackClearDone(Exception failure, String report) {
        if (isFinishing()) {
            // The user left the settings screen mid-clear: the relay still
            // finishes its job on its side, and there is no UI left to update.
            resetTrackBusy();
            return;
        }
        resetTrackBusy();

        if (failure != null) {
            showTrackClearFail(failure);
            return;
        }

        // Both endpoints answer the same {"ok", "removed", "freedBytes"}
        // shape, so the existing parser covers this one too (the extra
        // "id" field is harmless).
        CacheClearResult r = CacheClearResult.parse(report);
        if (!r.ok) {
            // A 200 with "ok":false would be odd, but show it as a failure
            // with whatever message the relay carried.
            showTrackClearFail(new java.io.IOException(
                    getString(R.string.settings_cache_track_failed_relay)
                            + (compact(report).length() > 0 ? ": " + compact(report) : "")));
            return;
        }
        if (r.removed == 0) {
            // Documented no-op: the song was never cached, so the relay
            // acknowledged with nothing to drop — soft success, not an error.
            showTrackClearResult(getString(R.string.settings_cache_track_not_cached));
            return;
        }

        String detail;
        if (r.freedBytes >= 0) {
            detail = getString(R.string.settings_cache_track_cleared_detail,
                    formatBytes(r.freedBytes));
        } else {
            // The relay acknowledged but gave no numbers: show its raw message.
            detail = compact(report);
        }
        showTrackClearResult(detail);
    }

    /** Soft-success dialog for the single-track clear (cleared or no-op). */
    private void showTrackClearResult(String detail) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_cache_track_cleared)
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showTrackClearFail(Exception failure) {
        String detail;
        if (failure instanceof Http.HttpException) {
            Http.HttpException h = (Http.HttpException) failure;
            detail = getString(R.string.settings_cache_failed_http, h.code,
                    (h.snippet == null) ? "" : compact(h.snippet));
        } else {
            detail = failure.toString();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_cache_track_failed)
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void resetTrackBusy() {
        trackBusy = false;
        if (trackClearBtn != null) {
            trackClearBtn.setEnabled(true);
            trackClearBtn.setText(R.string.settings_cache_track);
        }
    }

    /** The relay URL exactly as typed on this screen (falling back on blank). */
    private String currentUrl() {
        String url = urlEt.getText().toString().trim();
        if (url.length() == 0) {
            url = Prefs.DEFAULT_URL;
        }
        return url;
    }

    // ------------------------------------------- dangerous: wipe the cache

    /**
     * Asks the relay to wipe its lyrics cache ({@code POST /cache/clear}).
     * Because it is a dangerous action it is asked twice: a confirmation
     * dialog before the request leaves the device, and while it is in flight
     * the button shows "Working…" so the moment it is free is visible. After
     * that, the relay's own report (entries removed, bytes freed) — or its
     * error — is shown in a dialog.
     */
    private void confirmCacheClear() {
        if (cacheBusy) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_cache_clear_title)
                .setMessage(R.string.settings_cache_clear_msg)
                .setNegativeButton(R.string.settings_cache_clear_cancel, null)
                .setPositiveButton(R.string.settings_cache_clear_ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                sendCacheClear();
                            }
                        })
                .show();
    }

    private void sendCacheClear() {
        // Fire on the exact values the user sees in the form.
        String url = currentUrl();
        if (url.length() == 0) {
            Toast.makeText(this, R.string.diag_bad_url, Toast.LENGTH_SHORT).show();
            return;
        }
        final String clearUrl = RelayClient.cacheClearUrlFor(url);
        final String u = userEt.getText().toString().trim();
        final String p = passEt.getText().toString().trim();

        cacheBusy = true;
        cacheClearBtn.setEnabled(false);
        cacheClearBtn.setText(R.string.settings_cache_working);

        Thread t = new Thread(new Runnable() {
            public void run() {
                String rep = null;
                Exception fail = null;
                try {
                    rep = Http.post(clearUrl, u, p, CACHE_CLEAR_READ_TIMEOUT_MS);
                } catch (Exception e) {
                    fail = e;
                }
                final String report = rep;
                final Exception failure = fail;
                main.post(new Runnable() {
                    public void run() {
                        onCacheClearDone(failure, report);
                    }
                });
            }
        }, "cache-clear");
        t.setDaemon(true);
        t.start();
    }

    private void onCacheClearDone(Exception failure, String report) {
        if (isFinishing()) {
            // The user left the settings screen mid-wipe: the relay still
            // finishes its job on its side, and there is no UI left to update.
            return;
        }
        cacheBusy = false;
        cacheClearBtn.setEnabled(true);
        cacheClearBtn.setText(R.string.settings_cache_clear);

        if (failure != null) {
            showCacheClearFail(failure);
            return;
        }

        CacheClearResult r = CacheClearResult.parse(report);
        if (r.ok) {
            showCacheClearDone(r, report);
        } else {
            // A 200 with "ok":false would be odd, but show it as a failure
            // with whatever message the relay carried.
            showCacheClearFail(new java.io.IOException(
                    getString(R.string.settings_cache_failed_relay)
                            + (compact(report).length() > 0 ? ": " + compact(report) : "")));
        }
    }

    private void showCacheClearDone(CacheClearResult r, String report) {
        String detail;
        if (r.freedBytes >= 0) {
            detail = getString(R.string.settings_cache_cleared_detail,
                    r.removed, formatBytes(r.freedBytes));
        } else {
            // The relay acknowledged but gave no numbers: show its raw message.
            detail = compact(report);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_cache_cleared)
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showCacheClearFail(Exception failure) {
        String detail;
        if (failure instanceof Http.HttpException) {
            Http.HttpException h = (Http.HttpException) failure;
            detail = getString(R.string.settings_cache_failed_http, h.code,
                    (h.snippet == null) ? "" : compact(h.snippet));
        } else {
            detail = failure.toString();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_cache_failed)
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------------------------------------------------------- small helpers

    /** Trims and caps a relay message for dialog display. */
    private static String compact(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() > 200) {
            t = t.substring(0, 200) + "\u2026";
        }
        return t;
    }

    /** 402112 → "402.1 KB" (the device locale's decimal separator). */
    private static String formatBytes(long bytes) {
        if (bytes < 1000) {
            return String.valueOf(bytes) + " B";
        }
        if (bytes < 1000 * 1000) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1000.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB",
                bytes / (1000.0 * 1000.0));
    }
}
