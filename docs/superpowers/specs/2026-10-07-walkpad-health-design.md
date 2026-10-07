# Walkpad Health - Design Spec

Date: 2026-10-07
Status: draft, awaiting owner review

## 1. Purpose

A free, open-source Android app that tracks sessions on a UREVO walking pad (BLE name `URTM059`, "2D Pro") with no account and no login, and writes them to Health Connect. It shows steps, time, distance and calories. Distribution: GitHub releases and F-Droid.

### Success criteria
- A walk on the pad is recorded and appears in Health Connect without the user opening the app.
- No analytics, no account, no network use by the app. Crash reports are opt-in and sent only after the user taps Send.
- Every dependency is F-Droid-compatible (no Google Play Services, no proprietary SDKs).
- Protocol logic is unit-tested without hardware.

### Non-goals (v1)
- Controlling the pad (start, stop, speed). The driver is designed so control can be added later, but v1 never writes control frames. Only the two handshake frames are written (see 3.1).
- Other pad models, iOS, wear devices, cloud sync, social features.

## 2. Decisions (agreed with owner)

| Topic | Decision |
|---|---|
| Scope | Track only. Control is a later, separate spec. |
| Recording | Auto: a foreground service connects when the pad is on and records while the belt runs. |
| Profile | Weight and height entered in Settings, stored on device. No Health Connect read permissions. |
| Structure | Two Gradle modules: `:protocol` (pure Kotlin) and `:app`. |
| Stack | Kotlin, Jetpack Compose + Material 3, minSdk 26, Room, Nordic Android-BLE-Library (Apache-2.0, stable). |
| Crash reports | Small custom handler: stack frames only (no exception messages), prompt after a crash, sent only on user tap through the share sheet, no server. Replaces ACRA (needs a mailto address and a dependency). |
| License | GPL-3.0-or-later. |
| Attribution | Protocol facts credit the TreadSpan (E1L) and urevo-darwin (5L) research. No code is copied from either. |
| Repo | Standalone `walkpad-health`, unrelated to any other project. |

## 3. Protocol basis and risk

### 3.1 Known (from the 5L / E1L research, same OEM family)
- Services `0xFFF0` (`fff1` notify telemetry, `fff2` write), `0xFEE0`, `0x1826` (FTMS, `2acd` treadmill data), `0x180A`. Confirmed present on `URTM059` via nRF Connect.
- `fff1` stays silent until two handshake frames are written to `fff2`: `02 51 0b 03` then `02 50 03 09 03`.
- `fff1` frames start `02 51`; byte 2 is status (`00` idle, `01` stopped, `03` running, `04` pausing, `0a` paused); bytes 3-4 are speed (u16 LE, raw 0.1 km/h). Later bytes (elapsed time, distance) are only partly mapped.

### 3.2 Unknown (must be measured on the real pad)
- Whether `URTM059` uses the same `fff1` layout, and which fields it reports (distance, steps, calories).
- Whether `2acd` carries distance, energy and elapsed time on this model.

### 3.3 Mitigation
Phase 0 captures real frames with an in-app raw log before any field mapping is trusted. Decoders are written against captured fixtures. Unknown frame shapes are ignored and logged, never guessed.

## 4. Architecture

### 4.1 `:protocol` (pure Kotlin/JVM, no Android imports)
- `UrevoDriver`: decodes raw `fff1` / `2acd` bytes into `Telemetry(status, speedKmh, elapsedSec, distanceM?, steps?, kcal?)`. Exposes `handshakeFrames`.
- `SessionTracker`: state machine idle, running, paused, stopped. Emits `SessionSummary`. A disconnect longer than 60 s ends the session.
- `Estimators`: steps = distance / stride (stride derived from height); calories via the ACSM walking equation from speed, duration and weight. Used only for fields the pad does not report.
- Dependency rule: `:app` depends on `:protocol`, never the reverse.

### 4.2 `:app`
- `ble/`: scan, connect, enable notifications, write handshake, reconnect (Nordic library).
- `service/`: foreground service that pipes BLE bytes through the driver and tracker.
- `data/`: Room entities `Session`, `Profile`; DAOs; a `pendingSync` flag on `Session`.
- `health/`: Health Connect writer and a retrying `WorkManager` job.
- `ui/`: Compose screens and view models.
- `crash/`: custom crash handler and share-sheet prompt.

## 5. Data flow
1. The service scans for a device named `URTM0xx` (or the stored MAC), connects, enables notifications on `fff1` and `2acd`, and writes the handshake.
2. Each frame goes driver, tracker. Belt running starts a session; stop, or disconnect over 60 s, ends it.
3. A finished session is saved to Room first, then synced by a retrying worker to Health Connect as `ExerciseSession` plus `Steps`, `Distance` and `TotalCaloriesBurned`. The Room-first order means a failed sync loses nothing; sync is idempotent (client record id = session id).

## 6. Numbers
- Device-reported values win. Missing values are estimated.
- Each session stores a source tag per metric (`device` or `estimated`); the UI shows it.
- Estimators are pure functions with fixed-input tests.

## 7. UI
- **Today:** live card while walking (speed, time, distance, steps, kcal), day totals, session list.
- **History:** sessions by day; per-session detail with source tags.
- **Settings:** profile (weight, height), Health Connect permission and status, auto-record toggle, crash-report toggle, paired pad.
- **Raw log** (hidden, behind a Settings tap sequence): records frames to a file and exports it. Used for Phase 0 and bug reports.
- Simple, system-themed (light/dark, dynamic color), accessible text sizes.

## 8. Privacy and security
- No `INTERNET` permission. The crash send uses the system share sheet / mail intent.
- No analytics, no ads, no identifiers. Data stays in the app's private Room database.
- Permissions: Bluetooth scan/connect, notifications, foreground service (connected device), Health Connect write only.
- Android backup of the app database is disabled unless the owner opts in later.
- Frames from the pad are untrusted input: decoders bounds-check length and ignore malformed frames; they never throw into the service.

## 9. Error handling
Each case gets a plain, localized message and a recovery action: Bluetooth off, permission denied, pad out of range, pad connected but silent (handshake failed), Health Connect missing or not permitted (sessions queue and retry), storage full.

## 10. Testing
- `:protocol`: JVM unit tests against byte fixtures (captured from the pad), the tracker state machine, and the estimators.
- `:app`: Room instrumented tests; a fake driver and fake BLE source to exercise the service and UI without hardware.
- Manual: a hardware checklist run on the real pad before each release.
- CI: lint, unit tests, assemble.

## 11. Delivery
- GitHub: signed APK attached to tagged releases by CI.
- F-Droid: reproducible build, metadata file, no proprietary dependencies, no anti-features. The Health Connect client (`androidx.health.connect`) is open source.
- Docs: README (install, pairing, privacy), `PROTOCOL.md` (what was measured on `URTM059`, with credits), `CONTRIBUTING.md`.

## 12. Phases
0. Raw-frame capture from the pad; fill 3.2.
1. `:protocol` and tests.
2. BLE service and Room.
3. Health Connect sync.
4. UI.
5. Release (GitHub, then F-Droid submission).

## 13. Open items
- Field map for `URTM059` (Phase 0).
- Whether steps are device-reported (decides if the stride estimator is needed on this model).
- App name and application id (working name: Walkpad Health, `org.walkpadhealth`).
