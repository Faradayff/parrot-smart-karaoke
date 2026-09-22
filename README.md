# Parrot Smart Karaoke

Aplicación Android **para Android 2.3.7 (API 10)** pensada para la pantalla incrustada de un **Parrot Asteroid** (head unit de coche, pantalla 800×480). Muestra en tiempo real la **letra de la canción sincronizada** (estilo karaoké) junto con **título, artista y álbum**, y la portada del disco.

Es puramente presentacional: **no tiene controles de música**. Toda la música se reproduce desde tu dispositivo habitual (Spotify), y esta app solo pinta lo que suena.

## Hecho para usarse junto a `spotify-lyrics-relay`

> **Este proyecto no funciona solo.** Está diseñado para usarse **en combinación con
> [spotify-lyrics-relay](https://github.com/Faradayff/spotify-lyrics-relay)**, un
> servicio auto-hospedado (Go) que mantiene tu sesión de Spotify y expone el estado
> de reproducción como JSON.

La división de responsabilidades:

| | `spotify-lyrics-relay` | **este proyecto** |
|---|---|---|
| Token OAuth de Spotify | sí (lo mantiene y lo refresca) | no |
| Consultar LRCLIB y resolver la línea actual | sí (`line`, `lineText`, `lines`, `nextLines`) | no |
| HTTP | servidor | cliente (polling) |
| UI | HTML de estado / APIs | banda karaoké en la pantalla del coche |

La app **solo consume `GET /status`** del relay:

```
GET /status  →  200 JSON
{
  "ok": true, "auth": true, "playing": true, "positionMs": 42000,
  "track": { "id", "name", "artist", "album", "uri", "durMs", "cover": [...] },
  "lyricsSynced": true, "lyricsLines": 54,
  "line": 16,                // índice 0-based de la línea activa (la calcula el relay)
  "lineText": "…",           // texto de esa línea, listo para pintar
  "lines":  [ { "t": ms, "text": "…" }, … ],
  "nextLines": [ …hasta 3… ],
  "plain": "solo si no existe letra sincronizada"
}
```

## Por qué HTTP + Basic Auth

Android 2.3.7 solo habla **TLS 1.0** y su tienda de CAs (≈2012) **no confía en las raíces de Let's Encrypt**. La configuración asumida es:

- un **vhost sobre el puerto 80** (HTTP) del relay, protegido con **Basic Auth** en el proxy (p. ej. el proxy inverso de Synology), montando solo `/status`, para que `/login`, `/callback`, `/control` y `/logout` sigan solo en el vhost HTTPS (Let's Encrypt) del navegador.
- la app añade `Authorization: Basic …` a cada petición (user/pass desde la pantalla de ajustes, nunca en el binario).

## Uso

1. Ten el relay corriendo y un vhost HTTP+Basic Auth apuntando a `GET /status`
   (ver el [README del relay](https://github.com/Faradayff/spotify-lyrics-relay)).
2. Copia `parrot-karaoke.apk` (o el APK de `app/build/outputs/apk/debug/`) al pen drive
   y instálalo desde el gestor de archivos del head unit, o:
   ```
   adb install parrot-karaoke.apk
   ```
3. Abre **Parrot Karaoke** → botón **AJUSTES** → URL del relay, usuario y contraseña
   del Basic Auth (y, opcionalmente, el intervalo de sondeo) → **GUARDAR**.
4. A reproducir: la app muestra en verde la línea actual, atenuadas 2 anteriores y
   hasta 3 siguientes, con portada y estado (EN LÍNEA / PAUSA / SIN RED / error de auth).

Comportamiento de red: sondeo adaptativo (≈1 s mientras la línea avanza, 3–5 s en
pausa o esperando pista, backoff 5/10/15 s en errores de transporte) y reintentos
permanentes. Sin red, conserva el último estado hasta volver.

## Desarrollo

- **Stack**: Java (sin lambdas, sin androidx, sin librerías de terceros),
  `minSdk 10` / `targetSdk 10`, AGP 8.5 + Gradle 8.7 + JDK 17.
- Estructura:
  - `model/` — `Status`, `Track`, `LyricLine`, `StatusParser` (JSON → modelo, testeable en JVM).
  - `net/` — `Http` (HttpURLConnection + Base64), `RelayClient` (loop de sondeo con intervalos adaptativos), `CoverLoader` (portadas downsampladas).
  - `MainActivity` — UI de banda karaoké y estados (reproduciendo, pausa, sin letras, sin red, error del relay).
  - `SettingsActivity` — URL, user/pass, intervalo.
  - `util/Prefs` — claves y valores por defecto.
- Build y tests:
  ```
  ./gradlew :app:testDebugUnitTest   # unit tests del parser (9 casos)
  ./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
  ```
