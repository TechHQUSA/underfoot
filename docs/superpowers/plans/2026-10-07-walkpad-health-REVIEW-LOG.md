# Plan Review Log: walkpad-health implementation plan
Codex model gpt-5.6-terra, read-only. MAX_ROUNDS=5.

## Round 1 - Codex
- **Critical — Task 8, `HealthGateway.kt` lines 1680–1687:** `Metadata.manualEntry(clientRecordId = ...)` is the wrong recording method for BLE-captured sessions, and the same client ID is reused for all four record types. **Fix:** use `Metadata.autoRecorded`/`activelyRecorded` with a `Device`, stable version, and distinct IDs such as `walkpad-$id-exercise`, `-steps`, `-distance`, `-kcal`. [Health Connect Metadata API](https://developer.android.com/reference/androidx/health/connect/client/records/metadata/Metadata)

- **Critical — Task 8, line 1687:** “same clientRecordId upserts” is incomplete: replacement depends on `clientRecordVersion`, and the plan has no gateway-level idempotency test. **Fix:** persist a stable per-record client ID/version and test retry after an ambiguous `insertRecords` failure. [Health Connect sync guidance](https://developer.android.com/health-and-fitness/health-connect/sync-data)

- **Critical — Task 3, `SessionTracker.kt` lines 765–784:** a telemetry frame arriving after a 60-second disconnect clears `disconnectedAt` before expiry is checked, incorrectly merging a new walk into the old session. **Fix:** check and finalize an expired disconnect before clearing it, then process the frame as a possible new session; add a direct reconnect-at-60s test.

- **Critical — Task 3, lines 774–776 and 798–801:** device totals are copied as absolute “latest” values, not session deltas; pads commonly report cumulative distance/steps/calories. The initial running frame is also discarded. **Fix:** capture device values on the start frame as baselines and persist validated `latest - baseline` deltas.

- **High — Task 3, lines 769–773:** integration applies the current frame’s speed to the preceding interval, so every speed transition credits distance at the wrong speed. **Fix:** retain `lastSpeedKmh` and integrate that over `nowMs - lastMs`.

- **High — Task 3 / Task 6, lines 745 and 1341–1402:** `SessionTracker` is declared non-thread-safe but is called by BLE callbacks and a main-thread ticker without serialization; a callback/tick race can double-finalize or corrupt time/distance. **Fix:** funnel all tracker operations through one serial coroutine/Handler and make persistence idempotent.

- **High — Task 6, lines 1357–1360 and 1425–1432:** the service calls `startForeground()` before verifying the runtime Bluetooth prerequisite for `connectedDevice`; on Android 14+ this can throw a security exception when permission was denied/revoked. **Fix:** check granted Bluetooth permission before entering foreground and stop with a visible recovery notification/state. [FGS connected-device prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types)

- **High — Task 6, lines 1490–1495 and Task 9, lines 2009–2015:** permission callbacks start the service even when Bluetooth permission is denied; there is no grant-result check. **Fix:** start only if required Bluetooth permissions are granted, otherwise show the required denial message/action.

- **High — Task 5 / Task 6, lines 1138–1159 and 1405–1410:** scanning requires an advertised FTMS service and then matches only `URTM*`; this does not implement the spec’s “stored MAC” path and can miss pads that do not advertise FTMS/name. **Fix:** persist a user-selected address, connect it directly when known, and use a broader bounded scan plus robust name/address matching for pairing.

- **High — Task 6 / Task 9:** the “Record automatically” toggle does not start/stop the service or scan when changed, and no paired-pad setting exists. **Fix:** make the toggle issue explicit start/stop actions and implement persisted paired-device selection/address storage.

- **High — Task 5, `FrameLog.kt` line 1070:** `%02x`.format(it) sign-extends negative Kotlin `Byte`s, corrupting raw logs for bytes `>= 0x80`. **Fix:** format `it.toInt() and 0xff`; add a test containing `0x80`/`0xff`.

- **High — Task 6, lines 1370–1375:** a no-speed, paused, or stopped frame leaves the prior running speed displayed, producing false live state. **Fix:** set displayed speed to `t.speedKmh ?: 0.0` for non-running/no-speed telemetry.

- **Medium — Task 5, lines 1151–1158:** it assumes both characteristics support notifications and queues the handshake without explicit success/failure sequencing; FTMS may indicate rather than notify. **Fix:** inspect characteristic properties, choose notification vs indication, and write handshake only after FFF1 subscription succeeds.

- **Medium — Task 3, lines 769 and 778:** wall-clock timestamps are used for duration/integration with no monotonicity guard, so clock changes or out-of-order BLE callbacks can distort sessions. **Fix:** use monotonic elapsed time for intervals and separately retain epoch timestamps for persisted start/end.

- **Medium — Task 3, lines 725–735:** `Infinity` device metrics pass `> 0` and can reach Room/Health Connect; only `NaN` happens to be rejected. **Fix:** require `isFinite() && > 0` for every device and integrated value.

- **Medium — Task 4, `DaoTest.kt` line 961:** `ApplicationProvider` is used but `androidx.test:core` is not declared, so the stated instrumented test may not compile. **Fix:** add `androidTestImplementation("androidx.test:core:…")`.

- **Medium — Task 6 / Task 9:** the spec requires clear localized Bluetooth-off, permission-denied, out-of-range, silent-pad, Health Connect, and storage-full messages; the plan deliberately omits most of them and hard-codes English strings. **Fix:** add string resources and explicit UI/service error-state modeling with recovery actions.

- **Medium — Task 9:** `MaterialTheme` has no light/dark or dynamic color scheme, despite the spec requiring system-themed dynamic color. **Fix:** add an app theme selecting dark/light dynamic schemes with a non-dynamic fallback.

- **Medium — Task 10, lines 2091–2105:** a raw exception stack trace and message are not guaranteed free of user data or paths; the single `/Users/` assertion is not meaningful protection. **Fix:** redact paths, identifiers, BLE payloads, and exception-message content (or clearly show the exact report before share) and test redaction.

- **Medium — Task 6 / Task 11:** “app closed → walk” depends on a perpetually running FGS/boot restart, but there is no Companion Device association/presence strategy or reliable recovery after OS kill. **Fix:** explicitly define the supported lifecycle, or use Companion Device Manager for the paired pad; Android recommends it for continuous external-device work. [Android FGS guidance](https://developer.android.com/develop/background-work/services/fgs/service-types)

- **Medium — Task 7:** capture mapping accepts merely monotonic fields matching two manual readings; that is too weak to establish units, rollover behavior, cumulative-vs-session semantics, or field identity. **Fix:** require controlled start/reset/pause/known-distance fixtures and test decoding across rollovers and reconnects.

- **Medium — Task 8 tests:** `SyncSessionsTest` only validates a fake orchestration layer; it cannot prove Health Connect permissions, metadata construction, record validity, or retry deduplication. **Fix:** add focused gateway construction tests and an instrumented/manual Health Connect validation checklist.

- **Medium — Task 0 / Task 11:** F-Droid reproducibility is asserted but not tested; release signing is only in GitHub Actions and no F-Droid metadata/build recipe is included in this repository. **Fix:** add a reproducible unsigned-release build verification and commit the drafted metadata/specification used for the F-Droid submission.

- **Low — Task 4 versus Task 9:** `AppDb.cachedProfile` is already added in Task 4, but Task 9 says to add it again. **Fix:** remove the duplicate ownership instruction and replace the global cache with a collected profile flow or an atomic repository snapshot.

VERDICT: REVISE
### Claude's response (round 1)

**Accepted and applied**
- Health Connect metadata: switched to `Metadata.autoRecorded` with a `Device`, one distinct stable id per record type, constant version; added HC checks (no duplicates on re-sync) to the hardware checklist, since a JVM test cannot prove them.
- Tracker: an expired disconnect is finalized before the next frame is processed (new test at exactly 60 s); device distance/steps/kcal are stored as `latest - start-frame baseline` with a reset fallback (new tests); distance now integrates the previous speed over each interval (new test); non-finite device values are dropped in tracker and `finalize` (new tests).
- Threading: all BLE callbacks are re-posted to the main handler so the tracker, ticker and persistence share one thread.
- Foreground-service safety: Bluetooth permission is checked before `startForeground`; a single `WalkService.sync` entry point (used by UI, boot receiver and the auto-record toggle) starts or stops the service and only starts it when permission is granted.
- Pairing: the paired address is persisted; the service connects directly when known and scans (FTMS service filter, `URTM*` name match) only when not; a "Forget paired pad" button was added.
- `FrameLog` hex formatting sign-extension bug fixed, with a test.
- Live speed now reads 0 when not running or when the frame carries no speed.
- Notification vs indication is chosen from the characteristic properties; the handshake ordering is guaranteed by Nordic's request queue (comment added).
- Missing `androidx.test:core` test dependency added.
- Error states (Bluetooth off, permission denied) modelled as `Problem` and shown on Today; all UI text moves to `strings.xml`; a dynamic-color light/dark theme was added; "silent pad" and "storage full" are specified.
- Crash reports: exception messages are no longer included (class names, methods and line numbers only), with redaction tests.
- Task 7 acceptance is stricter: controlled capture runs (second walk without reconnect, known 10-minute distance, reconnect), reset-vs-cumulative behaviour recorded.
- F-Droid: the recipe is committed in-repo and CI builds the unsigned release twice and compares the APKs.
- Duplicate `cachedProfile` ownership instruction removed.

**Rejected, with reasons**
- Monotonic clock for tracker intervals: the tracker already ignores any interval that is not in `1..gapMs`, so a backwards clock step or out-of-order callback accrues nothing; epoch time is still needed for stored start and end. Not worth a second time source.
- Companion Device Manager: a real option, but it adds an association flow and permissions for a 0.1 release. The supported lifecycle is now stated explicitly (foreground service, boot and app-open start, resume after OS kill) and CDM is logged as a follow-up.
- Replace `AppDb.cachedProfile` with a repository snapshot: the cache is one nullable value set in two places; a repository is more structure than the problem needs.
- A gateway-level Health Connect unit test: it cannot run on the JVM without a device or the HC test rules; covered by the manual checklist and the F-Droid/hardware steps instead.

## Round 2 - Codex
- **High — Task 3, `SessionTracker.kt` lines 810–824; rejected monotonic-clock item:** the rejection is unsound. A backward clock jump is skipped once, but `lastMs` is still moved backward; the next frame can then accrue invented time/distance. End time can also precede start time. **Fix:** pass monotonic elapsed timestamps for tracking/integration and retain wall-clock timestamps separately for persistence.

- **High — Task 6, `WalkService.kt` lines 1440–1446:** scan callbacks are not posted to `main`, contrary to the task’s threading claim; they mutate `scanning`, preferences, and initiate connection off the serialized tracker thread, with possible duplicate callbacks/connections. **Fix:** immediately `main.post { handleScanResult(result) }`, with a `connecting` guard.

- **High — Task 6, lines 1442–1445 and 1497–1501:** the first matching advertisement is saved as the permanent paired address before GATT service validation/connection succeeds; a stale/spoofed `URTM*` device prevents later discovery. The fallback scan still requires advertised FTMS, which is not established by the spec. **Fix:** retain a pending address and persist it only in `onDeviceReady` after validating required services; use a name/manufacturer-data scan fallback without an FTMS advertisement requirement.

- **Medium — Task 6, lines 1497–1501:** there is no `onScanFailed` handling or retry, so scanner registration/resource failures silently leave auto-recording dead. **Fix:** implement `onScanFailed`, publish a recoverable problem state, and schedule bounded retry.

- **Medium — Task 6 line 1510 and Task 9 line 2194:** the proposed storage-full handling cannot satisfy “drop nothing else”: after a failed Room insert, the completed session exists only in memory and is lost on process death. **Fix:** retain failed finalized sessions in a durable recovery queue where possible, retry insertion, and explicitly document unavoidable full-storage behavior.

- **Medium — Task 9 line 2194:** “connected + IDLE for 15 seconds” does not establish that the pad is silent or that the belt is expected to be running; it will falsely report a handshake problem for an idle but healthy pad. **Fix:** base the warning on a user-initiated diagnostic/raw-frame timeout, or present it as a neutral “no running telemetry yet” status.

- **Medium — Task 10 line 2296:** deleting the crash file immediately after launching the chooser can break the shared attachment; a URI permission grants access but does not copy file contents. **Fix:** retain the file until a later launch (suppress repeat prompting with a separate “offered” flag), or copy it to a share-safe temporary location with a defined cleanup policy.

VERDICT: REVISE
### Claude's response (round 2)

**Accepted and applied**
- Monotonic time (reversing my round 1 rejection): the tracker now runs entirely on a caller-supplied monotonic clock; the service passes `SystemClock.elapsedRealtime()` and converts summary times to epoch once, with a single offset, in `persist`, so `end >= start` always holds.
- Scan callbacks now re-post to the main handler; a `connecting` guard stops duplicate connections.
- The paired address is held as `pendingAddress` and saved only when the connection reports ready (services validated), so a stale or spoofed `URTM*` advertiser cannot become the permanent pad.
- `onScanFailed` is handled: five retries 10 s apart, then a visible `SCAN_FAILED` problem state with a message.
- Storage full: finalized sessions go into an in-memory queue, inserts are retried every 30 s, a notification is shown, and the plan states plainly that a process death during full storage loses the queued sessions.
- "Silent pad" warning removed: Today shows a neutral "Connected. Start the belt to begin recording." status, and diagnosis of a mute pad is the raw log screen.
- Crash report file is moved to `offered.txt` before the share URI is built and deleted at the next app start, so the share sheet can read it and the prompt does not repeat.

**Rejected, with reason**
- Scan fallback without an FTMS advertisement requirement: Android only delivers screen-off scan results when a scan filter is set, so an unfiltered name scan would stop working with the phone locked, which is the main use case. The target pad does advertise `0x1826` (seen in nRF Connect). A pad that does not would need an exact-name or address filter added with its driver; documented as a driver-level concern, not a v1 fallback.

## Round 3 - Codex
- **High — Task 10, `CrashReporter.kt` lines 2324–2325:** deleting `offered.txt` in `Application.onCreate()` is still unsafe. If the provider process is recreated when the share target opens the URI, app initialization can delete the attachment before it is read. **Fix:** retain offered files for a timed retention period (or share a copied cache file) and never delete solely on app startup; also check that the move succeeded.

- **Medium — Task 6, `WalkService.persist()` lines 1539–1543:** converting every monotonic timestamp using the offset at session end preserves duration but gives the wrong real start/end time if wall time changes during the walk. **Fix:** capture an epoch start alongside the monotonic start when a session begins, then derive persisted end from monotonic elapsed duration.

- **Medium — Task 6, lines 1546–1568 / string line 1606:** the new volatile queue does not make “Walks are kept and saved once there is space” true; process death, force-stop, or service recreation loses queued sessions, which contradicts the visible claim and the Room-first durability goal. **Fix:** change the user-facing message to state the limitation and add a durable recovery journal when storage permits; otherwise report the session as unsaved explicitly.

VERDICT: REVISE
### Claude's response (round 3)

**Accepted and applied**
- Crash file retention: `markOffered` checks the rename (falls back to copy then delete) and stamps the offer time; `cleanOffered` deletes `offered.txt` only when it is over 24 hours old, never merely because the app started.
- Wall-clock start: `SessionTracker` takes an injectable `wallClock` and records `wallStartMs` when a session begins; `SessionSummary` carries it; `persist` stores `start = wallStartMs` and `end = wallStartMs + monotonic duration`, so a wall-clock jump mid-walk cannot move or reverse the stored times (new test).
- Storage-full message now says the last walk is not saved yet and to free up space, instead of promising it is kept.

**Rejected, with reason**
- A durable recovery journal for full storage: a journal needs free storage to be written, so it fails in exactly the case it is meant for. When storage is not full, Room already is the durable store. The plan now states the limitation instead.

## Round 4 - Codex
The wall-clock start capture, monotonic tracker flow, persistence conversion, and crash-report retention are coherent and compile-plausible. The new tracker test hand-traces correctly against the implementation.

VERDICT: APPROVED
Converged: APPROVED after 4 rounds.
