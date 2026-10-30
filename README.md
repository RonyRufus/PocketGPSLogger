# Pocket GPS Logger

A minimal, battery-conscious **native Android** app that saves your current GPS position when
you speak a chosen trigger phrase (default **“save spot”**). It is designed for the phone to sit
in a pocket with the screen off while you wear an earphone — for example while riding a
two-wheeler — and it never needs a network connection to recognise the phrase or to save a spot.

The app is written in Kotlin with Jetpack Compose, uses **Vosk** for offline speech recognition,
**Room/SQLite** for storage, a **microphone foreground service** for always-on listening, and
**Fused Location Provider** (with a platform `LocationManager` fallback) for GPS.

---

## 1. What it does (v1 scope)

* Guided **Setup**: permissions, offline model install, trigger-phrase test.
* **Start / Stop Hands-Free Mode** from the app, plus a notification action to stop.
* Continuous **offline** trigger-phrase recognition with a constrained grammar.
* On a valid trigger: capture the current location, **persist it to Room**, then play a short
  audible confirmation. Audio is never the only evidence of a save.
* **Saved Locations** list with timestamp, coordinates, accuracy and source; tap to open in a
  map app; delete one or clear all (with confirmation).
* **Settings**: trigger phrase, confirmation mode (spoken / tone / silent), GPS timeout,
  cached-location policy and age, accuracy threshold, duplicate cooldown, model status and
  storage usage.
* Everything is local. No accounts, cloud, analytics or recordings.

Out of scope for v1 (deliberately): voice notes, dictation, AI summaries, reminders, cloud sync,
dashboards, speaker identification.

---

## 2. Project layout

```
PocketGPSLogger/
├── app/
│   ├── build.gradle.kts              # app module, R8/resource shrinking, arm64 ABI
│   ├── proguard-rules.pro            # Vosk/JNA/Room keep rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # permissions + microphone FGS
│       │   ├── java/com/pocketgps/logger/
│       │   │   ├── App.kt            # Application, ServiceLocator init
│       │   │   ├── ServiceLocator.kt # process-wide singletons
│       │   │   ├── audio/            # AudioRecord, Vosk, matching, cues, route monitor
│       │   │   ├── data/             # Room entity, DAO, database
│       │   │   ├── engine/           # Listener state machine + UI state
│       │   │   ├── location/         # LocationAcquirer (Fused + fallback), repository
│       │   │   ├── model/            # Vosk model import/download/verify/load
│       │   │   ├── service/          # HandsFreeService (microphone FGS)
│       │   │   ├── settings/         # DataStore-backed AppSettings
│       │   │   └── ui/               # Compose: Setup, Main, Saved, Settings, theme
│       │   └── res/                  # strings, theme, icons
│       └── test/                     # pure-JVM unit tests
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/                   # Gradle 8.7
└── gradle.properties
```

### Component responsibilities

| Package | Responsibility |
| --- | --- |
| `audio` | `MicAudioSource` (16 kHz mono `AudioRecord`), `VoskKeywordSpotter`, `RecognitionPipeline` (persistent, allocation-free hot loop, dedupe/cooldown), `TriggerMatcher` (whole-phrase validation), `GrammarJson`, `ConfirmationPlayer` (TTS/tone/silent), `AudioRouteMonitor`, `PhraseTestRunner`. |
| `engine` | `ListenerEngine` state machine; receives triggers, runs GPS + Room work off the audio thread, publishes `ListenerUiState`. |
| `location` | `LocationAcquirer` (cached → fresh → fallback with timeout), `LocationRepository`, `LocationSource`. |
| `data` | Room `LocationRecord`, `LocationDao`, `LocationDatabase` (versioned, migration-ready). |
| `model` | `ModelManager`: SAF import, one-time download, structural verification, zip-slip protection, cached `Model`. |
| `settings` | `SettingsRepository` (Preferences DataStore) and `AppSettings`. |
| `service` | `HandsFreeService`: foreground microphone service, wake lock, notifications, interruption recovery, headset changes. |
| `ui` | Compose screens and view models. |

---

## 3. Requirements

