# Parrot Smart Karaoke

An Android app **for Android 2.3.7 (API 10)** designed for the built-in screen of a **Parrot Asteroid** (car head unit, 800×480 display). It shows in real time the **synchronized song lyrics** (karaoke-style) along with the **title, artist, and album**, plus the album cover (shown only when the track has art).

It's mostly presentational — the song itself is played from your regular device (Spotify) — but it carries a small **transport row (previous / play-pause / next)**: those buttons send `POST /control` commands to the relay, and the play/pause button always shows the opposite of the current state (pause icon while playing, play icon while paused).

## Made to be used alongside `spotify-lyrics-relay`

> **This project does not work on its own.** It is designed to be used **in combination with
> [spotify-lyrics-relay](https://github.com/Faradayff/spotify-lyrics-relay)**, a
> self-hosted service (Go) that keeps your Spotify session alive and exposes the playback
> state as JSON.

Division of responsibilities:

| | `spotify-lyrics-relay` | **this project** |
|---|---|---|
| Spotify OAuth token | yes (holds and refreshes it) | no |
| Query LRCLIB and resolve the current line | yes (`line`, `lineText`, `prevLine`, `nextLines`), **applying the requested `delayMs`** (so the line is what is heard now) | no |
| Audio-delay compensation | yes — resolves `line`/`lineText`/`prevLine`/`nextLines` at `positionMs − delayMs` and echoes `delayMs` | sends the user's `delayMs` on every request; renders the served line directly (no local re-derivation) |
| HTTP | server | client (long-poll, classic polling fallback) |
| UI | status HTML / APIs | karaoke band + transport row on the car screen |

The app consumes two relay endpoints — `GET /status` (state) and
`POST /control` (playback commands):

```
GET /status  →  200 JSON
{
  "ok": true, "auth": true, "playing": true, "positionMs": 42000,
  "track": { "id", "name", "artist", "album", "uri", "durMs", "cover": [...] },
  "lyricsSynced": true, "lyricsLines": 54,
  "line": 16,                // 0-based index of the active line (computed by the relay at positionMs − delayMs)
  "lineText": "…",           // text of that line, ready to render
  "prevLine": "…",           // text of the line above the active one ("" at the first line)
  "nextLines": [ …up to 3… ],
  "delayMs": 1200,           // audio-delay applied by the relay (echoed back for diagnostics)
  "lyricsStatus": "synced",  // synced | plain | none | loading — how the lyrics were resolved
  "pendingMs": 45000,        // only while "loading": ≈ ms until the relay's next attempt
  "version": 42,             // state version (incremented when the visible state changes)
  "plain": "only if no synced lyrics exist"
}
```

While the lyrics are not resolved yet (first fetch in flight, or a
provider refusal riding out the relay's own backoff) the relay reports
`"lyricsStatus": "loading"` — with a `pendingMs` hint and **no error**:
the app shows a quiet "Loading the lyrics…" state, never an error, and the
relay's version bump when the lyrics (or their absence) arrive wakes the
wait connection.

and for the transport row:

```
POST /control?action=next|prev|pause|resume   →   200 { "ok": true, "action": "…" }
```

The app derives the control URL from the configured status URL
(`http://host/status` → `http://host/control?action=…`), keeps the same
Basic Auth, and nudges its poller right after a command so the new state
(showing on the play/pause icon) arrives without waiting out the whole
cadence. A rejected command is a silent no-op for the screen: the next
status poll still reconciles, and the request lands in the debug log.

**Long-poll ("wait") mode** (when the relay implements `version`): the app also
accepts holding a request open until the state changes, so it stops polling at a
fixed interval and gets one response **per state change** instead — the right
trade for the old head unit:

```
GET /status?wait=1&timeoutMs=8000&sinceVersion=42
    → 200 JSON (current state + version) once `version` > 42,
       or after `timeoutMs`, or immediately when `sinceVersion` is -1/absent
```

A relay without the feature ignores the parameters and answers immediately
without a `version` field; the app then keeps its classic adaptive polling
(≈1 s while a line is advancing, 3–5 s when paused or waiting for a track,
5/10/15 s backoff on transport errors) and permanent retries.

## Why HTTP + Basic Auth

Android 2.3.7 only speaks **TLS 1.0**, and its CA store (≈2012) **does not trust Let's Encrypt roots**. The assumed setup is:

- a **vhost on port 80** (HTTP) of the relay, protected with **Basic Auth** at the proxy (e.g. the Synology reverse proxy), mounting `/status`, `POST /control`, **and `POST /cache/clear`** — the endpoints the car needs, incl. the transport row and the settings screen's "Clear lyrics cache" button (otherwise the button answers with a `404`) — so that `/login`, `/callback`, and `/logout` stay only on the browser's HTTPS vhost (Let's Encrypt). The cache-wipe command is also run by hand from any machine — see [Wiping the lyrics cache](#wiping-the-lyrics-cache-post-cacheclear).
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
3. Open **Parrot Karaoke** → the **gear button** in the header → relay URL, user,
   and password for the Basic Auth (and optionally the polling interval) → **SAVE**.
   If the music arrives delayed in the car (typical ~1 s), set **Lyrics delay**
   (0.1 s granularity, 0–30 s) so the words follow what you actually hear. The
   app forwards the value to the relay, which resolves the active line and the
   surrounding band at `positionMs − delayMs` (the words you actually hear). It
   is saved permanently and takes effect as soon as you come back.
4. Hit play: the app shows the current line in green, the 1 previous one dimmed,
   and up to 2 upcoming lines, with the album cover (top-left, only when the track
   has art), the status (ONLINE / PAUSED / OFFLINE / auth error), and working
   **previous / play-pause / next** buttons in the header — they call the relay's
   `/control` endpoint (the HTTP vhost must forward it, see above).

Network behavior: with a wait-capable relay the app long-polls — one request
per state change, held open at most `timeoutMs` at a time. With an older relay
it falls back to adaptive polling (≈1 s while a line is advancing, 3–5 s when
paused or waiting for a track, 5/10/15 s backoff on transport errors) and
permanent retries.
Without a connection it shows a live diagnosis on every attempt — general internet
reachability (TCP probe to a public host), DNS of the relay, the relay port, and the
HTTP answer (e.g. 401 = credentials rejected) — with the attempt number and
a running countdown of the next retry, so the screen never looks frozen.

## Wiping the lyrics cache (`POST /cache/clear`)

Since **relay v1.2** the relay remembers resolved lyrics for a long time
(see the [relay's *Lyrics cache* section](https://github.com/Faradayff/spotify-lyrics-relay#lyrics-cache)).
That is normally what you want — the lyrics API is off the hot path — but if
[lrcLib](https://lrclib.net) later serves a **corrected** version of the lyrics
and the old (cached) copy keeps showing on the screen, the way to see the new
text right away is to wipe the relay's cache:

```
POST /cache/clear   →   200 { "ok": true, "removed": 137, "freedBytes": 402112 }
```

What it does:

- **POST only.** Any other method answers `405` — typing the URL in a browser
  can never wipe anything by accident.
- **No body, no parameters.** `removed` = entries dropped (memory + disk),
  `freedBytes` ≈ disk space reclaimed. HTTP `500` with `{"ok":false,"error":…}`
  only if the wipe itself fails.
- It clears **only the lyrics cache** — the in-memory map and the files under
  the relay's state volume. Your **Spotify session is untouched**, and the app
  needs no change: it never caches lyrics locally, it always renders what the
  relay serves.
- Wiped songs are fetched from the provider again the next time they play: the
  app will briefly show its quiet **"Loading the lyrics…"** state (the relay's
  `lyricsStatus: "loading"`), then the new text renders as usual.

When it is actually needed:

- **Corrected lyrics** on lrcLib — call it and re-play the song.
- Reclaiming disk space, or resetting a cache that looks stale.
- Otherwise *not*: a song that had no lyrics yet is re-checked by the relay
  within 1 day automatically (404 shadows are memory-only and short-lived),
  and cached lyrics expire after 365 days. So "new song got lyrics later"
  needs no action — a full wipe is only for forcing a refresh *now*.

In the app both are deliberate, one-off actions behind **two buttons at the
bottom of Settings**, clearly apart from the form and both confirming first
(their color mirrors the damage: orange sits above red):

- **Orange — "Clear this song's lyrics"**
  (`POST /cache/track?id=<trackId>&artist=<artist>`): the app reads a fresh
  `GET /status` to learn the id+artist pair it must target (that pair IS the
  relay's cache key), names the song in the confirmation dialog, and drops
  *only* that cached lyric. A song that was never cached answers
  `"removed":0` — a soft success, not an error. Only use this for the case
  where one song's lyric is wrong/old: **corrected lyrics** on lrcLib.
- **Red — "Clear lyrics cache"** (`POST /cache/clear`): wipes every cached
  lyric. Use it to reclaim disk space or reset a cache that looks stale.

On both, while the request is in flight the button shows "Working…", and
afterwards the app shows the relay's own report (*N entries removed ·
N KB freed on disk*) or its error (HTTP code + snippet). For either button to
work, the car's HTTP vhost must forward `/cache/track` and `/cache/clear` in
addition to `/status` and `/control`. They are also one-off commands from any
machine:

```bash
# drop ONE song's cached lyric (trackId/artist are the values /status reports):
curl -X POST -u USER:PASS \
  'https://your-public.hostname/lyrics/cache/track?id=4c0sRXhoW2ZtN2pJ4c5xv3&artist=The+Beatles'
# → {"ok":true,"id":"4c0sRXhoW2ZtN2pJ4c5xv3","removed":1,"freedBytes":87312}

# wipe the WHOLE lyrics cache:
curl -X POST -u USER:PASS https://your-public.hostname/lyrics/cache/clear
# → {"ok":true,"removed":137,"freedBytes":402112}
```

(Use the relay's base path in the URL, as with `/status` and `/control`.)

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
  - `model/` — `Status`, `Track`, `LyricLine`, `LyricIndex` (active line at `positionMs − delay`, testable on the JVM), `StatusParser` (JSON → model, testable on the JVM), `CacheClearResult` (parses the `POST /cache/clear` report — `ok`, `removed`, `freedBytes` — testable on the JVM).
  - `net/` — `Http` (HttpURLConnection + Base64, `get` and `post` — the latter returns the response body —, typed `HttpException` with the status code, per-call read timeout), `RelayClient` (long-poll/wait loop when the relay advertises `version`, classic adaptive polling otherwise; quiet retries before the first diagnosis; `controlUrlFor` and `cacheClearUrlFor` derive the `/control` and `/cache/clear` URLs from the configured status URL, and `nudge` shortens the next poll after a control action), `Diagnostics` (failsafe check per attempt: internet TCP probe, DNS, relay port, HTTP status → `Diagnosis` shown in the UI), `CoverLoader` (downsampled covers).
  - `MainActivity` — karaoke band UI and states (playing, paused, lyrics loading, no lyrics, relay error, and a live connectivity diagnostic per retry attempt with a countdown; the full check list is shown only when the Settings "Debug mode" toggle is on, otherwise just the short headline — the attempt number stays visible in both), a transport row (previous / play-pause / next) that POSTs to the relay's `/control` endpoint and whose central button flips between a play and a pause icon with `Status.playing`, and an album cover that is only visible while it actually shows an image.
  - `SettingsActivity` — URL, user/pass, interval, lyrics delay (− / + stepper, 0.1 s steps), Debug mode toggle, and the dangerous "Clear lyrics cache" button (confirmation dialog, "Working…" while the POST is in flight, success/failure dialog with the relay's report or its HTTP error).
  - `util/Prefs` — keys and default values.
- Build and tests:
  ```
  ./gradlew :app:testDebugUnitTest   # parser, cache-wipe report, diagnostics, relay-client (incl. control- & cache-clear-URL derivation) and lyric-index unit tests (61 cases)
  ./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
  ```
  - **Local development builds**: the relay defaults (URL, Basic Auth user/pass)
    are injected at build time from `app/local-dev.properties` — copy
    `local-dev.properties.example` and fill it in. That file is **gitignored**,
    so a development APK for your own relay stays out of the repository; the
    public releases (built in CI without the file) ship with **empty** defaults,
    i.e. no relay URL and no credentials anywhere in the binary.
