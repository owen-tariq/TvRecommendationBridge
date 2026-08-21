# TvRecommendationBridge (TvHop)

Click a movie or show on your Google TV home screen and have it open in
**Nuvio** or **Stremio** instead of wherever the launcher wanted to send you.

Free, no account, no subscription, no API key. Install it and it works.

**[⬇ Download TvHop.apk](../../releases/latest/download/TvHop.apk)** — latest release, signed and ready to sideload.

---

## What it does

The TV launcher announces the title of a recommendation card when you click
it. TvHop listens for that one event, looks the title up, and opens the
matching page in the player app you chose.

That's the whole app. It does not host, store, stream or provide any
video — it turns a card you clicked into a link and hands it to another app
you already installed.

## Requirements

- A device using the **Google TV** launcher (Chromecast with Google TV, Google
  TV Streamer, or a Google TV set from Sony / TCL / Hisense and friends). The
  older Android TV launcher is also supported. Fire TV is not.
- **Nuvio** and/or **Stremio** installed.

## Install

TvHop isn't on Google Play, so it's a manual (sideloaded) install.

### From your phone

1. Install [Send Files to TV](https://play.google.com/store/apps/details?id=com.jstenpal.sendfilestotv)
   on the TV and on your phone.
2. Download `TvHop.apk` (link at the top of this page) on your phone.
3. Send it to the TV and open it to install. Allow installs from unknown
   sources if prompted.

### Via ADB

```bash
adb connect <tv-ip>:5555
adb install TvHop.apk
```

## Set it up

Open TvHop from the TV's app list. Three steps on one screen:

1. **Pick your app** — Nuvio or Stremio.
2. **Turn on the service** — opens Accessibility settings; enable TvHop there.
3. **Run test** — resolves a known title and opens it in your chosen app, so
   you can confirm everything works without hunting for a card first.

### If the Accessibility toggle switches itself back off

On Android 13+ the system blocks sideloaded apps from enabling Accessibility
by default. Go to **Settings → Apps → TvHop**, find **Allow restricted
setting**, enable it, then turn the service on again.

If your device has no such option (the Google TV Streamer is one), use ADB:

```bash
adb shell settings put secure enabled_accessibility_services \
  com.owentariq.tvhop/com.owentariq.tvhop.CardClickAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### If the service stops working after a while

Some manufacturers kill background services aggressively. Exclude TvHop from
any battery optimiser or "RAM cleaner", and on TCL boxes:

```bash
adb shell appops set com.owentariq.tvhop APP_AUTO_START allow
adb shell appops set com.owentariq.tvhop APP_ASSOC_START allow
```

### Nothing opens when you click a card

Check the logs over ADB:

```bash
adb logcat -s TvHopService:D MetaResolver:D TargetLauncher:D
```

- No log line at all → the click isn't being seen; the service is off or the
  launcher isn't one of the supported ones.
- `No match for "..."` → the title couldn't be resolved. Rare, but a card with
  a heavily decorated label can do it.
- Something logged but nothing opens → the target app isn't installed, or its
  build doesn't accept the hand-off.

## How it works

| Piece | Detail |
|---|---|
| Detection | An `AccessibilityService` scoped to the launcher packages only, listening for `TYPE_VIEW_CLICKED` and nothing else |
| Identification | [Cinemeta](https://v3-cinemeta.strem.io), Stremio's public metadata addon — no API key, returns IMDb ids directly |
| Stremio hand-off | `stremio:///detail/{type}/{id}`, its documented URL scheme |
| Nuvio hand-off | Nuvio publishes no URL scheme, so TvHop starts its exported `MainActivity` with the `contentId` / `contentType` extras it already uses for its own "Continue watching" channel |

### Privacy

The accessibility service is restricted in `accessibility_service_config.xml`
to the launcher packages and to click events. It cannot see what you type or
what you do in any other app. The only thing that leaves the device is the
title of a card you clicked, sent to Cinemeta to look up. There is no
analytics, no account and no server of ours.

## Building

The Gradle wrapper jar isn't committed, so use a local Gradle 8.11+:

```bash
gradle assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

A GitHub Actions workflow is included to build, sign, and publish the APK. Every push to `main` refreshes the `latest` release with a signed APK.

## Legal

TvHop is an independent navigation tool. It does not host, store, distribute
or provide movies, series, streams, torrents or any other audiovisual
content, and has no control over what any other app on your device can play.
What you watch in Nuvio or Stremio, and whether that is lawful where you are,
is between you and those apps.

Not affiliated with, sponsored by or endorsed by Google, Nuvio or Stremio.
All trademarks belong to their respective owners.

Metadata comes from Cinemeta. TvHop is not endorsed or certified by Stremio.
