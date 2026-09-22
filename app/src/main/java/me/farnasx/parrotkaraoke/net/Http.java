package me.farnasx.parrotkaraoke.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.util.Base64;

/**
 * Minimal blocking HTTP GET. Plain {@code HttpURLConnection} + {@code android.util.Base64}
 * so it runs on Android 2.3 without any library.
 */
public final class Http {

    public static final int CONNECT_TIMEOUT_MS = 3000;
    public static final int READ_TIMEOUT_MS = 6000;
    public static final int MAX_BODY_BYTES = 512 * 1024;

    private Http() {
    }

    /**
     * GETs {@code url} and returns the body bytes.
     *
     * @throws IOException on network errors or non-2xx status codes
     */
    public static byte[] get(String url, String user, String pass) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "parrot-karaoke/1.0 (Android)");
            if (user != null && user.length() > 0
                    && pass != null && pass.length() > 0) {
                byte[] auth = (user + ":" + pass).getBytes("UTF-8");
                c.setRequestProperty("Authorization",
                        "Basic " + Base64.encode(auth, Base64.NO_WRAP));
            }

            int code = c.getResponseCode();
            InputStream in;
            if (code >= 400 && code <= 599) {
                in = c.getErrorStream();
            } else {
                in = c.getInputStream();
            }
            byte[] body = readAll(in);
            if (code < 200 || code >= 300) {
                String snippet = new String(body, "UTF-8");
                if (snippet.length() > 120) {
                    snippet = snippet.substring(0, 120);
                }
                throw new IOException("HTTP " + code + " " + snippet);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_BODY_BYTES) {
                throw new IOException("body too large");
            }
        }
        in.close();
        return bos.toByteArray();
    }
}