* Android **8.0 (API 26)** or newer; targets **API 34**.
* A physical **ARM64** Android phone is the intended target. **Debug** builds package every ABI
  from the native AARs, so they also run on an x86_64 emulator. **Release** builds are restricted
  to `arm64-v8a` (see §6).
* No Google Play Services? The app falls back to `LocationManager` for last-known fixes.
* No internet is required after the speech model is installed.

---

## 4. Speech model installation

The Vosk model is **not** included in the APK. Install it once in either of two ways:

1. **Import a local `.zip`** (fully offline). In Setup, tap **Import .zip** and pick a Vosk model
   archive such as `vosk-model-small-en-us-0.15.zip`.
2. **Download once**. Tap **Download model** in Setup; the app fetches the official small English
   model (`https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`). This is the
   only network operation in the app.

The archive is extracted into app-private storage (`filesDir/model`), checked for the required
`am/final.mdl`, `conf/mfcc.conf` and a graph file, and then loaded. Imports are protected against
zip-slip. You can delete the model from Settings at any time. Importing, downloading or deleting
the model is disabled while hands-free listening is active, because replacing the native model
under a live recognizer would crash the process.

`vosk-model-small-en-us-0.15` is ~40 MB extracted and supports runtime grammar constraints, which
the app uses to keep recognition focused on the trigger words plus a small decoy set.

---

## 5. Permissions

Requested progressively from Setup / Main:

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Offline recognition. Required for the microphone foreground service. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Saving the spot. |
| `POST_NOTIFICATIONS` (Android 13+) | The persistent listening notification. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Declared for the microphone service. |
| `INTERNET` | Only for the optional one-time model download. |

The app does **not** request background-location, overlay, accessibility, or device-admin
permissions, and never asks you to disable security features.

---

## 6. Building

### Debug APK

```bash
# from the PocketGPSLogger directory
./gradlew :app:assembleDebug
# Windows
gradlew.bat :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Debug builds include all packaged ABIs from the AARs so they run on emulators as well.

### Release APK (minified + resource-shrunk)

Create a signing config (or use the debug keystore for a local smoke test) and run:

```bash
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk` (or `-signed.apk` once a signing
config is present). Measured sizes and the exact build/test status are in
[`BUILD_REPORT.md`](BUILD_REPORT.md).

Release builds enable:

* R8 minification (`isMinifyEnabled = true`)
* resource shrinking (`isShrinkResources = true`)
* `arm64-v8a` only (see `abiFilters` in the `release` block of `app/build.gradle.kts`)

To test the release build on an x86_64 emulator, add `"x86_64"` to the release `abiFilters`. To
support 32-bit ARM phones, add `"armeabi-v7a"`. Debug builds are not restricted, so they install
on emulators without changes.

### Signing (example)

Add to `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/pocketgps.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release") }
    }
}
```

Never commit a keystore or passwords.

### Build without Android Studio (GitHub Codespaces / cloud VM)

The project is a plain Gradle project and does not need the Android Studio IDE.

1. Create a Codespace (or any Linux VM) with **JDK 17**.
2. Install the Android command-line SDK and accept licences:

   ```bash
   mkdir -p "$HOME/android-sdk/cmdline-tools"
   cd "$HOME/android-sdk/cmdline-tools"
   curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
   unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
   yes | "$HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager" --licenses
   "$HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager" \
       "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

3. Point the project at the SDK (either export `ANDROID_HOME` or create `local.properties`):

   ```bash
   echo "sdk.dir=$HOME/android-sdk" > local.properties
   ```

4. Build:

   ```bash
   ./gradlew :app:assembleDebug :app:testDebugUnitTest
   ./gradlew :app:assembleRelease
   ```

5. Download the APK artifacts from the Codespace file explorer or `gh codespace cp`.

`local.properties` is machine-specific and should not be committed.

---

## 7. How recognition works

* `AudioRecord` captures 16 kHz mono 16-bit PCM with `VOICE_RECOGNITION`, read into a
  pre-allocated buffer (no per-chunk allocations).
