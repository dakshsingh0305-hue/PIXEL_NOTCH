# PIXEL_NOTCH

> **A Dynamic Island–style overlay for Android, built natively in Kotlin.**
> Music pop-ups with real playback controls · charging ring · timer countdowns · notification previews · four visualizer styles.

![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84)
![Language](https://img.shields.io/badge/Kotlin-JetBrains-7F52FF)
![Build System](https://img.shields.io/badge/Gradle-9.6.0%20%2F%20AGP%209.5.0-02303A)
![Target SDK](https://img.shields.io/badge/targetSdk-37-blue)

PIXEL_NOTCH recreates the feel of Apple's Dynamic Island on Android — originally built for a **Google Pixel 10a** and designed around the Pixel's camera cutout. A small spring-animated pill floats above every app at the top of the screen:

- **Idle** → a small camera-dot-shaped pill tucked under the cutout
- **Music playing** → grows into a compact player; tap to expand into a full card with artwork, track info, and play/pause · previous · next
- **Track change** → a "NOW PLAYING" pop-up slides in
- **Charging** → pop-up on plug/unplug, then a persistent ring with the battery percentage while idle
- **Timer** → live countdown in the pill; completion pop-up with vibration and a notification
- **Notifications** → discreet previews (calls elevated to the highest priority)

Pop-ups follow a priority system (call > timer done > charging > track change > notification), debounce incoherent bursts, queue instead of stacking chaotically, and always restore whatever was underneath when they expire.

> ⚠️ This app does **not** modify or replace the system status bar. It uses Android's standard `TYPE_APPLICATION_OVERLAY` window API and sits *alongside* your system UI. No root, no accessibility-service tricks.

---

## The visualizer — honest by design

The equalizer gets its bars from one of two sources, and the app always tells you which one is live:

| Source | How it works |
|---|---|
| **Audio-reactive** (best-effort) | `android.media.audiofx.Visualizer` on the output mix → FFT → 24 log-spaced bands with per-band auto-gain. Real capture support varies by device/ROM. |
| **Simulated** (automatic fallback) | A smooth generator (bass-weighted envelopes + beat-like pulses) so the pill still feels alive. Never claims to be real audio analysis. |

The main-screen card labels the truth: **"Audio-reactive"** / **"Simulated visualizer"** / **"Idle"**. If the device or ROM denies output-mix capture (any exception, or `enabled === false`), the engine falls back automatically — no fake meters anywhere.

All four styles work in either mode:

| Style | Look |
|---|---|
| **Bars** | Classic gradient equalizer bars |
| **Wave** | Smooth filled curve with a center line |
| **Bouncy** | Squash-and-stretch rounded bars |
| **Minimal** | Breathing blob/ring pulse |

---

## Features

### Island
- Spring-driven expand/collapse (`androidx.dynamicanimation`: stiffness/damping tuned springs on width, height, and content crossfade)
- Fully **Canvas-drawn**: idle pill, compact music pill, expanded music card with drawn transport buttons, charging ring cards, notification/call cards with a clipped app icon, timer rings, "NOW PLAYING" chip
- Tap-through *outside* the pill (`WRAP_CONTENT` overlay window); tap the pill to expand, tap again to collapse or dispatch a media control
- Cutout-aware top offset via real `WindowInsets.Type.statusBars()` (API 30+), legacy status-bar-height fallback below that
- Pop-up priority engine: **CALL 6 > TIMER_DONE 5 > CHARGING 4 > TRACK_CHANGE 3 > NOTIFICATION 2**, 1.2 s debounce, 2-slot queue, state restoration on expiry

### Music
- Media sessions discovered through `NotificationListenerService` + `MediaSessionManager` active-session watching
- Live title / artist / album / artwork (downscaled to ≤512 px)
- Transport commands are sent **only** when the session advertises the capability — no fake success

### Events
- **Charging**: broadcast-driven (no polling); plug/unplug pop-ups + persistent ring while charging
- **Timers**: 1 / 3 / 5 / 10-minute in-app countdowns; completion pop-up + `VibratorManager` vibration (API 31+) + a real notification
- **Notification previews** with smart filtering: skips our own package, ongoing notifications, group summaries, blank/redacted content, and update spam (0.8 s per-key debounce)

### App UI
- Main screen with a **live preview of the island** running the same state machine as the overlay
- Three permission cards with real-time status and deep links to the right settings pages
- Settings screen: pop-up duration (2–10 s), visualizer style, "prefer audio-reactive" opt-in, charging/notifications/timers toggles, theme (System/Dark/Light, applied live), motion intensity (Full/Reduced/Off — also respects the system's `ANIMATOR_DURATION_SCALE`), reset-to-defaults
- Dark-first Material 3 theme with a light variant

---

## Permissions — and why

| Permission | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the island above other apps (standard overlay API) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the overlay alive; declared with `foregroundServiceType="specialUse"` and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "persistent_island_overlay_display"` for targetSdk 37 compliance |
| `POST_NOTIFICATIONS` (runtime) | Low-importance persistent service notification + timer completion notifications |
| Notification listener access | Powers media-session detection and notification previews. Granted by you in Settings; revocable at any time |
| `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS` | Only requested if you opt into audio-reactive mode (for `Visualizer(0)`); not required for anything else |
| `VIBRATE` | Timer completion buzz |

The notification-listener service is bound by the system (`BIND_NOTIFICATION_LISTENER_SERVICE`), exported intentionally per platform contract, and started only through the system's listener plumbing — the overlay service itself is only ever started from the app's own foreground UI (special-use foreground services don't assume background-start privileges; `ForegroundServiceStartNotAllowedException` is handled honestly).

---

## Build it yourself

**Prerequisites**
- Android Studio (with the SDK for API 37)
- The project pins the JVM toolchain to **Java 25** (`gradle-daemon-jvm.properties` → `toolchainVersion=25`); the Android Studio JBR satisfies it. If you hit toolchain discovery issues, ensure no bogus JREs are on the path — the repo's `gradle.properties` already restricts auto-detection.

```bat
git clone https://github.com/Dakshsingh0305/PIXEL_NOTCH.git
cd PIXEL_NOTCH

REM build the APK
.\gradlew.bat assembleDebug

REM install on a connected device
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The APK lands at:

```
app\build\outputs\apk\debug\app-debug.apk
```

**First-run setup**
1. Open the app and toggle **Island ** → grant *Display over other apps*
2. Grant **Notification access** (needed for music sessions and notification previews)
3. Optional: allow the microphone only if you want the audio-reactive visualizer mode

---

## Project structure

```
app/src/main/java/com/pixelnotch/app/
├── coordinator/   IslandCoordinator — state machine, priority pop-up engine, band fan-out
├── service/       IslandOverlayService — foreground TYPE_APPLICATION_OVERLAY window
├── ui/            IslandPillView (Canvas island), EqualizerView, VisualizerRenderer (4 styles), SettingsActivity
├── media/         PixelNotchNotificationListener, MediaSessionTracker
├── audio/         VisualizerEngine — real FFT (best-effort) + simulated fallback, single-slot arbitration
├── battery/       BatteryStateReceiver
├── timer/         TimerController
├── settings/      PixelNotchSettings — SharedPreferences-backed, listener-broadcasting
└── (PixelNotchApp.kt, MainActivity.kt)
```

Everything is native Android (Kotlin + Views + Material 3 + `androidx.dynamicanimation`) — no unnecessary third-party libraries.

---

## Status

- ✅ `assembleDebug` builds clean (`BUILD SUCCESSFUL`)
- ✅ Unit tests pass — **1 test, 0 failures** (`ExampleUnitTest`, the template's suite)
- ⏳ On-device validation on a Pixel 10a (overlay placement, tap-through, media detection, real vs. simulated visualizer behavior) — **not yet claimed as passing**; please file an issue if you test it

## Disclaimer

This is an independent, educational hobby project. It is **not affiliated with, endorsed by, or derived from Apple or Google**. "Dynamic Island" is a trademark of Apple Inc.; Android and Pixel are trademarks of Google LLC. The app does not fake elements Android doesn't allow and asks for the minimum permissions its features need.
