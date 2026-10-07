# Walkpad Health - Design Spec

Date: 2026-10-07 (updated after hardware testing)
Status: implemented for version 0.1; living document. The original design was approved before any code; sections marked "changed" record where measured reality or the owner's later decisions moved it.

## 1. Purpose

A free, open-source Android app for a UREVO walking pad (BLE name `URTM059`, "2D Pro") with no account and no login. It records walks, writes them to Health Connect, and shows steps, time, distance and calories. It can also start, pause, resume and stop the belt. Distribution: GitHub releases and F-Droid.

### Success criteria
- A walk on the pad is recorded and appears in History and in Health Connect without the user opening the app (status: History and the Health Connect exercise sessions confirmed on hardware, seen in a third-party Health Connect viewer; the steps, distance and calories records on those sessions still to be checked).
- No analytics, no account, no network use by the app. Crash reports are opt-in and sent only after the user taps Send.
- Every dependency is F-Droid-compatible (no Google Play Services, no proprietary SDKs).
- Protocol logic is unit-tested without hardware, using frames captured from the real pad.

### Non-goals
- Other pad models (the driver is a class in `:protocol`, so a model can be added), iOS, wear devices, cloud sync, social features.
- Setting the belt speed. Only Start, Pause, Resume and Stop are sent.

## 2. Decisions

| Topic | Decision |
|---|---|
| Scope (changed) | Tracking, plus Start/Pause/Resume/Stop control through the standard FTMS Control Point. The original spec was track-only; the owner added control after measuring all commands on the pad. |
| Recording | Auto: a foreground service connects when the pad is on and records while the belt runs. A pause of 60 s ends the walk. |
| Profile | Weight and height entered in Settings, stored on device. No Health Connect read permissions. |
| Units (added) | Imperial by default (mph, miles, pounds, inches), with a Settings switch. Everything is stored in metric. |
| Structure | Two Gradle modules: `:protocol` (pure Kotlin) and `:app`. |
| Stack | Kotlin, Jetpack Compose + Material 3, minSdk 26, Room, Nordic Android-BLE-Library (Apache-2.0, stable). |
| Look (changed) | Dark with a lime accent and a speed dial, light variant following the system. No dynamic color. Design from the owner's mockup. |
| Crash reports | Small custom handler: stack frames only (no exception messages), prompt after a crash, sent only on user tap through the share sheet, no server. Replaces ACRA (needs a mailto address and a dependency). |
| License | GPL-3.0-or-later. |
| Attribution | Protocol facts credit the TreadSpan (E1L) and urevo-darwin (5L) research. No code is copied from either. |
| Repo | Standalone `walkpad-health`, unrelated to any other project. |

## 3. Protocol (measured on URTM059; full detail in `PROTOCOL.md`)

- Handshake: write `02 51 0B 03` then `02 50 03 09 03` to `fff2`; the pad acknowledges on `fff1`. A larger MTU must be requested first: the 25-byte running frame does not fit the default payload, and without the request only idle pings arrive.
- `fff1` sends status (`00` idle, `02` countdown, `03` running, `04` pausing, `0A` paused), elapsed seconds and energy in tenths of a kcal. Its speed and distance fields are in miles, so they are not decoded.
- Standard FTMS Treadmill Data (`2ACD`) supplies speed (km/h), distance (m), energy and elapsed time in SI units. The app merges it with `fff1`.
- The pad has no step count. Steps are estimated.
- The console's Stop button only pauses; the pad stays PAUSED, so the app ends a walk after 60 s of pause.
- Control Point (`2AD9`): `00` request control (once per connection), `07` start or resume, `08 02` pause, `08 01` stop (ends the workout, console shows END). All measured with nobody on the belt, then through the app.

Unknowns that remain: the exact control-point indications, `fff1` status after stop, the meaning of `fff1` bytes 11-12 and the checksum, and whether the units change when the console is set to km.

## 4. Architecture

### 4.1 `:protocol` (pure Kotlin/JVM, no Android imports)
- `UrevoDriver`: `decodeFff1`, `decodeFtms`, `handshakeFrames`. Bounds-checked; never throws.
- `TelemetryMerger`: combines the `fff1` status, elapsed and energy with the FTMS speed and distance.
- `SessionTracker`: state machine with a monotonic clock supplied by the caller; ends a walk on stop or idle, after a 60 s disconnect, or after a 60 s pause; ignores gaps over 5 s; discards runs under 10 s; stores cumulative pad counters as the change since the start frame.
- `FtmsControl`, `PadCommand`, `CommandGate`: the command bytes, reply parsing, which buttons are allowed for a belt status, how a command is confirmed from a status change, and a rate limit for Pause, Resume and Start (Stop is never held back).
- `Estimators` and `finalize`: steps and calories for values the pad does not report, tagged as estimated.
- Dependency rule: `:app` depends on `:protocol`, never the reverse.

