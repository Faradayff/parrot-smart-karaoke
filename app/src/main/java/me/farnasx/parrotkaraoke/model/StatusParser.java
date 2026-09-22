package me.farnasx.parrotkaraoke.model;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Parses a relay {@code GET /status} JSON payload into a {@link Status}.
 *
 * <p>Pure and exception-safe: any malformed input leaves the returned
 * {@link Status} with its default fields (ok=false). No Android imports, so it
 * is unit-testable on the JVM.
 */
public final class StatusParser {

    private StatusParser() {
    }

    public static Status parse(String json) {
        Status s = new Status();
        if (json == null || json.length() == 0) {
            return s;
        }
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            return s;
        }

        s.ok = o.optBoolean("ok", false);
        s.auth = o.optBoolean("auth", false);
        s.error = o.optString("error", "");
        s.playing = o.optBoolean("playing", false);
        s.positionMs = o.optLong("positionMs", 0L);
        s.lyricsSynced = o.optBoolean("lyricsSynced", false);
        s.lyricsLines = o.optInt("lyricsLines", 0);
        if (o.has("line")) {
            try {
                s.line = o.getInt("line");
            } catch (Exception e) {
                // null "line" or non-numeric value: keep -1.
            }
        }
        s.lineText = o.optString("lineText", "");
        s.lines = parseLines(o.optJSONArray("lines"));
        s.nextLines = parseLines(o.optJSONArray("nextLines"));
        s.plain = o.optString("plain", "");

        JSONObject t = o.optJSONObject("track");
        if (t != null) {
            Track tr = new Track();
            tr.id = t.optString("id", "");
            tr.name = t.optString("name", "");
            tr.artist = t.optString("artist", "");
            tr.album = t.optString("album", "");
            tr.durMs = t.optInt("durMs", 0);
            JSONArray cover = t.optJSONArray("cover");
            if (cover != null && cover.length() > 0) {
                tr.coverUrl = cover.optString(0, "");
            }
            if (tr.id.length() > 0 || tr.name.length() > 0) {
                s.track = tr;
            }
        }
        return s;
    }

    private static ArrayList<LyricLine> parseLines(JSONArray arr) {
        ArrayList<LyricLine> out = new ArrayList<LyricLine>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject l = arr.optJSONObject(i);
            if (l == null) {
                continue;
            }
            String text = l.optString("text", "").trim();
            if (text.length() == 0) {
                continue;
            }
            out.add(new LyricLine(l.optLong("t", 0L), text));
        }
        return out;
    }
}
