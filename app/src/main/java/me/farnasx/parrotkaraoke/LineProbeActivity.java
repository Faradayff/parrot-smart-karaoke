package me.farnasx.parrotkaraoke;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import me.farnasx.parrotkaraoke.model.Status;
import me.farnasx.parrotkaraoke.model.StatusParser;
import me.farnasx.parrotkaraoke.net.Http;
import me.farnasx.parrotkaraoke.util.Prefs;

/**
 * PROBE ONLY (test build, do not ship).
 *
 * <p>Replicates the app's "current lyric line" view against four line-cap
 * variants, then dumps exactly what the platform reports about each one
 * (line count, layout width, ellipsized width, measured size) plus the raw
 * delivered line text from the relay. Used to pin down why the Parrot head
 * unit caps the big line at two lines even with maxLines=3.
 */
public final class LineProbeActivity extends Activity {

    /** The exact long phrase that got cut in the car. */
    private static final String SAMPLE =
            "La va a pagar el notas al que le he dado un vole\u00f3n (\u00bfc\u00f3mo?, \u00bfc\u00f3mo?)";

    private TextView diag;
    private ScrollView diagScroll;
    private TextView probeA;
    private TextView probeB;
    private TextView probeC;
    private TextView probeD;
    private boolean diagBaseDone = false;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lineprobe);

        diag = (TextView) findViewById(R.id.diag);
        diagScroll = (ScrollView) findViewById(R.id.diagScroll);
        probeA = (TextView) findViewById(R.id.pA);
        probeB = (TextView) findViewById(R.id.pB);
        probeC = (TextView) findViewById(R.id.pC);
        probeD = (TextView) findViewById(R.id.pD);

        probeA.setText(SAMPLE);
        probeB.setText(SAMPLE);
        probeC.setText(SAMPLE);
        probeD.setText(SAMPLE);

        // Relay check: what did the server actually deliver? (background)
        new Thread(new Runnable() {
            public void run() {
                fetchStatus();
            }
        }).start();

        // After the first layout pass, report what the platform decided.
        diag.post(new Runnable() {
            public void run() {
                dumpDiag();
            }
        });
    }

    private String probeInfo(String tag, TextView v) {
        StringBuilder sb = new StringBuilder();
        sb.append(tag);
        Layout l = v.getLayout();
        if (l == null) {
            sb.append("  layout=<null (not laid out yet)>");
        } else {
            sb.append("  lineCount=").append(l.getLineCount())
              .append("  layoutW=").append(l.getWidth())
              .append("  ellipsizedW=").append(l.getEllipsizedWidth());
        }
        sb.append("  measured=").append(v.getMeasuredWidth())
          .append("x").append(v.getMeasuredHeight());
        return sb.toString();
    }

    private void dumpDiag() {
        if (diagBaseDone) {
            return;
        }
        diagBaseDone = true;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("== DEVICE ==\n");
        sb.append("screen px=").append(dm.widthPixels).append("x").append(dm.heightPixels)
          .append("  density=").append(dm.density)
          .append("  dpi=").append(dm.densityDpi)
          .append("  sdk=").append(android.os.Build.VERSION.SDK_INT).append("\n\n");
        sb.append("== PROBES (same text, four caps) ==\n");
        sb.append("A  maxLines=3 + ellipsize (the real view):\n").append(probeInfo("  ", probeA)).append("\n");
        sb.append("B  no maxLines, no ellipsize (free wrap):\n").append(probeInfo("  ", probeB)).append("\n");
        sb.append("C  maxLines=5 + ellipsize:\n").append(probeInfo("  ", probeC)).append("\n");
        sb.append("D  maxLines=3, no ellipsize:\n").append(probeInfo("  ", probeD)).append("\n\n");
        sb.append("== WHAT TO LOOK FOR ==\n");
        sb.append("B and C wrapping past 3 visible lines while A stops at 2\n");
        sb.append("with an ellipsis means the cap is the ellipsize+maxLines\n");
        sb.append("combination. If B also stops at 2 lines, the text itself\n");
        sb.append("(or the platform, not our caps) is the cause.\n");
        push(sb.toString());
    }

    /** Fetch the relay /status (same URL/credentials the app is configured with). */
    private void fetchStatus() {
        String url = Prefs.get(this, Prefs.KEY_URL, Prefs.DEFAULT_URL);
        String user = Prefs.get(this, Prefs.KEY_USER, Prefs.DEFAULT_USER);
        String pass = Prefs.get(this, Prefs.KEY_PASS, Prefs.DEFAULT_PASS);
        if (url.length() == 0) {
            push("relay: no URL configured\n");
            return;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String statusUrl = url + "/status";
        String linePreview;
        int len;
        String rest;
        try {
            byte[] body = Http.get(statusUrl, user, pass);
            Status s = StatusParser.parse(new String(body, "UTF-8"));
            String lt = (s.lineText == null) ? "" : s.lineText;
            len = lt.length();
            linePreview = (len > 60) ? lt.substring(0, 60) : lt;
            rest = "  nextLines=" + s.nextLines.size()
                    + "  prevLineLen=" + ((s.prevLine == null) ? 0 : s.prevLine.length());
        } catch (final Exception e) {
            push("relay: could not fetch /status (" + e.getClass().getSimpleName()
                    + ": " + msgClip(e.toString()) + ")\n");
            return;
        }
        push("== DELIVERED (relay /status) ==\n"
                + "lineText chars=" + len + "  head=\"" + linePreview + "\"\n"
                + rest + "\n"
                + "If lineText chars < " + SAMPLE.length() + ", the relay (or the\n"
                + "lyric provider) truncated the line before the app saw it.\n");
    }

    private static String msgClip(String s) {
        s = (s == null) ? "" : s.replace('\n', ' ').replace('\r', ' ');
        return (s.length() > 80) ? s.substring(0, 80) : s;
    }

    private void push(final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                if (diag.length() > 0) {
                    diag.append("\n");
                }
                diag.append(text);
                diagScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
