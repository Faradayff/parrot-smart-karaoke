package me.farnasx.parrotkaraoke.model;

import org.json.JSONObject;

/**
 * Parses a relay {@code POST /cache/clear} JSON payload into the report the
 * settings screen shows:
 * <pre>{"ok":true,"removed":137,"freedBytes":402112}</pre>
 *
 * <p>Pure and exception-safe, like {@link StatusParser}: malformed input
 * leaves the returned result at its default fields ({@code ok=false},
 * {@code removed=0}, {@code freedBytes=-1}). No Android imports, so it is
 * unit-testable on the JVM.
 */
public final class CacheClearResult {

    /** True when the relay reports the wipe succeeded ({@code "ok":true}). */
    public boolean ok;

    /** Cache entries dropped (memory + disk); 0 when the relay did not say. */
    public int removed;

    /** ≈ bytes of disk space reclaimed; -1 when the relay did not say. */
    public long freedBytes = -1L;

    private CacheClearResult() {
    }

    public static CacheClearResult parse(String json) {
        CacheClearResult r = new CacheClearResult();
        if (json == null || json.length() == 0) {
            return r;
        }
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            return r;
        }
        r.ok = o.optBoolean("ok", false);
        if (o.has("removed")) {
            try {
                r.removed = o.getInt("removed");
            } catch (Exception e) {
                // null "removed" or a non-numeric value: keep 0.
            }
        }
        r.freedBytes = o.optLong("freedBytes", -1L);
        return r;
    }
}