* A single Vosk `Recognizer` is created once per session with a grammar built from the trigger
  words plus decoys (`no`, `wait`, `one`, `two`, `uh`, `ah`, `hm`, `okay`, `yes`, `[unk]`). It is
  reused for the whole session — it is **not** restarted after every utterance.
* Only **final** results are considered. `TriggerMatcher` normalises case/punctuation and requires
  the trigger as a **contiguous run of whole words**; a single matching word never fires.
* Final words are kept in a short rolling buffer so a phrase split across two Vosk segments still
  matches.
* A post-trigger **cooldown** (default 3 s) plus a one-capture-at-a-time guard prevents one
  utterance from creating multiple records.
* While a confirmation cue plays, real audio is still drained but digital silence is fed to the
  recognizer, so the app never transcribes itself.

### Trigger phrase validation

* At least two words.
* The complete phrase must be spoken.
* Empty, partial, malformed or unrelated results are rejected.
* Live changes to the phrase rebuild the grammar and update the running pipeline.

---

## 8. Location behaviour

On a trigger, `LocationAcquirer` runs on an IO coroutine (never the audio thread):

1. If the policy is *prefer recent* and a last-known fix is within `cachedMaxAgeSeconds` and
   within the accuracy threshold, use it as **cached**.
2. Otherwise request a **fresh** fix (Fused `Priority.PRIORITY_HIGH_ACCURACY`, or platform
   `getCurrentLocation` / `requestSingleUpdate`) bounded by `gpsTimeoutSeconds`.
3. If the fresh fix is missing or unusably inaccurate, use a last-known fix only if it still meets the configured age and accuracy limits; mark it as **fallback**.
4. If the only available fix is too old or too inaccurate, report **Location unavailable** and play the failure cue — no misleading coordinate is saved as a successful current location. Fix age is evaluated when the fix is selected, not when the trigger was first detected.

Stored per record: id, latitude, longitude, trigger time, fix time, accuracy (nullable), source
(`fresh`/`cached`/`fallback`), fix age (nullable), recognised phrase, save status, optional notes
(warnings), reserved `notes` field.

`triggerTimestamp` is captured the instant the phrase is recognised; `locationTimestamp` is the
fix's own time. The record is only announced as saved **after** `Room.insert` returns an id.

---

## 9. Background / screen-off operation and limitations

The service:

* declares `foregroundServiceType="microphone"` and `FOREGROUND_SERVICE_MICROPHONE`;
* is started from a visible user interaction (Start button) as required by Android 12+;
* posts a persistent, silent, low-importance notification with a **Stop listening** action;
* holds a bounded `PARTIAL_WAKE_LOCK` (60 min, renewed) so the recognizer keeps running with the
  screen off;
* recovers from phone calls / audio-focus loss (detected via `AudioManager.mode`), muted-microphone
  privacy toggles, and transient read errors, with bounded retries;
* handles headset connect/disconnect events and reports the **actual** input route rather than
  assuming the earphone mic is used; if an earphone that was in use disappears, confirmation cues
  are muted so the phone speaker does not announce saves from a pocket. Without any earphone the
  configured cue plays through the speaker;
* returns `START_NOT_STICKY` and does **not** auto-restart after process death or reboot. Android
  restricts starting a microphone foreground service from the background; pretending otherwise
  would be dishonest. If the system kills the service, open the app and start listening again.

### Battery-optimisation notes

* No high-frequency GPS polling: location is requested only on a trigger.
* No busy loops: the audio loop blocks on `AudioRecord.read`.
* The wake lock is held only while listening and released on stop.
* On aggressive OEM skins (Xiaomi/MIUI, Samsung, Oppo, Vivo, Huawei), consider:
  * allowing the app to run in the background / “no restrictions”;
  * disabling battery optimisation for this app;
  * locking the app in the recents screen if the OEM requires it.
  These are optional user settings; the app itself never asks you to disable security features.

---

## 10. Testing

### Unit tests (run without a device)

```bash
./gradlew :app:testDebugUnitTest
```

Covered:

* `TriggerMatcherTest` — full-phrase matching, case/punctuation, partial and unrelated rejection,
  reversed/non-contiguous phrases.
