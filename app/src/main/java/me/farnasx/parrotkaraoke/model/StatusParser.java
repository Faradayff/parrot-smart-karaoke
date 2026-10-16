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
        s.lineText = displaySpace(o.optString("lineText", "")).trim();
        // Audio-delay compensation echoed back by the relay (informational;
        // -1 = a relay predating the delay feature).
        s.delayMs = o.optLong("delayMs", -1L);
        s.prevLine = displaySpace(o.optString("prevLine", "")).trim();
        s.nextLines = parseLines(o.optJSONArray("nextLines"));
        s.plain = displaySpace(o.optString("plain", ""));

        // Long-poll support: "version": null or non-numeric keeps the -1 default.
        s.version = o.optLong("version", -1L);

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

    /**
     * Maps the invisible "non-breaking" whitespace that some lyrics sources
     * ship inside lines (U+00A0 no-break space, word joiner, zero-width
     * space, figure/narrow no-break spaces, BOM, non-breaking hyphen, ...)
     * into ordinary wrappable characters. Android's StaticLayout will NOT
     * break a line at those characters, so an affected line renders as one
     * giant unbreakable line that gets clipped at both ends of its box.
     * Regular spaces, tabs, newlines and everything else are untouched.
     *
     * It also maps the Cyrillic homoglyphs that some lyric sources smuggle in
     * (А/Е/О/С and lowercase) back to Latin A/E/O/C: the car stock font has no
     * glyph for some of them, so they render as blank holes in otherwise
     * normal text (e.g. "A Dios" losing its "A").
     */
    static String displaySpace(String s) {
        if (s == null || s.length() == 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFEFF') {
                continue; // BOM / zero-width no-break space: drop it.
            }
            if (c == '\u00A0' || c == '\u2007' || c == '\u202F'
                    || c == '\u2060' || c == '\u200B' || c == '\uFFE0'
                    || c == '\uFFF0') {
                out.append(' ');
            } else if (c == '\u2011') {
                out.append('-');
            } else if (c == '\u0410') {
                out.append('A');
            } else if (c == '\u0430') {
                out.append('a');
            } else if (c == '\u0415') {
                out.append('E');
            } else if (c == '\u0435') {
                out.append('e');
            } else if (c == '\u041E') {
                out.append('O');
            } else if (c == '\u043E') {
                out.append('o');
            } else if (c == '\u0421') {
                out.append('C');
            } else if (c == '\u0441') {
                out.append('c');
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
            String text = displaySpace(l.optString("text", "")).trim();
            if (text.length() == 0) {
                continue;
            }
            out.add(new LyricLine(l.optLong("t", 0L), text));
        }
        return out;
    }
}
