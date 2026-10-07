# Sennachie for Plex

Sennachie for Plex is a simple Android player for personal Plex music and audiobook libraries.

It is designed around two focused experiences:

- **Music** — albums, artists, favourites, playlists, downloads and background playback.
- **Audiobooks** — chapter navigation, resume positions, playback speed, sleep timer, Listen Again and offline listening.

It is an independent project and is not affiliated with or endorsed by Plex, Inc.

## Status

Sennachie is in active personal testing and has only been tested on the author's GrapheneOS Pixel. It is offered as-is, with no guarantee that it will work on your device, Plex server, network, or Sonos setup.

It is shared because it solves a few frustrating personal pain points: a focused audiobook player with an easy skip-back flow, and a music player that can keep an album playing on repeat.

Releases are signed Android APKs for people comfortable installing apps outside Google Play. The original `0.7.1-preview` build used Android's debug key; anyone who installed it must uninstall it once before installing the first signed release.

## What it does

- Sign in through Plex's own device-link page, then choose a server and your music and/or audiobook libraries.
- Play music and audiobooks in the background, with artwork and controls in Android's notification area.
- Save albums and audiobooks for offline listening.
- Keep audiobook progress on the phone and safely compare or exchange it with Plex.
- Browse Music by album, artist, five-star Plex favourite, or Plex playlist.
- Send the current track to a reachable Sonos speaker on your local network or home VPN.

## What it does not do

- It does not host, provide, or modify media files.
- It does not replace Plex Media Server or the official Plex apps.
- Sonos handoff plays one stream at a time; remote Sonos queue and transport control are not yet included.
- Offline downloads stay on the phone and cannot be sent to Sonos.

## Install from GitHub

To install a release:

1. Open the [Releases](../../releases) page on your Android phone.
2. Download the APK attached to the latest release.
3. Open it and allow your browser or file manager to install apps from that source when Android asks.
4. Open **Sennachie for Plex** and follow the short setup wizard: choose Music, Audiobooks, or both; link Plex; select a server; then select the relevant libraries.

Android may warn that the app did not come from Google Play. Only install APKs from this repository's official releases page, and check the release notes and checksum before installing.

## Privacy

Sennachie connects directly from your phone to the Plex server and Sonos speakers you choose. Plex account and server tokens are kept in Android's encrypted storage. Listening history, metadata cache and downloads remain on your phone.

The public build contains no analytics, advertising, household directory, or external account service. A complete privacy notice will be published before the first public binary release.

## Building from source

You need Android SDK Platform 36 and Java 17.

```bash
cp local.properties.example local.properties
./gradlew :app:assembleDebug
```

The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`.

For checks used by the project:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## Feedback and support

Please use [GitHub Issues](../../issues) for bugs and feature ideas. When reporting a problem, include the Android version, Sennachie version, whether you were on a home network or VPN, and steps to reproduce it. Do not include Plex tokens, server URLs, or stream links.

## Licence

Sennachie for Plex is free software under the [GNU General Public License v3.0 or later](LICENSE). You may use, study, share and modify it; distributed modified versions must remain under the same licence.
