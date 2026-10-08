# Underfoot

Open-source Android app for UREVO walking pads. It records your walks over Bluetooth LE, saves them to Health Connect, and lets you start, pause, resume and stop the belt. No account, no analytics.

Status: version 0.1, tested on a UREVO `URTM059` (the "2D Pro").

## What it does
- **Records automatically.** When the pad is on, a background service connects and records while the belt runs. A walk ends when you end it on the pad (long-press play/pause) or in the app, or when the pad powers off.
- **Shows live numbers:** speed (mph, or km/h), time, distance, calories and steps, with a speed dial.
- **Controls the belt:** Start, Pause/Resume, a red Stop button and belt speed (drag the dial handle or tap - and +, 0.6-4.0 mph). They send only what you tap, and you can switch the controls off in Settings.
- **Saves to Health Connect** as a Walking exercise session with steps, distance and total calories.
- **Keeps a history** with each value marked as reported by the pad or estimated.

Time, distance, speed, energy and steps come from the pad. Calories are the pad's own estimate.

## Install
- GitHub releases: download the signed APK from the [latest release](https://github.com/TechHQUSA/underfoot/releases/latest) and open it on your phone (Android 9 or newer).
- F-Droid: submission pending.

## First run
1. Allow the Bluetooth and notification permissions when asked.
2. In Settings, tap **Allow writing to Health Connect** and grant the four write permissions. The button then reads "Connected to Health Connect". On Android 13 and older, Health Connect is a separate app and the button opens its store page.
3. Enter your weight and height (used for estimates; stored on the device only). Units are miles and pounds by default, with a switch for metric.
4. Turn the pad on. The app finds it, connects, and shows "Connected locally".
5. Exempt the app from battery optimization so recording survives screen-off. If the OS kills the app, it resumes at the next boot or the next time you open it.

The pad talks to one app at a time. Close other apps that use it (including the official one) while Underfoot is running.

## Privacy
No network permission, no analytics, no ads. Your data stays on the phone, excluded from cloud backup and device transfer. After a crash the app offers to share the error type, code locations and app version through the system share sheet; nothing is sent unless you tap Send.

The app writes to the pad only when you tap a button, apart from the Bluetooth handshake that makes the pad start sending data.

## Supported devices
UREVO URTM059 only. Other pads in the same family may work; they are untested. See `PROTOCOL.md` for what was measured, and `CONTRIBUTING.md` for how to add a model.

## Build
See `CONTRIBUTING.md`. JDK 17 and the Android SDK (platform 36). License: GPL-3.0-or-later.
