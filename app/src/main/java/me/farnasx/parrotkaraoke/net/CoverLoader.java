package me.farnasx.parrotkaraoke.net;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

/**
 * Downloads an image URL (album cover from i.scdn.co) and posts a downsampled
 * {@link Bitmap} to the main thread. Failures post a null bitmap.
 */
public final class CoverLoader {

    public interface Callback {
        /** @param tag the tag passed to {@link #load}, to discard stale results */
        void onFinished(Bitmap bitmap, String tag);
    }

    private static final int MAX_SIDE_PX = 240;

    public static void load(final String url, final Callback callback, final String tag) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(new Runnable() {
            public void run() {
                Bitmap b = null;
                try {
                    byte[] data = Http.get(url, null, null);
                    b = decodeDownsampled(data, MAX_SIDE_PX);
                } catch (Exception e) {
                    b = null;
                }
                final Bitmap out = b;
                main.post(new Runnable() {
                    public void run() {
                        callback.onFinished(out, tag);
                    }
                });
            }
        }, "cover-loader");
        t.setDaemon(true);
        t.start();
    }

    /** Decodes with an in-sample size so the bitmap stays small (2.3.x memory). */
    static Bitmap decodeDownsampled(byte[] data, int maxSide) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > maxSide && sample < 64) {
            sample <<= 1;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }
}
