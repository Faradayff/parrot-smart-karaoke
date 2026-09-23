package me.farnasx.parrotkaraoke;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import me.farnasx.parrotkaraoke.model.LyricIndex;
import me.farnasx.parrotkaraoke.model.LyricLine;
import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.Track;
import me.farnasx.parrotkaraoke.net.CoverLoader;
import me.farnasx.parrotkaraoke.net.RelayClient;
import me.farnasx.parrotkaraoke.util.Prefs;

import java.util.ArrayList;

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
    private TextView prev2;
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

    private RelayClient client;
    private String lastTrackId;
    private boolean everConnected;

    /** Audio-delay compensation (ms) applied to the active line; from settings. */
    private int delayMs = Prefs.DEFAULT_DELAY_MS;

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
        prev2 = (TextView) findViewById(R.id.prev2);
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
        footer = (TextView) findViewById(R.id.footer);

        View btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        showBoot();

        String url = Prefs.get(this, Prefs.KEY_URL, Prefs.DEFAULT_URL);
        String user = Prefs.get(this, Prefs.KEY_USER, Prefs.DEFAULT_USER);
        String pass = Prefs.get(this, Prefs.KEY_PASS, Prefs.DEFAULT_PASS);
        int ms = Prefs.getInt(this, Prefs.KEY_POLL_MS, Prefs.DEFAULT_POLL_MS);
        client = new RelayClient(new RelayClient.Callback() {
            public void onStatus(Status s) {
                handleStatus(s);
            }

            public void onTransportError(String message) {
                handleOffline();
            }
        }, url, user, pass, ms);
        client.start();
    }

    public void onResume() {
        super.onResume();
        // Reload so a value changed in the settings screen applies immediately.
        delayMs = Prefs.getInt(this, Prefs.KEY_DELAY_MS, Prefs.DEFAULT_DELAY_MS);
    }

    public void onDestroy() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private void showView(View active) {
        band.setVisibility(active == band ? View.VISIBLE : View.GONE);
        plainScroll.setVisibility(active == plainScroll ? View.VISIBLE : View.GONE);
        msgBox.setVisibility(active == msgBox ? View.VISIBLE : View.GONE);
    }

    private void showBoot() {
        title.setText(R.string.app_name);
        subtitle.setText("");
        showPlainMessage(getString(R.string.connecting), "");
        footer.setText(R.string.footer_connecting);
    }

    private void showPlainMessage(String main, String detail) {
        showView(msgBox);
        msgMain.setText(main);
        if (nonEmpty(detail)) {
            msgDetail.setVisibility(View.VISIBLE);
            msgDetail.setText(detail);
        } else {
            msgDetail.setVisibility(View.GONE);
        }
    }

    private void handleOffline() {
        if (!everConnected) {
            showPlainMessage(getString(R.string.offline_title),
                    getString(R.string.offline_detail));
        }
        footer.setText(R.string.footer_offline);
    }

    /** Runs on the main thread (RelayClient posts there). */
    private void handleStatus(Status s) {
        everConnected = true;

        if (!s.ok) {
            String detail = s.auth
                    ? (s.error == null ? "" : s.error)
                    : getString(R.string.auth_error_detail);
            showPlainMessage(getString(R.string.relay_error), detail);
            footer.setText(R.string.footer_relay_error);
            return;
        }

        if (s.track == null) {
            lastTrackId = null;
            title.setText(R.string.app_name);
            subtitle.setText("");
            cover.setImageBitmap(null);
            showPlainMessage(getString(R.string.no_track), "");
            footer.setText(R.string.footer_online);
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
        if (delayMs > 0 && s.lines != null && s.lines.size() > 0) {
            int li = LyricIndex.indexOfLine(s.lines, s.positionMs, delayMs);
            if (li >= 0) {
                effIdx = li;
                effText = s.lines.get(li).text;
            }
        }

        boolean hasBand = effIdx >= 0
                && (nonEmpty(effText) || (s.lines != null && s.lines.size() > 0));
        if (hasBand) {
            renderBand(s, effIdx, effText, active);
        } else if (nonEmpty(s.plain)) {
            renderPlain(s.plain, active);
        } else {
            // Show the relay error (e.g. a transient lrclib 503) as detail if any.
            showPlainMessage(getString(R.string.no_lyrics),
                    nonEmpty(s.error) ? s.error : "");
            footer.setText(active ? R.string.footer_online : R.string.footer_paused);
        }
    }

    private void updateHeader(final Track t) {
        String nm = nonEmpty(t.name) ? t.name : t.artist;
        title.setText(nonEmpty(nm) ? nm : getString(R.string.unknown_title));
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
        subtitle.setText(sb.toString());

        final String tag = lastTrackId;
        cover.setImageBitmap(null);
        if (nonEmpty(t.coverUrl)) {
            CoverLoader.load(t.coverUrl, new CoverLoader.Callback() {
                public void onFinished(Bitmap b, String returnedTag) {
                    boolean same = (tag == null && returnedTag == null)
                            || (tag != null && tag.equals(returnedTag));
                    if (same) {
                        cover.setImageBitmap(b);
                    }
                }
            }, tag);
        }
    }

    private void renderBand(Status s, int effIdx, String effText, boolean active) {
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

        setBandSlot(prev2, (n > 0 && idx - 2 >= 0) ? all.get(idx - 2).text : "");
        setBandSlot(prev1, (n > 0 && idx - 1 >= 0) ? all.get(idx - 1).text : "");

        String cur = nonEmpty(effText) ? effText
                : (n > 0 ? all.get(idx).text : "");
        current.setText(cur);

        for (int i = 0; i < bandNext.length; i++) {
            String xt = "";
            if (s.nextLines != null && s.nextLines.size() > i) {
                xt = s.nextLines.get(i).text;
            } else if (n > 0 && idx + 1 + i < n) {
                xt = all.get(idx + 1 + i).text;
            }
            setBandSlot(bandNext[i], xt);
        }

        int curColor = active ? COLOR_CURRENT_ACTIVE : COLOR_CURRENT_PAUSED;
        int othColor = active ? COLOR_OTHER_ACTIVE : COLOR_OTHER_PAUSED;
        current.setTextColor(curColor);
        prev2.setTextColor(othColor);
        prev1.setTextColor(othColor);
        next1.setTextColor(othColor);
        next2.setTextColor(othColor);
        next3.setTextColor(othColor);

        footer.setText(active ? R.string.footer_online : R.string.footer_paused);
    }

    private void setBandSlot(TextView v, String text) {
        if (!nonEmpty(text)) {
            v.setVisibility(View.GONE);
        } else {
            v.setVisibility(View.VISIBLE);
            v.setText(text);
        }
    }

    private void renderPlain(String text, boolean active) {
        showView(plainScroll);
        plainText.setText(text);
        plainText.setTextColor(active ? COLOR_PLAIN_ACTIVE : COLOR_PLAIN_PAUSED);
        plainScroll.scrollTo(0, 0);
        footer.setText(active ? R.string.footer_online : R.string.footer_paused);
    }

    private static boolean nonEmpty(String s) {
        return s != null && s.trim().length() > 0;
    }
}
