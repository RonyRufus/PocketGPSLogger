# Pocket GPS Logger — Build Report

Generated on the development machine used for this project (Windows 11, JDK 17, Android
SDK 34, build-tools 34.0.0). All sizes are the actual produced artefacts.

## 1. Build environment

| Item | Value |
| --- | --- |
| OS | Windows 11 (win32) |
| JDK | OpenJDK 17 (`C:/Users/HP/tools/jdk-17`) |
| Android SDK | `C:/Users/HP/tools/android-sdk` (platforms android-34, build-tools 34.0.0) |
| Gradle | 8.7 (wrapper) |
| AGP | 8.4.2 |
| Kotlin | 1.9.23 (KSP 1.9.23-1.0.19) |
| Compose BOM | 2024.05.00 (Compose compiler 1.5.11) |
| Room | 2.6.1 |
| Vosk | 0.3.47 (+ JNA 5.13.0) |

## 2. Build results

| Task | Result |
| --- | --- |
| `:app:assembleDebug` | **SUCCESS** (39 tasks, ~3 min) |
| `:app:testDebugUnitTest` | **SUCCESS** — 33 tests, 0 failures |
| `:app:assembleRelease` | **SUCCESS** (51 tasks, ~10 min) — R8 + resource shrinking |
| `:app:lintVitalRelease` | **SUCCESS** (no vital issues) |

## 3. APK sizes (measured)

| Artefact | Size | Notes |
| --- | --- | --- |
| `app-debug.apk` | **52.74 MB** (55,306,377 bytes) | Unsigned debug, all ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) plus Vosk/JNA .so |
| `app-release-unsigned.apk` | **10.41 MB** (10,917,946 bytes) | R8-minified, resource-shrunk, arm64-v8a only |
| `classes.dex` (release) | 2.66 MB | |
| `libvosk.so` (release) | 8.66 MB | single ABI; dominates the release APK |
| `libjnidispatch.so` (release) | 164 KB | JNA native bridge |
| `libdatastore_shared_counter.so` (release) | 7 KB | DataStore shared counter |

The release APK is roughly **5× smaller** than the debug APK, almost entirely because the release
build is restricted to `arm64-v8a` (debug keeps every ABI the native AARs ship) and because R8
removes unused code/resources.

## 4. Native ABI split

* Debug: arm64-v8a, armeabi-v7a, x86, x86_64 (as packaged by the Vosk/JNA AARs).
* Release: **arm64-v8a only** (`ndk.abiFilters` in the `release` build type).

## 5. Dependency audit

Runtime dependencies (see `app/build.gradle.kts`):

| Dependency | Why |
| --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib` | Language runtime |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Coroutines |
| `androidx.core:core-ktx` | `ContextCompat`, `ServiceCompat`, notification compat |
| `androidx.appcompat:appcompat` | Compat base for the platform theme/activity stack |
| `androidx.lifecycle:lifecycle-*` | ViewModels, `collectAsStateWithLifecycle`, service lifecycle |
| `androidx.compose.*` (BOM 2024.05.00) | UI |
| `androidx.activity:activity-compose` | `ComponentActivity`, result contracts, edge-to-edge |
| `androidx.navigation:navigation-compose` | Four-screen navigation |
| `androidx.room:room-runtime/ktx` + KSP compiler | Local SQLite storage |
| `androidx.datastore:datastore-preferences` | Settings persistence |
| `com.alphacephei:vosk-android` | Offline recognizer + native libs |
| `net.java.dev.jna:jna@aar` | Native bridge required by Vosk |
| `com.google.android.gms:play-services-location` | Fused Location Provider (location module only) |
| `com.google.code.gson:gson` | Vosk JSON parsing (pure JVM) |

Test dependencies: `junit:junit:4.13.2`, `kotlinx-coroutines-test:1.8.1`.

No analytics, crash reporting, advertising or cloud SDKs are included. No audio is ever stored or
transmitted.

## 6. Unit tests (run without a device)

Command: `./gradlew :app:testDebugUnitTest`

| Test class | Tests |
| --- | --- |
| `TriggerMatcherTest` | full-phrase matching, case/punctuation, partial/unrelated rejection, reversed/non-contiguous, empty/single-word rejection, normalisation, accepted-phrase, repeated phrase |
| `GrammarJsonTest` | trigger words + `[unk]`, deduplication, JSON array shape, `wordsOf` normalisation |
| `VoskResultParserTest` | final/partial JSON parsing, timing, malformed/empty input, `hasFinalResult` |
| `RecognitionPipelineTest` | trigger fires once, unrelated/single words ignored, phrase split across segments, cooldown suppresses duplicates, new utterance after cooldown fires, exhausted source stops processing |
| `LocationSourceTest` | stable wire values, round-trip, unknown → fallback |

Total: **33 tests, 0 failures**.

## 7. What was NOT tested (honest status)

No physical Android device and no emulator were available in this environment, so the following
were **not** executed and must not be claimed as passing:

* Manual acceptance checklist in README §10 (valid trigger saves one record; unrelated sentence
  saves nothing; no accidental duplicates; screen-off listening; earphone save; confirmation only
  after commit; poor-GPS warnings; airplane mode; stop from app and notification; resource release;
  phone-call recovery; R8 release smoke test; prolonged screen-off CPU/RAM/battery/false-trigger
  measurements).
* Microphone foreground service start/stop contract on Android 12–14.
* Fused Location Provider accuracy and cached/fresh/fallback behaviour.
* TTS routing through a wired/Bluetooth earphone.
* `assembleRelease` installability on a real device (the APK is unsigned; signing must be applied
  before installation — see README §6).

## 8. Artefact locations

* Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
* Release APK (unsigned): `app/build/outputs/apk/release/app-release-unsigned.apk`
* R8 mapping (release): `app/build/outputs/mapping/release/mapping.txt`
* Room schema: `app/schemas/com.pocketgps.logger.data.LocationDatabase/1.json`
* Unit-test report: `app/build/reports/tests/testDebugUnitTest/index.html`

## 9. Signing

The release APK is **unsigned** because no keystore is present in this environment. To produce an
installable release build, follow the signing example in README §6 and re-run
`./gradlew :app:assembleRelease`. Do not commit the keystore or passwords.
