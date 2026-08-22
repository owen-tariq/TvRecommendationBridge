# TvRecommendationBridge (TvHop)

[![Stars](https://img.shields.io/github/stars/owen-tariq/TvRecommendationBridge?style=flat-square&label=stars&color=4c8dff)](../../stargazers)
[![Downloads](https://img.shields.io/github/downloads/owen-tariq/TvRecommendationBridge/total?style=flat-square&label=downloads&color=7fe7c4)](../../releases)
[![Build](https://img.shields.io/github/actions/workflow/status/owen-tariq/TvRecommendationBridge/build.yml?style=flat-square&label=build)](../../actions)

Pick a movie or show on your Google TV home screen and have it open in
**Nuvio** or **Stremio**, automatically.

Free, no account, no subscription, no API key. Install it and it works.

**[⬇ Download the latest APK](../../releases/latest)**

---

## What it does

Tap a title on the Google TV home screen. Google TV opens its own detail page
for it — and at that moment TvHop reads which title it is, looks it up, and
opens the matching page in your player. No second press.

That's the whole app. It doesn't host, store, stream or provide any video; it
turns something you picked into a link and hands it to an app you already
installed.

## Requirements

- A device using the **Google TV** launcher (Chromecast with Google TV, Google
  TV Streamer, or a Google TV set from Sony / TCL / Hisense and friends). The
  older Android TV launcher also works. Fire TV does not.
- **Nuvio** and/or **Stremio** installed.

## Install

Not on Google Play, so it's a manual (sideloaded) install.

### From your phone

1. Install [Send Files to TV](https://play.google.com/store/apps/details?id=com.jstenpal.sendfilestotv)
   on both the TV and your phone.
2. Download `TvHop.apk` from [Releases](../../releases/latest).
3. Send it to the TV and open it to install. Allow installs from unknown
   sources if prompted.

### Via ADB

```bash
adb connect <tv-ip>:5555
adb install TvHop.apk
```

## Set it up

Open TvHop from the TV's app list:

1. **Pick your app** — Nuvio or Stremio.
2. **Turn on the service** — On modern Android TV versions, the system blocks sideloaded apps from enabling Accessibility through the settings menu. **You must use ADB to enable the service:**

   ```bash
   adb shell settings put secure enabled_accessibility_services com.owentariq.tvhop/com.owentariq.tvhop.CardClickAccessibilityService
   adb shell settings put secure accessibility_enabled 1
   ```

   *(If you try to turn it on with your remote in the settings and it immediately turns itself back off, use the ADB command above. It's the only way.)*

3. **Run test** — resolves a known title and opens it, confirming network
   access and the hand-off without hunting for a card first.

Then just use the home screen normally. When a title is picked you'll see
`Found "…" — looking it up…` followed by `Opening … in Nuvio`.

### It stops working after a while

Some manufacturers kill background services aggressively. Exclude TvHop from
any battery optimiser or "RAM cleaner", and on TCL boxes:

```bash
adb shell appops set com.owentariq.tvhop APP_AUTO_START allow
adb shell appops set com.owentariq.tvhop APP_ASSOC_START allow
```

### It opened the wrong title, or nothing happened

Open TvHop and look at **"What TvHop saw"** at the bottom — the last dozen
things the service handled, no ADB needed:

```
detail: "Snowpiercer" (2013) [movie]
open:   "Snowpiercer" (auto, from detail page) → NUVIO
```

- `not a detail page (no play/buy/episodes actions)` — the page wasn't
  recognised as belonging to one title.
- `detail page, but no title among N nodes: …` — the page was recognised but
  its title view wasn't; the line lists the view ids found.
- `✗ "…" — best was "…" score N < 55, not opening` — nothing matched
  confidently, so nothing was opened rather than guessing.

Same detail over ADB:

```bash
adb logcat -s TvHopService:D MetaResolver:D TargetLauncher:D
```

## How it works

| Piece | Detail |
|---|---|
| Trigger | An `AccessibilityService` scoped to the TV launcher and Google TV, watching for a detail page opening |
| Why the detail page | Home-screen cards expose only layout scaffolding to accessibility — a card announces itself as "Column 1". The detail page has already resolved which title you picked and renders its name |
| Finding the title | Prefers the view Google names as the title (`…:id/title`, `movie_title`, …) over guessing at positions |
| Identification | [Cinemeta](https://v3-cinemeta.strem.io), Stremio's public metadata addon — no API key, returns IMDb ids directly |
| Matching | Title, year and type are scored together; below a confidence floor nothing opens, because opening the wrong film is worse than opening none |
| Stremio hand-off | `stremio:///detail/{type}/{id}`, its documented URL scheme |
| Nuvio hand-off | Nuvio publishes no URL scheme, so TvHop starts its exported `MainActivity` with the `contentId` / `contentType` extras its own "Continue watching" channel uses |
| Not looping | A title is opened once; backing out of Nuvio returns you to the detail page without being thrown out again. Clicking something on the home screen re-arms it |

### Privacy

The service is restricted in `accessibility_service_config.xml` to the
launcher and Google TV, and to focus and click events. It can't see what you
type or do in any other app. The only thing that leaves the device is a title
being looked up on Cinemeta. No analytics, no account, no server of ours.

## Building

```bash
gradle assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The Gradle wrapper jar isn't committed — use a local Gradle 8.11+.

Unsigned release builds are fine locally, but CI refuses to produce one: an
unsigned APK fails to install with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`, and
publishing one from a green build is worse than failing. CI signs using four
repository secrets — `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` — and refreshes the `latest` release on every push to `main`.

To sign locally, create `keystore.properties` in the project root (it's
gitignored):

```properties
storeFile=../release.jks
storePassword=…
keyAlias=tvhop
keyPassword=…
```

## Legal

TvHop is an independent navigation tool. It does not host, store, distribute
or provide movies, series, streams, torrents or any other audiovisual content,
and has no control over what any other app on your device can play. What you
watch in Nuvio or Stremio, and whether that is lawful where you are, is
between you and those apps.

Not affiliated with, sponsored by or endorsed by Google, Nuvio or Stremio. All
trademarks belong to their respective owners.

Metadata comes from Cinemeta. TvHop is not endorsed or certified by Stremio.
