package me.farnasx.parrotkaraoke.net;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;

import android.util.Base64;

import me.farnasx.parrotkaraoke.BuildConfig;

/**
 * Minimal blocking HTTP GET. Plain {@code HttpURLConnection} + {@code android.util.Base64}
 * so it runs on Android 2.3 without any library.
 */
public final class Http {

    public static final int CONNECT_TIMEOUT_MS = 3000;
    public static final int READ_TIMEOUT_MS = 6000;
    public static final int MAX_BODY_BYTES = 512 * 1024;

    /**
     * HTTP-level failure: the connection went through and a response arrived,
     * but with a non-2xx status code. {@code code} is the HTTP status,
     * {@code snippet} a short piece of the error body (may be null).
     *
     * <p>{@code authUser}/{@code authSent} record what this request actually
     * carried for Basic Auth, so a 401 diagnosis can point at the real cause
     * (e.g. the header was never sent because user or pass were empty).
     */
    public static final class HttpException extends IOException {
        public final int code;
        public final String snippet;
        /** User included in the Authorization header; null when it was not sent. */
        public final String authUser;
        /** True when an Authorization header was included in the request. */
        public final boolean authSent;

        public HttpException(int code, String snippet) {
            this(code, snippet, null, false);
        }

        public HttpException(int code, String snippet, String authUser, boolean authSent) {
            super("HTTP " + code);
            this.code = code;
            this.snippet = snippet;
            this.authUser = authUser;
            this.authSent = authSent;
        }
    }

    /** Append-only debug trace of what was sent, for development builds only. */
    public static final String DEBUG_LOG_PATH = "/sdcard/parrot-karaoke-debug.log";

    private Http() {
    }

    public static byte[] get(String url, String user, String pass) throws IOException {
        return get(url, user, pass, READ_TIMEOUT_MS);
    }

    /**
     * GETs {@code url} and returns the body bytes.
     *
     * @param readTimeoutMs socket read timeout for this call. A long-poll
     *                      response may legitimately take the relay's whole
     *                      wait period before any byte arrives.
     * @throws IOException on network errors or non-2xx status codes
     */
    public static byte[] get(String url, String user, String pass,
                             int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout((readTimeoutMs > 0) ? readTimeoutMs : READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "parrot-karaoke/1.0 (Android)");

            // Basic Auth is only attached when both parts exist. Recording the
            // decision lets a 401 diagnosis point at the real cause (e.g. the
            // header was never sent because user or pass were empty).
            boolean authSent = false;
            String b64 = null;
            if (user != null && user.length() > 0
                    && pass != null && pass.length() > 0) {
                // encodeToString — note: Base64.encode() returns a byte[] and
                // `"Basic " + byte[]` used to produce a garbage header
                // ("Basic [B@…") that the relay rejects with 401.
                b64 = Base64.encodeToString((user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
                c.setRequestProperty("Authorization", "Basic " + b64);
                authSent = true;
            }
            logDebug(url, user, pass, authSent, b64);

            int code = c.getResponseCode();
            InputStream in;
            if (code >= 400 && code <= 599) {
                in = c.getErrorStream();
            } else {
                in = c.getInputStream();
            }
            byte[] body = readAll(in);
            if (code < 200 || code >= 300) {
                String snippet = new String(body, "UTF-8").trim();
                if (snippet.length() > 120) {
                    snippet = snippet.substring(0, 120);
                }
                throw new HttpException(code, snippet, authSent ? user : null, authSent);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Development-only append-only log of exactly what was about to be sent,
     * so a 401 on the car can be checked on the file system
     * ({@code /sdcard/parrot-karaoke-debug.log}). Never breaks the request.
     */
    private static void logDebug(String url, String user, String pass,
                                 boolean authSent, String b64) {
        if (!BuildConfig.DEBUG_HTTP_LOG) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(new Date().toString())
                .append("  url=").append(url)
                .append("  user=").append(user == null ? "<null>" : user)
                .append("  passLen=").append(pass == null ? -1 : pass.length())
                .append("  authorization=").append(authSent ? "Basic " + b64 : "(none)");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(DEBUG_LOG_PATH, true);
            fos.write((sb.toString() + "\n").getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {
            // Best effort: the log must never kill the request.
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
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
