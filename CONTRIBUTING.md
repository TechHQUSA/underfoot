# Contributing

## Layout
- `:protocol` is pure Kotlin: frame decoding, the session state machine, the step and calorie estimators. No Android imports. Tests use byte fixtures.
- `:app` holds Bluetooth transport, the foreground service, Room storage, Health Connect sync and the Compose UI.

## Adding support for another pad
1. Capture frames with the in-app raw log (Settings, tap the version row 7 times, turn on "Record raw frames", walk, export).
2. Add a decoder class in `:protocol`, with fixtures copied from the capture and tests written first.
3. Wire it into `WalkService` only after its tests pass.

## Build and test
```bash
./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest   # needs a phone on adb
```
JDK 17 and the Android SDK (platform 36) are required.

## Hardware checklist (run before every release)
- Pad on, app closed, walk 2 minutes: the session appears in History and in Health Connect with correct totals.
- Pause and resume keeps one session.
- Walk out of range for 30 seconds and back: still one session. Out of range for 70 seconds: the session ends.
- Reboot the phone with "Keep pad connected in the background" off: nothing starts and the pad is left alone. Turn it on and reboot: the service starts and connects.
- With it off, open the app: it connects. Lock the phone mid-walk: recording continues. End the walk and leave the app hidden: about 2 minutes later the notification goes away and the pad is no longer connected.
- Turn "Keep pad connected in the background" off while connected and the app open: it stays connected until you leave; turn it on again: one session per walk, not two.
- Switch the pad off for an hour with background mode on: battery use stays negligible (the background connect just waits).
- With Record raw frames on and the app open but the pad not connected, the log fills with `adv:<rssi>` lines (the pad's advertising bytes). Record a minute each with the pad off, on and idle, and paused.
- Bluetooth off, or the Bluetooth permission revoked: Today shows the matching message.
- Health Connect permission denied: sessions queue and sync after you grant it.
- In the Health Connect app, one walk shows as one Walking session with steps, distance and calories. Re-running sync (toggle the permission off and on, reopen the app) creates no duplicates.
- Tap "Forget paired pad" in Settings while connected: the connection drops, the app rescans, and the pad pairs again with a single session per walk.
- Tap the recording notification: the app opens.
- Set "Let the pad sleep" to 5 min, pause the walk and wait: the app disconnects, the pad switches itself off, and resuming the walk afterwards keeps one session.
- After the app releases the pad (Today says Resting), the pad powers off and stays off; the app does not reconnect on its own, not even after an hour.
- While resting, resume on the remote within the pause backstop (30 min after the pause) and walk a few minutes, then tap Reconnect: the saved walk's duration matches the pad's clock, not just the time the app saw.
- Force a crash in a debug build, relaunch: the dialog appears, Send opens the share sheet, and the prompt does not repeat.

## Releases
Tag `vX.Y.Z`; CI builds and signs the APK and attaches it to a GitHub release. The signing keystore and the four repository secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are the maintainer's to create and back up; losing the keystore means installed copies cannot update.