### 4.2 `:app`
- `ble/`: `PadManager` (scan result, connect, MTU request, notifications, handshake, command writes), `FrameLog` (raw frame log).
- `service/`: `WalkService`, a foreground service that pipes BLE bytes through the driver and tracker, runs commands, and rebuilds the connection if Android drops it.
- `data/`: Room entities `Session` and `Profile`.
- `health/`: Health Connect writer, a retrying `WorkManager` job, and the Settings button state.
- `ui/`: Compose screens (Walk, History, Settings, a hidden raw-log screen with frame counters).
- `crash/`: custom crash handler and share-sheet prompt.

## 5. Data flow
1. The service scans for a device named `URTM0xx` (or connects to the stored address), requests a larger MTU, enables notifications on `fff1` and `2acd` and indications on `2ad9`, and writes the handshake.
2. Each `fff1` frame is merged with the latest FTMS reading and fed to the tracker. A running belt starts a session; stop, idle, a disconnect or a pause of 60 s ends it.
3. A finished session is saved to Room first, then synced to Health Connect as `ExerciseSession` plus `Steps`, `Distance` and `TotalCaloriesBurned`. Sync is idempotent (stable client record ids) and a session that Health Connect permanently rejects is skipped so it cannot block later walks.
4. A button tap goes through `WalkService.command`, which checks the allowed buttons for the belt status, applies the rate limit, requests control once, writes the command, and confirms it from the pad's reply or a matching status change. No reply and no change shows "The pad did not respond".

## 6. Numbers
- Device-reported values win. Missing values are estimated.
- Each session stores a source tag per metric (`DEVICE` or `ESTIMATED`); History shows how many are estimated.
- Estimators are pure functions with fixed-input tests.

## 7. UI
- **Walk:** status dot and line, speed dial (mph or km/h), time, distance, kcal and steps, the Start/Pause/Resume button and a red Stop button, "Today" tiles. The buttons can be switched off in Settings.
- **History:** sessions, newest first, with estimated and not-synced notes.
- **Settings:** profile, units, Health Connect (install, allow, or "Connected"), auto-record, pad controls, crash prompt, forget paired pad.
- **Raw log** (hidden, tap the version row 7 times): frame counters, recording switch, export, clear.

## 8. Privacy and security
- No `INTERNET` permission. The crash send uses the system share sheet.
- No analytics, no ads, no identifiers. Data stays in the app's private Room database, excluded from cloud backup and device transfer.
- Permissions: Bluetooth scan/connect, notifications, foreground service (connected device), boot completed, Health Connect write only.
- The app writes to the pad only on a button tap (plus the two handshake frames and the one-time request-control write). Pause, Resume and Start are rate-limited; Stop never is.
- Frames from the pad are untrusted input: decoders bounds-check length and ignore malformed frames; they never throw into the service.

## 9. Error handling
Plain messages with a recovery action: Bluetooth off, permission denied, scan failed (bounded retry), pad did not respond or refused a command, storage full (queued and retried), Health Connect missing or not permitted (sessions queue and retry), dead Bluetooth connection (rebuilt automatically).

## 10. Testing
- `:protocol`: JVM unit tests, including real frames from the pad, the tracker state machine, control rules and the estimators.
- `:app`: unit tests for formatting, mapping, the frame log, crash reports and sync; a Room instrumented test (compiles; needs a phone to run).
- Manual: the hardware checklist in `CONTRIBUTING.md` before each release.
- CI: lint, unit tests, assemble, and a reproducible-build check.

## 11. Delivery
- GitHub: signed APK attached to tagged releases by CI.
- F-Droid: reproducible build, metadata in the repo, no proprietary dependencies. Needs the maintainer to create the signing key and submit the recipe.
- Docs: `README.md`, `PROTOCOL.md`, `CONTRIBUTING.md`.

## 12. Open items
- Check that the steps, distance and calories records on a walk in Health Connect match History, and that History numbers match the console.
- Measure the control-point indications and the `fff1` status after stop.
- Release setup: keystore, secrets, public repo URL, tag.
- App name and application id are still the working names (Walkpad Health, `org.walkpadhealth`).
