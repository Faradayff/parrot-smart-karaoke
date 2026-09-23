package me.farnasx.parrotkaraoke.util;

import android.content.Context;
import android.content.SharedPreferences;

import me.farnasx.parrotkaraoke.BuildConfig;

/**
 * SharedPreferences facade with the app's keys and defaults.
 *
 * <p>The relay defaults come from the build ({@link BuildConfig}): official
 * builds ship empty (the user fills them in via Settings), while local
 * development builds can be preconfigured through
 * {@code app/local-dev.properties} (gitignored, see the .example file).
 */
public final class Prefs {

    public static final String KEY_URL = "relay_url";
    public static final String KEY_USER = "relay_user";
    public static final String KEY_PASS = "relay_pass";
    public static final String KEY_POLL_MS = "poll_ms";
    public static final String KEY_DELAY_MS = "lyrics_delay_ms";

    public static final String DEFAULT_URL = BuildConfig.DEFAULT_RELAY_URL;
    public static final String DEFAULT_USER = BuildConfig.DEFAULT_RELAY_USER;
    public static final String DEFAULT_PASS = BuildConfig.DEFAULT_RELAY_PASS;
    public static final int DEFAULT_POLL_MS = 1000;

    /** Audio-delay compensation for the lyrics, in milliseconds. */
    public static final int DEFAULT_DELAY_MS = 0;
    public static final int DELAY_STEP_MS = 100; // 0.1 s granularity
    public static final int DELAY_MIN_MS = 0;
    public static final int DELAY_MAX_MS = 30000; // 30 s is plenty for a car audio chain

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
