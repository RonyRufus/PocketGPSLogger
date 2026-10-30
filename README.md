# Pocket GPS Logger

A minimal, hyper-efficient **native Android** app that saves your current GPS position when you
**double-tap your pocketed, locked phone**. No screen, no unlock, no microphone, no network —
tap twice through the fabric, feel one haptic pulse, done. Built from first principles for the
only interaction that actually works while the phone is in a pocket: **a tap**.

Kotlin + Jetpack Compose UI, a pure-Kotlin tap-pattern detector, Room/SQLite storage,
Fused Location Provider (with a platform `LocationManager` fallback), and a foreground service
that never keeps the GPS radio on.

**APK size: 1.03 MB.** The voice stack (Vosk model, microphone, 40 MB of RAM at runtime) was
removed on purpose — muffled speech through fabric does not work; taps do.

---

## 1. How it works

Start a session once (app or notification). From then on the phone listens for a
**double-tap gesture** with the screen off and locked:

* A deliberate tap through clothing is an *isolated impulse*: one short, sharp acceleration
  spike, quiet, then a second spike 50–380 ms later, then ≥ 420 ms of silence → **save**.
* Sustained vibration (walking sway, cloth rub, bike rumble) never produces two isolated
  spikes → **ignored**, not saved.
* A confirmed tap opens a 1.2 s debounce window, so one gesture can never save twice.

**Feedback is haptic**, so it works locked and pocketed:

| Signal | Meaning |
| --- | --- |
| one short pulse | spot saved |
| double pulse | save failed (no GPS fix / write error) |

Every save path — pocket double-tap, notification "Save now", in-app button — goes through one
pipeline with identical behaviour and records *what* triggered it.

---

## 2. The efficiency law (three tiers)

The service is designed around one rule: **never hold a resource you are not using**.

1. **Parked** — phone stationary for 10 min (with "listen only while moving" on):
   accelerometer listener, GPS pre-warm and wake lock are all released. A hardware
   *significant-motion* trigger sensor — which costs ~nothing — stays armed and re-arms
   everything the moment you start moving.
2. **Moving** — accelerometer at game rate + partial wake lock + low-power GPS pre-warm.
   The tap detector runs per sample with zero allocations.
3. **Saving** — one bounded high-accuracy fix request, one Room insert, one haptic pulse.
   Continuous GPS never happens.

Result: when parked the app holds no sensors, no radio and no wake lock; when moving it holds
one sensor and one wake lock; it only touches GPS for the second a save takes.

---

## 3. What you see in the app

* **Home** — Start/Stop, live state (armed / capturing / parked), taps seen, saves this
  session, last save time and message.
* **Setup** — permission checklist (location, notifications, battery-optimisation exemption)
  and a **live tap test**: tap your phone and watch the spikes and confirmed double-taps
  appear, to verify sensitivity before you pocket it.
* **Saved Locations** — every spot with time, coordinates, accuracy, source and trigger;
  open one in any map app; delete one or clear all.
* **Export** — GPX waypoints and CSV, shareable to any app.
* **Settings** — tap sensitivity (gentle / normal / firm), listen only while moving, GPS
  pre-warm, GPS timeout, accuracy threshold, haptics on/off.

Everything is local. No accounts, no cloud, no analytics, no recordings, no microphone.

---

## 4. Project layout

```
PocketGPSLogger/
├── app/
│   ├── build.gradle.kts              # R8 full mode, resource shrinking, arm64-only, no legacy packaging
│   ├── proguard-rules.pro            # Room / Compose keep rules
│   └── src/
│       ├── main/java/com/pocketgps/logger/
│       │   ├── sensor/TapDetector.kt      # pure-Kotlin double-tap state machine (no Android types)
│       │   ├── service/TapLogService.kt   # foreground service, 3-tier efficiency, notification actions
│       │   ├── engine/SavePipeline.kt     # acquire → insert → confirm, shared by all triggers
│       │   ├── engine/ServiceBus.kt       # single StateFlow the UI subscribes to
│       │   ├── feedback/HapticCue.kt      # 1 pulse = saved, 2 pulses = failed
│       │   ├── location/                  # LocationAcquirer, LocationRepository, LocationSource
│       │   ├── data/                      # Room entity/dao/database
│       │   ├── export/                    # GPX + CSV exporters
│       │   ├── settings/                  # DataStore-backed settings repository
│       │   └── ui/                        # Compose: home, setup, saved, settings
│       └── test/                          # unit tests for detector, pipeline, exporters, format
├── gradle/
└── README.md
```