* `GrammarJsonTest` — grammar includes trigger words + `[unk]`, deduplicates.
* `VoskResultParserTest` — final/partial JSON parsing and malformed input.
* `RecognitionPipelineTest` — trigger fires once, unrelated/single words ignored, phrase split
  across segments, cooldown suppresses duplicates, new utterance after cooldown fires.
* `LocationSourceTest` — stable wire values.

Last verified run: 33 tests, 0 failures (`./gradlew :app:testDebugUnitTest`). Full measured
build/test status is in [`BUILD_REPORT.md`](BUILD_REPORT.md).

### Manual acceptance checklist (physical device)

These must be run on the target phone; they are **not** claimed as passed here unless executed.

1. Valid trigger phrase saves exactly one location.
2. An unrelated sentence saves nothing.
3. Repeating the phrase does not create accidental duplicates.
4. Listening continues with the screen locked.
5. A spot saves with the screen off and an earphone connected.
6. Confirmation plays only after the database commit.
7. Poor GPS produces an explicit warning/stale note rather than a misleading current location.
8. The app works in airplane mode after the model is installed.
9. Listening stops immediately from the app and from the notification action.
10. Microphone/AudioRecord and model resources are released on stop.
11. Phone-call interruption recovers when the call ends (where Android permits).
12. Release APK retains all features after R8/resource shrinking.
13. Prolonged screen-off run: measure CPU, RAM, battery drain, false triggers and missed commands
    (e.g. `adb shell dumpsys batterystats`, `adb shell top`, Android Studio Profiler).

---

## 11. Dependency and size audit

Runtime dependencies and why each exists:

| Dependency | Purpose |
| --- | --- |
| `kotlin-stdlib`, `kotlinx-coroutines-android` | Language/runtime and coroutines. |
| `androidx.core:core-ktx` | `ContextCompat`, `ServiceCompat`, notification compat. |
| `androidx.appcompat` | Compat base used by the platform theme/activity stack. |
| `androidx.lifecycle:*` | ViewModels, `collectAsStateWithLifecycle`, service lifecycle. |
| `androidx.compose.*` (BOM 2024.05.00) | UI. |
| `androidx.activity:activity-compose` | `ComponentActivity`, `enableEdgeToEdge`, result contracts. |
| `androidx.navigation:navigation-compose` | Four-screen navigation. |
| `androidx.room:room-runtime`, `room-ktx`, KSP compiler | Local SQLite storage. |
| `androidx.datastore:datastore-preferences` | Settings persistence. |
| `com.alphacephei:vosk-android` | Offline recognizer + native libs. |
| `net.java.dev.jna:jna@aar` | Native bridge required by Vosk. |
| `com.google.code.gson:gson` | Vosk JSON parsing (pure JVM). |
| `com.google.android.gms:play-services-location` | Fused Location Provider (location module only). |

Native payload: Vosk + JNA ship `.so` files per ABI. Release is restricted to `arm64-v8a`.

APK sizes: see `BUILD_REPORT.md` (debug and minified release, measured on this machine).

---

## 12. Privacy

* No audio is stored or transmitted.
* No coordinates leave the device.
* No accounts, analytics or telemetry.
* The only network request is the optional one-time model download.

---

## 13. Known limitations

* **Reboot / process death:** listening is not auto-restarted. Android forbids silently starting a
  microphone foreground service from the background, so the user must start it again.
* **Grammar support:** the small Vosk English model supports runtime grammars. Larger models that
  require a dynamic graph may behave differently; the bundled defaults target the small model.
* **Trigger language:** English only for v1.
* **TTS latency:** the first spoken confirmation after app start may be slightly delayed while the
  TTS engine initialises; the tone mode is offered for lower latency.
* **Headset microphone:** Android chooses the input route. The app reports the actual route but
  cannot force the earphone mic to be used.
* **Accuracy:** reported accuracy is whatever the provider supplies; indoors it may be poor, and
  those records are flagged rather than hidden.
* **OEM background killing:** some vendors kill foreground services aggressively; see §9.
