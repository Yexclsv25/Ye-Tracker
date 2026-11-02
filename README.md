# Ye Tracker Mobile

An unofficial native Android client for [yetracker.cc](https://yetracker.cc/) with an Apple Music-inspired
interface. Browse and stream the unreleased and released catalog from your phone.

> **Disclaimer:** This is a fan-made app. It is not affiliated with or endorsed by yetracker.cc, Ye, or any
> label. All music and metadata come from the tracker; the app only reads what the site makes available.

## Features

- **Listen Now** – shuffle the whole tracker (endless), recents, latest leaks, best of, released picks, eras
- **Eras** – every era, switchable between **Unreleased** and **Released**
- **Library** – Wrapped, downloads, favorites, recently played, local playlists, every other tracker tab
- **Search** with filters (OG File, Full, Lossless, Best, Grails, Released)
- **Full-screen player** + floating mini player, queue, shuffle, repeat, playback speed (optional pitch shift)
- **Media notification** with shuffle / previous / play / next / favorite; tapping it opens the player
- **Downloads** (also copied to `Music/Ye Tracker`), offline playback of downloaded songs
- **Wrapped** – minutes listened, total plays, top songs and top eras
- **Liquid Glass** UI option (live blurred translucent bars), accent colors, sleep timer
- **Deep links** – `yetracker.cc` song links open and play inside the app
- **Discord "Listening to" status** through the official Discord Social SDK (optional)
- **Offline screen** and a built-in **self-updater**

## How it gets its data

The app talks to the tracker's MCP endpoint, `https://yetracker.cc/mcp` (`list_eras`, `search_entries`,
`get_entry`, `random_entries`, `recent_leaks`). The server returns at most 50 entries per call, so large
eras are loaded in pieces and fill in progressively. Album covers are matched to official releases through
the iTunes Search API; other eras use generated gradient art.

## Build

Requirements: Android Studio (or AndroidIDE), JDK 17, Android SDK 36.

```bash
./gradlew assembleDebug
```

Kotlin + plain Android Views (no Compose), Media3 for playback. Min SDK 27, target SDK 34.

## Discord status (optional)

Uses the official Discord Social SDK (Application ID `1566609960685535273`). Each user links their own
Discord account in **Settings → Discord status**.

1. Developer Portal → your app → enable the **Social SDK**.
2. OAuth2 → add the redirect `discord-1566609960685535273:/authorize/callback`.
3. Enable **Public Client** (the app has no backend to hold a client secret).
4. Name the application "Ye Tracker" (it shows as "Listening to Ye Tracker").
5. Download the Android C++ SDK from the portal and copy `discord_partner_sdk.aar` to `app/libs/`.
   The AAR is git-ignored because it is Discord's file; every builder downloads it themselves.
6. Rebuild. Without the AAR the project builds normally and the option says "SDK not installed".

## Releases and the auto-updater

The app reads the newest [GitHub Release](https://github.com/Yexclsv25/Ye-Tracker/releases) of this repo
(at most every 12 hours at startup, and from **Settings → Check for updates**). If the release tag is higher
than the installed `versionName`, it shows the release notes and offers to download and install the attached
`.apk`. Nothing needs to be configured in the code.

To publish an update:

1. In `app/build.gradle` raise `versionCode` and set `versionName` (e.g. `'1.1'`).
2. Build and sign the APK with the **same keystore every time** (Android refuses updates signed with a
   different key).
3. On GitHub: **Releases → Draft a new release**, create the tag `v1.1` (it must match `versionName`),
   write the notes (they appear in the update dialog) and attach the `.apk`. Publish it (not as a draft or
   pre-release).

Users allow **Install unknown apps** for Ye Tracker once. If GitHub provides a checksum for the attached
file, the app verifies it before installing.

## Project layout

```
app/src/main/kotlin/cc/yetracker/mobile/
  MainActivity.kt   shell, navigation, playback control, player overlay, settings, links, updater UI
  Pages.kt          Listen Now, Eras, Library, Search, song lists, Wrapped
  Data.kt           models, MCP client, tracker queries, local storage (favorites, playlists, stats)
  Cover.kt          album-art lookup and generated artwork
  Downloader.kt     song downloads
  Glass.kt          liquid-glass drawing
  Discord.kt        Discord status bridge (JNI)
  Updater.kt        GitHub Releases check + APK download
  Ui.kt             playback service, theme colors, view helpers
app/src/main/cpp/   Discord JNI bridge (built only when the Discord AAR is present)
```

## Deep links to the app

`https://yetracker.cc/?...&id=...` links open in the app. Android opens them directly (without asking) only if
the website owner publishes an `assetlinks.json` for this app; otherwise users pick "Ye Tracker" from the
"Open with" prompt or add the link under **Settings → Apps → Ye Tracker → Open by default**.

## License

[MIT](LICENSE)
