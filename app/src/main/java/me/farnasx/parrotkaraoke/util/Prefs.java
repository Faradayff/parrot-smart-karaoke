package me.farnasx.parrotkaraoke.util;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences facade with the app's keys and defaults. */
public final class Prefs {

    public static final String KEY_URL = "relay_url";
    public static final String KEY_USER = "relay_user";
    public static final String KEY_PASS = "relay_pass";
    public static final String KEY_POLL_MS = "poll_ms";

    public static final String DEFAULT_URL = "http://lyrics.farnasx.synology.me/status";
    public static final String DEFAULT_USER = "";
    public static final String DEFAULT_PASS = "";
    public static final int DEFAULT_POLL_MS = 1000;

    private Prefs() {
    }

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences("parrot-karaoke", Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key, String def) {
        String v = sp(ctx).getString(key, def);
        return v == null ? "" : v;
    }

    public static int getInt(Context ctx, String key, int def) {
        return sp(ctx).getInt(key, def);
    }
}
