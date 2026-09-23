# Parrot Smart Karaoke

An Android app **for Android 2.3.7 (API 10)** designed for the built-in screen of a **Parrot Asteroid** (car head unit, 800×480 display). It shows in real time the **synchronized song lyrics** (karaoke-style) along with the **title, artist, and album**, plus the album cover.

It's purely presentational: **it has no music controls**. All music is played from your regular device (Spotify), and this app only draws what's playing.

## Made to be used alongside `spotify-lyrics-relay`

> **This project does not work on its own.** It is designed to be used **in combination with
> [spotify-lyrics-relay](https://github.com/Faradayff/spotify-lyrics-relay)**, a
> self-hosted service (Go) that keeps your Spotify session alive and exposes the playback
> state as JSON.

Division of responsibilities:

| | `spotify-lyrics-relay` | **this project** |
|---|---|---|
| Spotify OAuth token | yes (holds and refreshes it) | no |
| Query LRCLIB and resolve the current line | yes (`line`, `lineText`, `lines`, `nextLines`) | no |
| HTTP | server | client (polling) |
| UI | status HTML / APIs | karaoke band on the car screen |

The app **only consumes `GET /status`** from the relay:

```
GET /status  →  200 JSON
{
  "ok": true, "auth": true, "playing": true, "positionMs": 42000,
  "track": { "id", "name", "artist", "album", "uri", "durMs", "cover": [...] },
  "lyricsSynced": true, "lyricsLines": 54,
  "line": 16,                // 0-based index of the active line (computed by the relay)
  "lineText": "…",           // text of that line, ready to render
  "lines":  [ { "t": ms, "text": "…" }, … ],
  "nextLines": [ …up to 3… ],
  "plain": "only if no synced lyrics exist"
}
```

## Why HTTP + Basic Auth

Android 2.3.7 only speaks **TLS 1.0**, and its CA store (≈2012) **does not trust Let's Encrypt roots**. The assumed setup is:

- a **vhost on port 80** (HTTP) of the relay, protected with **Basic Auth** at the proxy (e.g. the Synology reverse proxy), mounting only `/status`, so that `/login`, `/callback`, `/control`, and `/logout` stay only on the browser's HTTPS vhost (Let's Encrypt).
- the app adds `Authorization: Basic …` to each request (user/pass from the settings screen, never in the binary).

## Usage

1. Have the relay running and an HTTP+Basic Auth vhost pointing at `GET /status`
   (see the [relay's README](https://github.com/Faradayff/spotify-lyrics-relay)).
2. Copy `parrot-karaoke.apk` (or the APK from `app/build/outputs/apk/debug/`) to a USB
   drive and install it from the head unit's file manager, or:
    ```
    adb install parrot-karaoke.apk
    ```
3. Open **Parrot Karaoke** → **SETTINGS** button → relay URL, user, and password
   for the Basic Auth (and optionally the polling interval) → **SAVE**.
   If the music arrives delayed in the car (typical ~1 s), set **Lyrics delay**
   (0.1 s granularity, 0–30 s) so the words follow what you actually hear —
   it is saved permanently and takes effect as soon as you come back.
4. Hit play: the app shows the current line in green, the 2 previous ones dimmed,
   and up to 3 upcoming lines, with cover art and status (ONLINE / PAUSED / OFFLINE / auth error).

Network behavior: adaptive polling (≈1 s while a line is advancing, 3–5 s when paused
or waiting for a track, 5/10/15 s backoff on transport errors) and permanent retries.
Without a connection, it keeps the last known state until one returns.

## Development

- **Stack**: Java (no lambdas, no androidx, no third-party libraries),
  `minSdk 10` / `targetSdk 10`, AGP 8.5 + Gradle 8.7 + JDK 17.
- Structure:
  - `model/` — `Status`, `Track`, `LyricLine`, `LyricIndex` (active line at `positionMs − delay`, testable on the JVM), `StatusParser` (JSON → model, testable on the JVM).
  - `net/` — `Http` (HttpURLConnection + Base64), `RelayClient` (polling loop with adaptive intervals), `CoverLoader` (downsampled covers).
  - `MainActivity` — karaoke band UI and states (playing, paused, no lyrics, no network, relay error).
  - `SettingsActivity` — URL, user/pass, interval, lyrics delay (− / + stepper, 0.1 s steps).
  - `util/Prefs` — keys and default values.
- Build and tests:
  ```
  ./gradlew :app:testDebugUnitTest   # parser unit tests (9 cases)
  ./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
  ```
