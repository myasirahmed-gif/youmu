# YouMu for Android 0.4.0

Native Kotlin music player for Android 8.0+ (API 26). Package: `com.youmu.app`.

### 0.4.0 player and library update

- Three saved themes: Midnight/Sage, Ocean/Ice blue and AMOLED/Lavender. Select Settings -> Theme.
- Redesigned Now Playing with a display picker, a dedicated tools row, queue and timeline. Tap the artwork or use Settings -> Artwork & audio visualizations.
- Two real audio visualizations: Spectrum (FFT frequency bars) and Orbit Wave (PCM waveform). Media3's tee audio processor passes decoded samples through unchanged. No microphone permission is used. FFT updates and visualization drawing stop when the UI is hidden; music continues.
- Normal, Shuffle, Repeat all and Repeat one use native Media3 modes, persist between sessions and remain available while the screen is locked.
- Download the current song to public `Music/YouMu`. Android's DownloadManager continues transfers after the UI closes. Downloads shows progress/status, retry and deletion; completed downloads play offline and are preferred when playing any playlist. Android 8–9 requests storage access only when Download is tapped. Files retain their source format, such as M4A or WebM; no MP3 conversion is claimed.
- Create, rename and delete local playlists, add the current song, inspect songs and remove entries. Local playlist membership stays separate from the original YouTube playlist. Membership alone does not download a song.

The existing library format is preserved; updating retains imported playlists, favorites and recent plays. Install over the previous APK using the same signing certificate.

### Loading reliability

Playback starts from the first available playlist page while later pages load in the background. Service connection, playlist startup and audio preparation have bounded waits. Failures survive service destruction and show an explicit Retry control. Player options -> Copy diagnostics provides Android version, phase and exception class names without request URLs, cookies or tokens. Regression tests cover SDK 26 (Android 8) and SDK 35; the reported phone itself was not connected, so its precise underlying failure has not been reproduced.

## Playback

- Now Playing with song artwork or live visualization, title, channel, seek bar, queue and native playback controls. No YouTube iframe or WebView is created.
- Native Media3 ExoPlayer hosted in `PlaybackService`, a foreground `MediaSessionService`.
- Playback continues when the screen locks, the activity goes into the background, or the player UI is closed. Closing the UI releases its controller, not the audio engine.
- Media notification and lock-screen controls are provided by Media3. Audio focus, headset unplug handling, CPU/network wake locks and explicit Stop are implemented.
- Tapping the media notification reconnects to the existing session. The library provides a Now Playing shortcut.
- NewPipeExtractor resolves public audio streams on demand. Only audio is loaded, with a short-lived in-memory URL cache and one refresh retry for HTTP stream failures.

This playback path uses NewPipeExtractor rather than the official YouTube IFrame API. Availability depends on upstream extraction support, region, network and YouTube changes. Videos requiring login, payment or age verification are not supported. No Google OAuth token, account cookie, or account password is passed to the extractor. Public playlist loading and a real public audio media response are covered by opt-in network tests; real-device screen-off playback still needs verification.

## Library and Google connection

Import public/unlisted YouTube or YouTube Music playlist links, or use Android Share -> YouMu. The app stores playlists, favorites and recent plays on the device.

Google authorization imports owned playlists using read-only YouTube access. While connected, account playlist track lists are fetched through YouTube Data API, so a private playlist containing publicly accessible videos can be used. Private videos themselves are not available through the public-stream resolver. Reconnect Google after the app process restarts or authorization expires.

To enable Google connection:

1. Create/select your Google Cloud project and enable **YouTube Data API v3**.
2. Configure the OAuth consent screen, branding, privacy policy and terms. Add `https://www.googleapis.com/auth/youtube.readonly` and your test accounts.
3. Create an **Android** OAuth client using package **com.youmu.app** and the signing **SHA-1** in `DEBUG-SIGNING.txt`, or obtain it with `gradlew.bat signingReport`.
4. Install this APK on a Google Play services device. Tap **Connect YouTube**, grant read permission and select playlists.
5. Register the release/Google Play certificate separately for a release build. No Cloud client configuration is included in the repository.

Disconnecting removes account imports, stops an active account-playlist session, clears the cached access token, and provides Google's connections page for revoking persistent consent. Clear Local Library also stops playback. Access tokens stay in memory. App backup is disabled. Streaming URLs are not written to the library or source.

## Build and install

Open this folder in Android Studio and run on your phone, or:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat assembleDebug testDebugUnitTest
```

Configure your SDK in the ignored `local.properties` if Android Studio has not done so. APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with Android's package installer or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

This is a signed debug APK for testing. It updates the earlier YouMu APKs using the same debug signing certificate and retains your library. It is not a Play Store release.

## Checks

`testDebugUnitTest` covers link validation, native import/favorites/share flows, local storage, native controls and timeline, queue selection, no WebView in the player, UI background/destroy behavior, foreground media service permissions, and actual media session creation/destruction using Robolectric. New checks cover local playlist creation/deduplication/removal, theme persistence and library compatibility, native repeat/shuffle restoration, live display selection, known-frequency PCM analysis and byte-for-byte audio passthrough, DownloadManager destination/format/retry/deduplication, Android 8 storage permission gating, and completed/missing download files through a local content provider. Network checks are skipped by default.

Optional native Canvas layout previews: `gradlew.bat testDebugUnitTest -PpreviewUi=true --tests '*PlayerPreviewTest'`. PNG renders use synthetic audio samples and sample metadata, and are written under `app/build/previews`; they are layout checks, not device screenshots.

Opt-in live public-source checks:

```powershell
.\gradlew.bat testDebugUnitTest -PliveAudio=true --tests '*LiveAudioTest'
```

These load a public playlist and fetch media bytes from a freshly resolved public audio stream. They do not play sound through an Android audio device.

Manual device acceptance: start a playlist, lock the screen for several minutes, use notification/lock-screen controls, unlock, leave and reopen the player, test headphones unplugging, and stop playback. Change themes, select each visualization, check pause/resume, test each playback mode, create a local playlist and add/remove songs. On Android 8, deny then grant storage permission, download a song, check its file in Music/YouMu, enable airplane mode and play Downloads. No physical device or emulator was connected during development; these device checks remain unverified.

## Source and licensing

See `THIRD_PARTY_NOTICES.md` and `COPYING`. NewPipeExtractor is GPL-3.0-or-later; corresponding app source/build files and the matching extractor source JAR accompany this build. YouMu is independent of Team NewPipe and YouTube. Download availability depends on the same public-source resolver as streaming; failed or expired downloads can be retried. Downloads that are manually removed from storage are detected when used. Clearing the library retains downloaded audio files.

References: [Media3 background playback](https://developer.android.com/media/media3/session/background-playback), [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), [Android Google authorization](https://developer.android.com/identity/authorization).
