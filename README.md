# Walkpad Health

Open-source Android app that records UREVO walking-pad sessions over Bluetooth LE and writes them to Health Connect. No account, no analytics.

Status: version 0.1 in development. The Bluetooth field map for the URTM059 still needs a capture from the real pad; see `PROTOCOL.md`.

## Install
- GitHub releases: download the signed APK.
- F-Droid: submission pending.

## First run
1. Grant the Bluetooth and notification permissions when asked.
2. In Settings, allow writing to Health Connect and enter your weight and height (used for estimates; stored on the device only).
3. Turn the pad on. The app finds it, connects, and records while the belt runs.
4. Exempt the app from battery optimization so recording survives screen-off. If the OS kills the app, it resumes at the next boot or the next time you open it.

## Privacy
No network permission, no analytics, no ads. After a crash the app offers to share the error type, code locations and app version through the system share sheet; nothing is sent unless you tap Send.

## Supported device
UREVO URTM059 (walking pad). Other pads in the same family may work; they are untested.

## Build
See `CONTRIBUTING.md`. License: GPL-3.0-or-later.
