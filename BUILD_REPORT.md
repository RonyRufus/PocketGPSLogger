# Pocket GPS Logger — v2.0.0 Build Report

Generated during the ground-up v2 rebuild (Linux build box, JDK 17, Android SDK 34,
build-tools 34.0.0). All sizes are the actual produced artefacts.

## 1. Build environment

| Item | Value |
| --- | --- |
| JDK | OpenJDK 17 |
| Android SDK | platforms android-34, build-tools 34.0.0 |
| Gradle | 8.7 (wrapper) |
| AGP | 8.4.2 |
| Kotlin | 1.9.23 (KSP 1.9.23-1.0.19) |
| Compose BOM | 2024.05.00 |
| Room | 2.6.1 |

## 2. Build results

| Task | Result |
| --- | --- |
| `:app:testDebugUnitTest` | **SUCCESS** — 22 tests, 0 failures, 0 skipped |
| `:app:assembleRelease` | **SUCCESS** — R8 full mode + resource shrinking |
| `apksigner verify` | **PASS** (single debug-cert signer, installs directly) |

## 3. APK size (measured)

| Version | APK size | Notes |
| --- | --- | --- |
| v1.0 (original, voice) | 10.41 MB | Vosk + JNA native libs, icon library, appcompat |
| v1.1 (efficiency pass) | 4.03 MB | Vosk slimmed, icons hand-written, R8 tuned |
| **v2.0 (tap-first rebuild)** | **1.03 MB** (1,082,347 bytes) | voice stack removed entirely |

The single biggest win of the rebuild: no `libvosk.so`, no JNA, no model management, no
microphone path — that alone removed ~3 MB of packed natives and the entire 40 MB runtime
model that previously had to live in RAM while listening.

## 4. Runtime footprint (by design)

* Parked tier: no sensor listeners, no wake lock, no GPS — only the hardware
  significant-motion trigger (~0 power) stays armed.
* Moving tier: one accelerometer listener + one partial wake lock + passive low-power
  GPS pre-warm listen.
* Save: one bounded high-accuracy fix, one Room insert, one haptic pulse. GPS is never
  held continuously; the app has **no Internet permission**.

## 5. Versioning

`versionCode 3`, `versionName "2.0.0"`.