---

## 5. Requirements

* Android Studio (or just the Gradle wrapper + JDK 17) and the Android SDK.
* A device with an accelerometer (every phone has one) running Android 8.0+.

---

## 6. Permissions

| Permission | Why |
| --- | --- |
| Fine / coarse location | capture the spot when you tap |
| Foreground service (location + special use) | keep tap listening alive with the screen off |
| Post notifications | the session notification with Save now / Undo / Stop |
| Vibrate | the confirmation pulses |
| Wake lock | keep the CPU listening while moving |
| Battery optimisation exemption | requested in Setup; prevents Android from killing the listener |

The `specialUse` foreground-service type is declared with subtype
`pocket_double_tap_gps_logger`: the service listens to the accelerometer so a double-tap
through a pocket saves a GPS spot while the phone is locked. No microphone, no camera, no
connectivity.

---

## 7. Building

```
./gradlew assembleRelease          # signed with the debug key, installs directly
./gradlew testDebugUnitTest        # unit tests (tap detector, pipeline, exporters)
```

The release APK lands in `app/build/outputs/apk/release/app-release.apk` (~1.03 MB).

Size discipline (how 1.03 MB is achieved): no voice stack (−9 MB of native libs alone),
R8 full mode, `resourceConfigurations += "en"`, `useLegacyPackaging = false` for resources,
single `arm64-v8a` ABI, hand-written `ImageVector` icons instead of the 11 MB icon library,
and no appcompat/material dependency (plain `ComponentActivity` + Compose).

---

## 8. Location behaviour

* **Pre-warm (optional, default on):** while moving, a low-power GPS stream keeps the receiver
  warm so a save completes in ~1 s. This is a passive listen — it never wakes the radio for you.
* **On save:** one bounded high-accuracy request. If GPS cannot deliver within the configured
  timeout, the last known fix is used *with its age recorded*, and the record is marked
  `fallback` so exports tell the truth about it.
* **Accuracy gate:** fixes above your accuracy threshold are saved but flagged
  (`Poor GPS accuracy (x m)`) — never silently dropped, never silently trusted.
* Every record stores: trigger time, fix time, fix age, accuracy, source and what triggered it.

---

## 9. Screen-off / pocket reliability — honest limits

* A **partial wake lock** keeps the accelerometer and detector alive while the session runs;
  with "listen only while moving" the parked tier drops it entirely.
* Aggressive OEM battery managers (Xiaomi/OPPO/Samsung "sleeping apps") can still kill the
  service — the Setup screen walks you through the exemption; if taps stop working, check there.
* Tap sensitivity is a trade-off: **gentle** fires on lighter taps (more false positives from
  cloth rub), **firm** needs a real knock. Test with the live tap test before a long ride.

---

## 10. Testing

`./gradlew testDebugUnitTest` — the tap-detector state machine (bounce, sustained vibration,
debounce, gap windows), the save pipeline (stale-fix warnings, accuracy gate, undo), both
exporters and the formatting helpers are all pure-JVM tested. 22 tests, all green.

---

## 11. Privacy

Position data stays on the device in a Room database you can clear with one button. Exports
happen through the standard share sheet, only to apps you pick. The app has no Internet
permission at all — it cannot phone home even if it wanted to.

---

## 12. History

The pre-2.0 versions used offline speech recognition ("save spot" through an earphone mic).
That stack — a 40 MB model held in RAM, flaky through jacket fabric — was removed in the
ground-up v2 rebuild; the old voice-era code (including the later dictation/notes experiments)
is preserved on the `archive/voice-experiments` branch.
