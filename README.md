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
2. Download `parrot-karaoke.apk` from the
   [latest release](https://github.com/Faradayff/parrot-smart-karaoke/releases)
   and install it from the head unit's file manager (USB drive), or:
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
Without a connection it shows a live diagnosis on every attempt — general internet
reachability (TCP probe to a public host), DNS of the relay, the relay port, and the
HTTP answer (e.g. 401 = credentials rejected) — with the attempt number and
a running countdown of the next retry, so the screen never looks frozen.

## Asteroid Tweaker (third-party app)

This repo used to carry an unofficial build of **Asteroid Tweaker** in the root
(`AsteroidTweaker-unoff.apk`, app v2.5, package `com.funky.asteroid.asteroidtweaker`)
for convenient sideloading on the Asteroid. It is a **third-party head-unit
utility, not part of this project**, used to tweak the head unit's system
settings (it requests `WRITE_SETTINGS`, `RECEIVE_BOOT_COMPLETED`, and
`REBOOT` permissions). The local copy has been removed from the repository to
keep the root clean; keep it aside separately if you need it, and install it
like any other APK.

## Localization

English is the default (base resources in `res/values/`). When the device locale
is Spanish, the whole UI switches automatically to Spanish
(translated `res/values-es/`) — no in-app setting is required, Android picks
the matching resources from the device configuration.

The free-text fragments of the connectivity diagnostics are localized through
the `Diagnostics.Labels` interface: the Android side passes
`ResourceLabels` (built from the string resources), while the JVM unit tests
use the built-in `Diagnostics.English`, so the classification logic stays
pure and testable without Android.

Adding more languages: copy `res/values-es/strings.xml` to
`res/values-<lang>/strings.xml`, translate it, and translate the fragments in
`ResourceLabels` (or add a new `Diagnostics.Labels` implementation if the
word choices do not fit the resources).

## Releases

Every change is published as a [GitHub Release](https://github.com/Faradayff/parrot-smart-karaoke/releases)
with the installable `parrot-karaoke.apk`, its version, and a changelog
(commits since the previous release). To publish one:

1. Bump `versionName`/`versionCode` in `app/build.gradle`.
2. Commit the change.
3. Tag and push: `git tag v<versionName> && git push origin v<versionName>`.

The `Release` workflow (`.github/workflows/release.yml`) then builds the APK
on GitHub Actions and creates the release automatically.

## Development

- **Stack**: Java (no lambdas, no androidx, no third-party libraries),
  `minSdk 10` / `targetSdk 10`, AGP 8.5 + Gradle 8.7 + JDK 17.
- Structure:
  - `model/` — `Status`, `Track`, `LyricLine`, `LyricIndex` (active line at `positionMs − delay`, testable on the JVM), `StatusParser` (JSON → model, testable on the JVM).
  - `net/` — `Http` (HttpURLConnection + Base64, typed `HttpException` with the status code), `RelayClient` (polling loop with adaptive intervals), `Diagnostics` (failsafe check per attempt: internet TCP probe, DNS, relay port, HTTP status → `Diagnosis` shown in the UI), `CoverLoader` (downsampled covers).
  - `MainActivity` — karaoke band UI and states (playing, paused, no lyrics, relay error, and a live connectivity diagnostic per retry attempt with a countdown).
  - `SettingsActivity` — URL, user/pass, interval, lyrics delay (− / + stepper, 0.1 s steps).
  - `util/Prefs` — keys and default values.
- Build and tests:
  ```
  ./gradlew :app:testDebugUnitTest   # parser + diagnostics unit tests (28 cases)
  ./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
  ```
  - **Local development builds**: the relay defaults (URL, Basic Auth user/pass)
    are injected at build time from `app/local-dev.properties` — copy
    `local-dev.properties.example` and fill it in. That file is **gitignored**,
    so a development APK for your own relay stays out of the repository; the
    public releases (built in CI without the file) ship with **empty** defaults,
    i.e. no relay URL and no credentials anywhere in the binary.
