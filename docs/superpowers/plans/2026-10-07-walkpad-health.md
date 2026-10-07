# Walkpad Health Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An open-source Android app that auto-records UREVO `URTM059` walking-pad sessions over BLE and writes them to Health Connect, with no account, no analytics and opt-in crash reports.

**Architecture:** Two Gradle modules. `:protocol` is pure Kotlin (frame decoding, session state machine, step and calorie estimators) and is unit-tested with byte fixtures. `:app` holds BLE transport (Nordic Android-BLE-Library), a foreground service, Room storage, a Health Connect sync worker and the Compose UI.

**Tech Stack:** Kotlin 2.1, Gradle 8.11 / AGP 8.9, Jetpack Compose + Material 3, Room, WorkManager, Nordic `no.nordicsemi.android:ble`, `androidx.health.connect:connect-client`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-07-walkpad-health-design.md`

## Spec deviations (applied to the spec in Task 0)
1. BLE: Nordic **Android-BLE-Library** (stable, Apache-2.0) replaces the "Kotlin BLE library" named in the spec, which is still alpha.
2. Crash reports: a ~60-line custom handler plus a share-sheet dialog replaces ACRA. ACRA needs a mailto address and a dependency; the share sheet needs neither and sends nothing unless the user taps Send.
3. Toggles (auto-record, raw log, crash prompt) live in SharedPreferences; only `Profile` and `Session` live in Room.

## Global Constraints
- minSdk 26. Kotlin + Jetpack Compose + Material 3. Room for storage. GPL-3.0-or-later.
- Every dependency must be F-Droid-compatible: no Google Play Services, no proprietary SDKs, no analytics.
- No `INTERNET` permission. No analytics, no ads, no identifiers.
- v1 is track-only: the app never writes control frames to the pad. The only writes are the two handshake frames `02 51 0b 03` and `02 50 03 09 03` to `fff2`.
- Health Connect: write permissions only (no read). Weight and height come from Settings only.
- Device-reported values win over estimates; every stored metric carries a `DEVICE` or `ESTIMATED` source tag.
- A session ends on a stop/idle frame or a disconnect longer than 60 s. Sessions are saved to Room before any Health Connect sync.
- Pad frames are untrusted input: decoders bounds-check and never throw into the service.
- Package `org.walkpadhealth`; working name "Walkpad Health".
- All user-visible text comes from `strings.xml` (English only in 0.1; no hard-coded literals in Compose code).
- Supported lifecycle: auto-record is a foreground service started at boot and at app open, kept alive by `START_STICKY`. If the OS kills it, it resumes at the next boot or app open; the README says so and recommends a battery-optimization exemption. Companion Device Manager association is a post-0.1 follow-up.
- Device-reported distance, steps and kcal are treated as possibly cumulative: a session stores `latest - value at the start frame` (or `latest` if the counter went down).

## Review Focus
1. Truncated or garbage frames (empty, 1-5 bytes, wrong header, 18-byte "running" frame with no speed) must yield `null` or a no-speed reading, never a crash. Pinned in Task 1.
2. Reconnect and gaps: a frame gap over 5 s must not be integrated into distance; a disconnect under 60 s must resume the same session; 60 s or more must end it. Pinned in Task 3.
3. Accidental starts: a belt run under 10 s of active time must not create a session. Pinned in Task 3.
4. Bad or missing profile: NaN, zero or negative weight/height must yield 0 estimates, not NaN or a crash; a missing profile falls back to defaults and is tagged `ESTIMATED`. Pinned in Tasks 2 and 3.
5. Health Connect unavailable, permission denied, or a duplicate sync: sessions stay unsynced and retry, a failed write never marks a session synced, and re-running sync is idempotent. Pinned in Task 8.

---

## File Structure
```
walkpad-health/
  settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml  gradle.properties
  LICENSE  README.md  PROTOCOL.md  CONTRIBUTING.md  .gitignore
  .github/workflows/ci.yml  .github/workflows/release.yml
  fastlane/metadata/android/en-US/{short_description.txt,full_description.txt,changelogs/1.txt}
  protocol/build.gradle.kts
  protocol/src/main/kotlin/org/walkpadhealth/protocol/
    Telemetry.kt  UrevoDriver.kt  Estimators.kt  SessionTracker.kt  SessionSummary.kt
  protocol/src/test/kotlin/org/walkpadhealth/protocol/
    UrevoDriverTest.kt  EstimatorsTest.kt  SessionTrackerTest.kt  FinalizeTest.kt
  app/build.gradle.kts  app/src/main/AndroidManifest.xml
  app/src/main/kotlin/org/walkpadhealth/
    WalkpadApp.kt  AppPrefs.kt  LiveState.kt  MainActivity.kt
    ble/{PadManager.kt,FrameLog.kt}
    service/{WalkService.kt,BootReceiver.kt}
    data/{Entities.kt,Daos.kt,AppDb.kt,Mapping.kt}
    health/{HealthGateway.kt,SyncSessions.kt,SyncWorker.kt}
    crash/CrashReporter.kt
    ui/{MainViewModel.kt,Format.kt,Theme.kt,TodayScreen.kt,HistoryScreen.kt,SettingsScreen.kt,RawLogScreen.kt}
  app/src/main/res/values/strings.xml
  app/src/test/kotlin/org/walkpadhealth/{SyncSessionsTest.kt,MappingTest.kt,FormatTest.kt}
  app/src/androidTest/kotlin/org/walkpadhealth/DaoTest.kt
```

---

### Task 0: Toolchain, scaffold, CI, spec sync

**Files:** Create everything under repo root listed above except `protocol/src` and `app/src/main/kotlin`. Modify the spec.

**Interfaces:** Produces: a building two-module Gradle project; `./gradlew :protocol:test` and `./gradlew :app:assembleDebug` work.

- [ ] **Step 1: Check the toolchain.** This Mac currently has no JDK, no Android SDK and no Gradle. Ask the owner before installing, then run:
```bash
brew install --cask temurin@17
brew install gradle
brew install --cask android-commandlinetools
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME" && yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
java -version && echo $ANDROID_HOME
```
Expected: Java 17 reported; SDK directory populated. Add `export ANDROID_HOME=...` to the shell profile (fish: `set -Ux ANDROID_HOME ...`).

- [ ] **Step 2: Write the Gradle files.**

`settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "walkpad-health"
include(":protocol", ":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

`gradle.properties`:
```
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
kotlin.code.style=official
```

`gradle/libs.versions.toml` (starting pins; if one fails to resolve, bump it to the latest stable on Google Maven / Maven Central and keep going):
```toml
[versions]
agp = "8.9.1"
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"
composeBom = "2025.01.00"
room = "2.6.1"
ble = "2.9.0"
healthConnect = "1.1.0"
work = "2.10.0"
coroutines = "1.9.0"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
coreKtx = "1.15.0"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-icons = { module = "androidx.compose.material:material-icons-extended" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
lifecycle-vm-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
ble = { module = "no.nordicsemi.android:ble", version.ref = "ble" }
health-connect = { module = "androidx.health.connect:connect-client", version.ref = "healthConnect" }
work = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`protocol/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
```

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
android {
    namespace = "org.walkpadhealth"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.walkpadhealth"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":protocol"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.vm.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.ble)
    implementation(libs.health.connect)
    implementation(libs.work)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.room.testing)
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
```

`app/src/main/AndroidManifest.xml` (minimal; extended in Task 6):
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Walkpad Health" android:allowBackup="false" />
</manifest>
```

`.gitignore`:
```
.gradle/
build/
local.properties
*.jks
*.keystore
.idea/
.DS_Store
```

- [ ] **Step 3: Wrapper, license, CI.**
```bash
cd "/Users/laptop/Documents/Coding projects/walkpad-health"
gradle wrapper --gradle-version 8.11.1
curl -fsSL https://www.gnu.org/licenses/gpl-3.0.txt -o LICENSE
```
`.github/workflows/ci.yml`:
```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- [ ] **Step 4: Sync the spec** to the three deviations above (edit spec sections 2, 4.2 and 8: replace "Nordic Kotlin BLE library" with "Nordic Android-BLE-Library", replace the ACRA rows with "custom crash handler + share sheet"). Add a `README.md` stub (name, one-line purpose, "work in progress").

- [ ] **Step 5: Verify the build.**
Run: `./gradlew :protocol:build :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If `:protocol:build` reports "no tests", that is fine.

- [ ] **Step 6: Commit.**
```bash
git add -A && git commit -m "chore: scaffold two-module Gradle project, CI, license"
```

---

### Task 1: Telemetry model and UrevoDriver

**Files:**
- Create: `protocol/src/main/kotlin/org/walkpadhealth/protocol/Telemetry.kt`, `UrevoDriver.kt`
- Test: `protocol/src/test/kotlin/org/walkpadhealth/protocol/UrevoDriverTest.kt`

**Interfaces:**
- Produces: `enum BeltStatus { IDLE, STOPPED, RUNNING, PAUSING, PAUSED, UNKNOWN }`; `data class Telemetry(status: BeltStatus, speedKmh: Double?, distanceM: Double? = null, steps: Int? = null, kcal: Double? = null)`; `object UrevoDriver { val handshakeFrames: List<ByteArray>; fun decodeFff1(frame: ByteArray): Telemetry? }`.

- [ ] **Step 1: Write the failing test.**
```kotlin
package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrevoDriverTest {
    // Synthetic frames built from PROTOCOL.md (5L/E1L family). Replace with real URTM059 captures in Task 7.
    private fun frame(status: Int, speedRaw: Int, size: Int): ByteArray = ByteArray(size).also {
        it[0] = 0x02; it[1] = 0x51; it[2] = status.toByte()
        it[3] = (speedRaw and 0xFF).toByte(); it[4] = ((speedRaw shr 8) and 0xFF).toByte()
        it[size - 1] = 0x03
    }

    @Test fun handshakeFramesAreExact() {
        assertContentEquals(byteArrayOf(0x02, 0x51, 0x0b, 0x03), UrevoDriver.handshakeFrames[0])
        assertContentEquals(byteArrayOf(0x02, 0x50, 0x03, 0x09, 0x03), UrevoDriver.handshakeFrames[1])
    }

    @Test fun idlePingHasStatusAndNoSpeed() {
        val t = UrevoDriver.decodeFff1(byteArrayOf(0x02, 0x51, 0x00, 0x00, 0x00, 0x03))!!
        assertEquals(BeltStatus.IDLE, t.status)
        assertNull(t.speedKmh)
    }

    @Test fun runningFrameDecodesSpeedInTenthsKmh() {
        val t = UrevoDriver.decodeFff1(frame(0x03, 20, 19))!!
        assertEquals(BeltStatus.RUNNING, t.status)
        assertEquals(2.0, t.speedKmh!!, 1e-9)
    }

    @Test fun speedIsLittleEndianU16() {
        assertEquals(6.0, UrevoDriver.decodeFff1(frame(0x03, 0x3c, 19))!!.speedKmh!!, 1e-9)
        assertEquals(25.6, UrevoDriver.decodeFff1(frame(0x03, 0x0100, 19))!!.speedKmh!!, 1e-9)
    }

    @Test fun twentyFiveByteFrameDecodesTheSame() {
        assertEquals(3.5, UrevoDriver.decodeFff1(frame(0x03, 35, 25))!!.speedKmh!!, 1e-9)
    }

    @Test fun statusMapping() {
        val expected = mapOf(
            0x00 to BeltStatus.IDLE, 0x01 to BeltStatus.STOPPED, 0x03 to BeltStatus.RUNNING,
            0x04 to BeltStatus.PAUSING, 0x0a to BeltStatus.PAUSED, 0x7f to BeltStatus.UNKNOWN,
        )
        for ((raw, status) in expected) assertEquals(status, UrevoDriver.decodeFff1(frame(raw, 0, 19))!!.status)
    }

    @Test fun eighteenByteFrameHasNoSpeed() {
        assertNull(UrevoDriver.decodeFff1(frame(0x03, 20, 18))!!.speedKmh)
    }

    @Test fun malformedFramesReturnNull() {
        assertNull(UrevoDriver.decodeFff1(ByteArray(0)))
        assertNull(UrevoDriver.decodeFff1(byteArrayOf(0x02)))
        assertNull(UrevoDriver.decodeFff1(byteArrayOf(0x02, 0x51, 0x03, 0x14, 0x00)))      // 5 bytes
        assertNull(UrevoDriver.decodeFff1(frame(0x03, 20, 19).also { it[1] = 0x50 }))      // wrong header
        assertNull(UrevoDriver.decodeFff1(frame(0x03, 20, 19).also { it[0] = 0x00 }))
    }
}
```

- [ ] **Step 2: Run to verify it fails.**
Run: `./gradlew :protocol:test --tests '*UrevoDriverTest'`
Expected: FAIL (compilation error, `UrevoDriver` unresolved).

- [ ] **Step 3: Implement.**

`Telemetry.kt`:
```kotlin
package org.walkpadhealth.protocol

enum class BeltStatus { IDLE, STOPPED, RUNNING, PAUSING, PAUSED, UNKNOWN }

data class Telemetry(
    val status: BeltStatus,
    val speedKmh: Double?,
    val distanceM: Double? = null,
    val steps: Int? = null,
    val kcal: Double? = null,
)
```

`UrevoDriver.kt`:
```kotlin
package org.walkpadhealth.protocol

object UrevoDriver {
    /** Written to fff2 after connecting; fff1 stays silent without them. */
    val handshakeFrames: List<ByteArray> = listOf(
        byteArrayOf(0x02, 0x51, 0x0b, 0x03),
        byteArrayOf(0x02, 0x50, 0x03, 0x09, 0x03),
    )

    private const val MIN_FRAME = 6
    private const val SPEED_FRAME = 19

    /** Returns null for anything that is not a well-formed `02 51 ...` frame. Never throws. */
    fun decodeFff1(frame: ByteArray): Telemetry? {
        if (frame.size < MIN_FRAME || frame[0] != 0x02.toByte() || frame[1] != 0x51.toByte()) return null
        val status = when (frame[2].toInt() and 0xFF) {
            0x00 -> BeltStatus.IDLE
            0x01 -> BeltStatus.STOPPED
            0x03 -> BeltStatus.RUNNING
            0x04 -> BeltStatus.PAUSING
            0x0a -> BeltStatus.PAUSED
            else -> BeltStatus.UNKNOWN
        }
        val speed = if (frame.size >= SPEED_FRAME) {
            ((frame[3].toInt() and 0xFF) or ((frame[4].toInt() and 0xFF) shl 8)) / 10.0
        } else null
        return Telemetry(status, speed)
    }
}
```

- [ ] **Step 4: Run to verify it passes.**
Run: `./gradlew :protocol:test --tests '*UrevoDriverTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit.**
```bash
git add -A && git commit -m "feat(protocol): decode fff1 telemetry frames"
```

---

### Task 2: Estimators

**Files:**
- Create: `protocol/src/main/kotlin/org/walkpadhealth/protocol/Estimators.kt`
- Test: `protocol/src/test/kotlin/org/walkpadhealth/protocol/EstimatorsTest.kt`

**Interfaces:**
- Produces: `object Estimators { fun kcal(distanceM: Double, durationSec: Double, weightKg: Double): Double; fun steps(distanceM: Double, heightCm: Double): Int }`. Both return 0 / 0.0 for non-finite or non-positive inputs.

- [ ] **Step 1: Write the failing test.**
```kotlin
package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class EstimatorsTest {
    @Test fun kcalMatchesAcsmWalkingEquation() {
        // 3 km/h for 60 min = 3000 m, 70 kg: (0.1*3000 + 3.5*60) * 70 * 0.005 = 178.5
        assertEquals(178.5, Estimators.kcal(3000.0, 3600.0, 70.0), 0.01)
    }

    @Test fun stepsUseStrideFromHeight() {
        // stride = 0.414 * 1.75 = 0.7245 m; 1000 / 0.7245 = 1380.26
        assertEquals(1380, Estimators.steps(1000.0, 175.0))
    }

    @Test fun badInputsGiveZeroNotNaN() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)
        for (b in bad) {
            assertEquals(0.0, Estimators.kcal(b, 3600.0, 70.0))
            assertEquals(0.0, Estimators.kcal(3000.0, b, 70.0))
            assertEquals(0.0, Estimators.kcal(3000.0, 3600.0, b))
            assertEquals(0, Estimators.steps(b, 175.0))
            assertEquals(0, Estimators.steps(1000.0, b))
        }
    }

    @Test fun zeroDistanceGivesZeroKcal() {
        assertEquals(0.0, Estimators.kcal(0.0, 3600.0, 70.0))   // no movement: not a walking session
    }
}
```
Zero distance returns 0.0 (no estimate), so a stuck belt never produces calories.

- [ ] **Step 2: Run to verify it fails.**
Run: `./gradlew :protocol:test --tests '*EstimatorsTest'`
Expected: FAIL (unresolved `Estimators`).

- [ ] **Step 3: Implement.**
```kotlin
package org.walkpadhealth.protocol

import kotlin.math.roundToInt

object Estimators {
    private fun ok(v: Double) = v.isFinite() && v > 0.0

    /** ACSM level-walking equation, gross kcal: VO2 = 0.1*S(m/min) + 3.5 ml/kg/min, 5 kcal per L O2. */
    fun kcal(distanceM: Double, durationSec: Double, weightKg: Double): Double {
        if (!ok(distanceM) || !ok(durationSec) || !ok(weightKg)) return 0.0
        val minutes = durationSec / 60.0
        return (0.1 * distanceM + 3.5 * minutes) * weightKg * 0.005
    }

    /** Stride = 0.414 x height. */
    fun steps(distanceM: Double, heightCm: Double): Int {
        if (!ok(distanceM) || !ok(heightCm)) return 0
        return (distanceM / (0.414 * heightCm / 100.0)).roundToInt()
    }
}
```

- [ ] **Step 4: Run to verify it passes.** `./gradlew :protocol:test --tests '*EstimatorsTest'` → PASS (4 tests).

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(protocol): step and calorie estimators"`

---

### Task 3: SessionTracker and finalize

**Files:**
- Create: `protocol/.../SessionSummary.kt`, `protocol/.../SessionTracker.kt`
- Test: `protocol/src/test/kotlin/org/walkpadhealth/protocol/SessionTrackerTest.kt`, `FinalizeTest.kt`

**Interfaces:**
- Consumes: `Telemetry`, `BeltStatus` (Task 1), `Estimators` (Task 2).
- Produces:
  - `enum class Source { DEVICE, ESTIMATED }`
  - `data class Profile(weightKg: Double, heightCm: Double) { companion object { val DEFAULT = Profile(70.0, 170.0) } }`
  - `data class SessionSummary(startMs: Long, endMs: Long, activeSec: Long, integratedDistanceM: Double, deviceDistanceM: Double?, deviceSteps: Int?, deviceKcal: Double?, wallStartMs: Long = 0L)`
  - `data class FinalSession(startMs, endMs, activeSec: Long, distanceM: Double, distanceSource: Source, steps: Int, stepsSource: Source, kcal: Double, kcalSource: Source)`
  - `fun SessionSummary.finalize(profile: Profile): FinalSession`
  - `data class Progress(activeSec: Long, distanceM: Double)`
  - `class SessionTracker(gapMs = 5_000, disconnectMs = 60_000, minActiveSec = 10)` with `isActive: Boolean`, `onTelemetry(t, nowMs): SessionSummary?`, `onDisconnect(nowMs)`, `tick(nowMs): SessionSummary?`, `finish(nowMs): SessionSummary?`, `progress(): Progress`.

- [ ] **Step 1: Write the failing tests.**

`SessionTrackerTest.kt`:
```kotlin
package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTrackerTest {
    private fun run(sp: Double? = 3.0) = Telemetry(BeltStatus.RUNNING, sp)
    private fun st(s: BeltStatus) = Telemetry(s, null)
    private fun SessionTracker.runFor(fromSec: Int, toSec: Int, sp: Double? = 3.0) {
        for (s in fromSec..toSec) onTelemetry(run(sp), s * 1000L)
    }

    @Test fun recordsARunningSession() {
        val t = SessionTracker()
        t.runFor(0, 60)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 61_000)!!
        assertEquals(0L, out.startMs)
        assertEquals(61_000L, out.endMs)
        assertEquals(60L, out.activeSec)
        assertEquals(50.0, out.integratedDistanceM, 1e-6)   // 3 km/h for 60 s
        assertFalse(t.isActive)
    }

    @Test fun idleFramesWithoutASessionDoNothing() {
        val t = SessionTracker()
        assertNull(t.onTelemetry(st(BeltStatus.IDLE), 0))
        assertNull(t.onTelemetry(st(BeltStatus.STOPPED), 1000))
        assertFalse(t.isActive)
    }

    @Test fun runningWithoutSpeedCountsTimeButNoDistance() {
        val t = SessionTracker()
        t.runFor(0, 20, sp = null)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertEquals(20L, out.activeSec)
        assertEquals(0.0, out.integratedDistanceM)
    }

    @Test fun pauseDoesNotAccrue() {
        val t = SessionTracker()
        t.runFor(0, 10)
        for (s in 11..20) t.onTelemetry(st(BeltStatus.PAUSED), s * 1000L)
        t.runFor(21, 30)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 31_000)!!
        assertEquals(19L, out.activeSec)
        assertEquals(19 * 3.0 / 3.6, out.integratedDistanceM, 1e-6)
    }

    @Test fun gapOverFiveSecondsIsNotIntegrated() {
        val t = SessionTracker()
        t.onTelemetry(run(), 0); t.onTelemetry(run(), 1_000)
        t.onTelemetry(run(), 20_000)                      // 19 s gap: skipped
        t.onTelemetry(run(), 21_000)
        t.runFor(22, 30)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 31_000)!!
        assertEquals(1L + 1L + 9L, out.activeSec)
    }

    @Test fun shortAccidentalRunIsDiscarded() {
        val t = SessionTracker()
        t.runFor(0, 5)
        assertNull(t.onTelemetry(st(BeltStatus.STOPPED), 6_000))
        assertFalse(t.isActive)
    }

    @Test fun disconnectOfSixtySecondsEndsTheSessionAtDisconnectTime() {
        val t = SessionTracker()
        t.runFor(0, 30)
        t.onDisconnect(31_000)
        assertNull(t.tick(90_999))
        val out = t.tick(91_000)!!
        assertEquals(31_000L, out.endMs)
        assertEquals(30L, out.activeSec)
        assertFalse(t.isActive)
    }

    @Test fun reconnectWithinSixtySecondsResumesTheSameSession() {
        val t = SessionTracker()
        t.runFor(0, 30)
        t.onDisconnect(31_000)
        t.onTelemetry(run(), 50_000)                      // reconnect: gap not integrated
        t.runFor(51, 60)
        assertNull(t.tick(500_000))                       // no disconnect pending any more
        assertTrue(t.isActive)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 61_000)!!
        assertEquals(40L, out.activeSec)
    }

    @Test fun deviceTotalsAreSessionDeltasFromTheStartFrame() {
        val t = SessionTracker()
        t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 100.0, steps = 200, kcal = 10.0), 0)
        for (s in 1..20) t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 100.0 + s * 1.5, steps = 200 + s * 2, kcal = 10.0 + s), s * 1000L)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertEquals(30.0, out.deviceDistanceM)
        assertEquals(40, out.deviceSteps)
        assertEquals(20.0, out.deviceKcal)
    }

    @Test fun counterThatWentDownIsTreatedAsReset() {
        val t = SessionTracker()
        t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 100.0), 0)
        for (s in 1..20) t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 5.0), s * 1000L)
        assertEquals(5.0, t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!.deviceDistanceM)
    }

    @Test fun nonFiniteDeviceValuesAreDropped() {
        val t = SessionTracker()
        t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = 0.0), 0)
        for (s in 1..20) t.onTelemetry(Telemetry(BeltStatus.RUNNING, 3.0, distanceM = Double.POSITIVE_INFINITY, kcal = Double.NaN), s * 1000L)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertNull(out.deviceDistanceM); assertNull(out.deviceKcal)
    }

    @Test fun distanceUsesThePreviousSpeedOverEachInterval() {
        val t = SessionTracker(minActiveSec = 1)
        t.onTelemetry(run(2.0), 0); t.onTelemetry(run(4.0), 1_000); t.onTelemetry(run(4.0), 2_000)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 3_000)!!
        assertEquals((2.0 + 4.0) / 3.6, out.integratedDistanceM, 1e-9)
    }

    @Test fun frameAtExactlySixtySecondsAfterDisconnectEndsOldSessionAndStartsANewOne() {
        val t = SessionTracker()
        t.runFor(0, 30)
        t.onDisconnect(31_000)
        val old = t.onTelemetry(run(), 91_000)!!
        assertEquals(31_000L, old.endMs); assertEquals(30L, old.activeSec)
        assertTrue(t.isActive)                           // the new walk is its own session
        assertEquals(Progress(0, 0.0), t.progress())
    }

    @Test fun summaryCarriesTheWallClockStartCapturedAtBegin() {
        var wall = 1_700_000_000_000L
        val t = SessionTracker(wallClock = { wall })
        t.onTelemetry(run(), 0)
        wall += 999_999                                   // wall clock jumps mid-walk; the stored start must not move
        for (s in 1..20) t.onTelemetry(run(), s * 1000L)
        val out = t.onTelemetry(st(BeltStatus.STOPPED), 21_000)!!
        assertEquals(1_700_000_000_000L, out.wallStartMs)
        assertEquals(21_000L, out.endMs - out.startMs)
    }

    @Test fun progressTracksTheOpenSession() {
        val t = SessionTracker()
        assertEquals(Progress(0, 0.0), t.progress())
        t.runFor(0, 12)
        assertEquals(12L, t.progress().activeSec)
    }

    @Test fun finishEndsAnOpenSessionAndIsNullOtherwise() {
        val t = SessionTracker()
        assertNull(t.finish(0))
        t.runFor(0, 30)
        assertNotNull(t.finish(31_000))
        assertFalse(t.isActive)
    }

    @Test fun tickWithoutDisconnectIsANoOp() {
        val t = SessionTracker()
        t.runFor(0, 30)
        assertNull(t.tick(1_000_000))
        assertTrue(t.isActive)
    }
}
```

`FinalizeTest.kt`:
```kotlin
package org.walkpadhealth.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class FinalizeTest {
    private fun sum(devDist: Double? = null, devSteps: Int? = null, devKcal: Double? = null) =
        SessionSummary(0, 3_600_000, 3600, 3000.0, devDist, devSteps, devKcal)

    @Test fun estimatesEverythingWhenPadReportsNothing() {
        val f = sum().finalize(Profile(70.0, 175.0))
        assertEquals(3000.0, f.distanceM); assertEquals(Source.ESTIMATED, f.distanceSource)
        assertEquals(4141, f.steps);       assertEquals(Source.ESTIMATED, f.stepsSource)   // 3000 / 0.7245
        assertEquals(178.5, f.kcal, 0.01); assertEquals(Source.ESTIMATED, f.kcalSource)
    }

    @Test fun deviceValuesWin() {
        val f = sum(devDist = 2500.0, devSteps = 3000, devKcal = 200.0).finalize(Profile.DEFAULT)
        assertEquals(2500.0, f.distanceM); assertEquals(Source.DEVICE, f.distanceSource)
        assertEquals(3000, f.steps);       assertEquals(Source.DEVICE, f.stepsSource)
        assertEquals(200.0, f.kcal);       assertEquals(Source.DEVICE, f.kcalSource)
    }

    @Test fun zeroDeviceDistanceFallsBackToIntegrated() {
        val f = sum(devDist = 0.0).finalize(Profile.DEFAULT)
        assertEquals(3000.0, f.distanceM); assertEquals(Source.ESTIMATED, f.distanceSource)
    }

    @Test fun infiniteDeviceValuesAreIgnored() {
        val f = sum(devDist = Double.POSITIVE_INFINITY, devKcal = Double.POSITIVE_INFINITY).finalize(Profile(70.0, 175.0))
        assertEquals(3000.0, f.distanceM); assertEquals(Source.ESTIMATED, f.distanceSource)
        assertEquals(Source.ESTIMATED, f.kcalSource)
    }

    @Test fun badProfileGivesZeroEstimatesNotNaN() {
        val f = sum().finalize(Profile(Double.NaN, -5.0))
        assertEquals(0, f.steps); assertEquals(0.0, f.kcal)
    }
}
```
(3000 m / 0.7245 m = 4140.8, rounded to 4141.)

- [ ] **Step 2: Run to verify they fail.** `./gradlew :protocol:test --tests '*SessionTrackerTest' --tests '*FinalizeTest'` → FAIL (unresolved references).

- [ ] **Step 3: Implement.**

`SessionSummary.kt`:
```kotlin
package org.walkpadhealth.protocol

enum class Source { DEVICE, ESTIMATED }

data class Profile(val weightKg: Double, val heightCm: Double) {
    companion object { val DEFAULT = Profile(70.0, 170.0) }
}

data class SessionSummary(
    val startMs: Long,
    val endMs: Long,
    val activeSec: Long,
    val integratedDistanceM: Double,
    val deviceDistanceM: Double?,
    val deviceSteps: Int?,
    val deviceKcal: Double?,
    val wallStartMs: Long = 0L,   // epoch time at the start frame; startMs/endMs are monotonic
)

data class FinalSession(
    val startMs: Long,
    val endMs: Long,
    val activeSec: Long,
    val distanceM: Double,
    val distanceSource: Source,
    val steps: Int,
    val stepsSource: Source,
    val kcal: Double,
    val kcalSource: Source,
)

fun SessionSummary.finalize(profile: Profile): FinalSession {
    val devDist = deviceDistanceM?.takeIf { it.isFinite() && it > 0.0 }
    val dist = devDist ?: integratedDistanceM.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val steps = deviceSteps?.takeIf { it > 0 }
    val kcal = deviceKcal?.takeIf { it.isFinite() && it > 0.0 }
    return FinalSession(
        startMs, endMs, activeSec,
        dist, if (devDist != null) Source.DEVICE else Source.ESTIMATED,
        steps ?: Estimators.steps(dist, profile.heightCm), if (steps != null) Source.DEVICE else Source.ESTIMATED,
        kcal ?: Estimators.kcal(dist, activeSec.toDouble(), profile.weightKg), if (kcal != null) Source.DEVICE else Source.ESTIMATED,
    )
}
```

`SessionTracker.kt`:
```kotlin
package org.walkpadhealth.protocol

data class Progress(val activeSec: Long, val distanceM: Double)

/** Pure state machine. Not thread-safe: the caller must serialize all calls on one thread. Times are milliseconds on a monotonic clock the caller supplies (the service uses `SystemClock.elapsedRealtime()`); the caller converts summary times to epoch for storage. */
class SessionTracker(
    private val gapMs: Long = 5_000,
    private val disconnectMs: Long = 60_000,
    private val minActiveSec: Long = 10,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private data class Totals(val dist: Double? = null, val steps: Int? = null, val kcal: Double? = null)

    private var startMs: Long? = null
    private var wallStartMs = 0L
    private var lastMs = 0L
    private var lastStatus = BeltStatus.IDLE
    private var lastSpeedKmh: Double? = null
    private var activeMs = 0L
    private var integratedM = 0.0
    private var base = Totals()
    private var latest = Totals()
    private var disconnectedAt: Long? = null

    val isActive: Boolean get() = startMs != null

    fun progress() = Progress(activeMs / 1000, integratedM)

    fun onTelemetry(t: Telemetry, nowMs: Long): SessionSummary? {
        val expired = tick(nowMs)            // an expired disconnect ends the old session before this frame is considered
        disconnectedAt = null
        val open = startMs != null
        if (open) {
            val dt = nowMs - lastMs
            if (lastStatus == BeltStatus.RUNNING && t.status == BeltStatus.RUNNING && dt in 1..gapMs) {
                activeMs += dt
                lastSpeedKmh?.let { integratedM += it / 3.6 * dt / 1000.0 }   // previous speed held over the interval
            }
            latest = Totals(t.distanceM ?: latest.dist, t.steps ?: latest.steps, t.kcal ?: latest.kcal)
        }
        lastMs = nowMs
        lastStatus = t.status
        lastSpeedKmh = t.speedKmh
        val ended = when {
            !open && t.status == BeltStatus.RUNNING -> { begin(nowMs, t); null }
            open && (t.status == BeltStatus.STOPPED || t.status == BeltStatus.IDLE) -> end(nowMs)
            else -> null
        }
        return ended ?: expired
    }

    fun onDisconnect(nowMs: Long) {
        if (startMs != null && disconnectedAt == null) disconnectedAt = nowMs
    }

    fun tick(nowMs: Long): SessionSummary? {
        val d = disconnectedAt ?: return null
        return if (nowMs - d >= disconnectMs) end(d) else null
    }

    fun finish(nowMs: Long): SessionSummary? = if (startMs != null) end(nowMs) else null

    private fun begin(nowMs: Long, t: Telemetry) {
        startMs = nowMs; wallStartMs = wallClock(); activeMs = 0; integratedM = 0.0
        base = Totals(t.distanceM, t.steps, t.kcal); latest = base
    }

    private fun delta(b: Double?, l: Double?): Double? = when {
        l == null || !l.isFinite() -> null
        b != null && b.isFinite() && l >= b -> l - b
        else -> l                                  // counter reset: the latest value is the session value
    }

    private fun end(endMs: Long): SessionSummary? {
        val s = startMs ?: return null
        val summary = SessionSummary(
            s, endMs, activeMs / 1000, integratedM,
            delta(base.dist, latest.dist),
            delta(base.steps?.toDouble(), latest.steps?.toDouble())?.toInt(),
            delta(base.kcal, latest.kcal),
            wallStartMs,
        )
        startMs = null; disconnectedAt = null; activeMs = 0; integratedM = 0.0
        base = Totals(); latest = Totals()
        return if (summary.activeSec >= minActiveSec) summary else null
    }
}
```
The start frame's device values are the baseline; the stored device value is `latest - baseline`, so cumulative pad counters give per-session numbers.

- [ ] **Step 4: Run to verify they pass.** `./gradlew :protocol:test` → PASS (all protocol tests).

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(protocol): session tracker and finalize"`

---

### Task 4: Room storage

**Files:**
- Create: `app/src/main/kotlin/org/walkpadhealth/data/{Entities.kt,Daos.kt,AppDb.kt,Mapping.kt}`
- Test: `app/src/test/kotlin/org/walkpadhealth/MappingTest.kt`, `app/src/androidTest/kotlin/org/walkpadhealth/DaoTest.kt`

**Interfaces:**
- Consumes: `FinalSession`, `Source`, `Profile` (Task 3).
- Produces: `SessionEntity(id: Long = 0, startMs, endMs, activeSec, distanceM, distanceSource: String, steps, stepsSource: String, kcal, kcalSource: String, synced: Boolean = false)`; `ProfileEntity(id: Int = 1, weightKg: Double, heightCm: Double)`; `SessionDao { insert, observeAll, unsynced, markSynced }`; `ProfileDao { observe(): Flow<ProfileEntity?>, get(): ProfileEntity?, upsert }`; `AppDb.get(ctx)`; `FinalSession.toEntity()`; `ProfileEntity.toProfile()`.

- [ ] **Step 1: Write the failing JVM test** `MappingTest.kt`:
```kotlin
package org.walkpadhealth

import org.walkpadhealth.data.toEntity
import org.walkpadhealth.protocol.FinalSession
import org.walkpadhealth.protocol.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MappingTest {
    @Test fun mapsFinalSessionToUnsyncedEntityWithSourceNames() {
        val e = FinalSession(1, 2, 3, 4.0, Source.DEVICE, 5, Source.ESTIMATED, 6.0, Source.ESTIMATED).toEntity()
        assertEquals("DEVICE", e.distanceSource)
        assertEquals("ESTIMATED", e.stepsSource)
        assertEquals(0L, e.id)
        assertFalse(e.synced)
        assertEquals(5, e.steps)
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testDebugUnitTest --tests '*MappingTest'` → FAIL (unresolved).

- [ ] **Step 3: Implement.**

`Entities.kt`:
```kotlin
package org.walkpadhealth.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val activeSec: Long,
    val distanceM: Double,
    val distanceSource: String,
    val steps: Int,
    val stepsSource: String,
    val kcal: Double,
    val kcalSource: String,
    val synced: Boolean = false,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val weightKg: Double,
    val heightCm: Double,
)
```

`Daos.kt`:
```kotlin
package org.walkpadhealth.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Query("SELECT * FROM sessions ORDER BY startMs DESC") fun observeAll(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE synced = 0 ORDER BY startMs") suspend fun unsynced(): List<SessionEntity>
    @Query("UPDATE sessions SET synced = 1 WHERE id = :id") suspend fun markSynced(id: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun observe(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE id = 1") suspend fun get(): ProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: ProfileEntity)
}
```

`AppDb.kt`:
```kotlin
package org.walkpadhealth.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class, ProfileEntity::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun profile(): ProfileDao

    companion object {
        @Volatile private var inst: AppDb? = null
        /** Last saved profile, so the service can show live estimates without a DB read. */
        @Volatile var cachedProfile: org.walkpadhealth.protocol.Profile? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "walkpad.db").build().also { inst = it }
        }
    }
}
```

`Mapping.kt`:
```kotlin
package org.walkpadhealth.data

import org.walkpadhealth.protocol.FinalSession
import org.walkpadhealth.protocol.Profile

fun FinalSession.toEntity() = SessionEntity(
    startMs = startMs, endMs = endMs, activeSec = activeSec,
    distanceM = distanceM, distanceSource = distanceSource.name,
    steps = steps, stepsSource = stepsSource.name,
    kcal = kcal, kcalSource = kcalSource.name,
)

fun ProfileEntity.toProfile() = Profile(weightKg, heightCm)
```

- [ ] **Step 4: Run to verify it passes.** `./gradlew :app:testDebugUnitTest --tests '*MappingTest'` → PASS.

- [ ] **Step 5: Add the instrumented DAO test** `DaoTest.kt` (runs on a connected phone: `./gradlew :app:connectedDebugAndroidTest`; the owner runs this if adb is unavailable to the agent):
```kotlin
package org.walkpadhealth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.walkpadhealth.data.AppDb
import org.walkpadhealth.data.ProfileEntity
import org.walkpadhealth.data.SessionEntity
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDb
    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    private fun s(start: Long) = SessionEntity(startMs = start, endMs = start + 1, activeSec = 60, distanceM = 50.0,
        distanceSource = "ESTIMATED", steps = 70, stepsSource = "ESTIMATED", kcal = 3.0, kcalSource = "ESTIMATED")

    @Test fun unsyncedReturnsOnlyUnsyncedInOrderAndMarkSyncedRemovesOne() = runBlocking {
        val a = db.sessions().insert(s(2)); db.sessions().insert(s(1))
        assertEquals(listOf(1L, 2L), db.sessions().unsynced().map { it.startMs })
        db.sessions().markSynced(a)
        assertEquals(listOf(1L), db.sessions().unsynced().map { it.startMs })
    }

    @Test fun profileUpsertReplacesSingleRow() = runBlocking {
        assertNull(db.profile().get())
        db.profile().upsert(ProfileEntity(weightKg = 70.0, heightCm = 170.0))
        db.profile().upsert(ProfileEntity(weightKg = 80.0, heightCm = 180.0))
        assertEquals(80.0, db.profile().get()!!.weightKg)
    }
}
```

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(data): Room sessions and profile"`

---

### Task 5: BLE transport and raw frame log

**Files:**
- Create: `app/src/main/kotlin/org/walkpadhealth/ble/PadManager.kt`, `ble/FrameLog.kt`, `AppPrefs.kt`

**Interfaces:**
- Consumes: `UrevoDriver.handshakeFrames` (Task 1).
- Produces:
  - `class AppPrefs(ctx)` with `var autoRecord: Boolean = true`, `var rawLog: Boolean = false`, `var crashOffer: Boolean = true`, `var padAddress: String? = null`.
  - `class FrameLog(dir: File, maxBytes: Long = 5_000_000)` with `fun append(source: String, bytes: ByteArray)`, `fun file(): File`, `fun clear()`. Line format: `<epochMs>\t<source>\t<hex>`.
  - `class PadManager(ctx, onFff1: (ByteArray)->Unit, onFtms: (ByteArray)->Unit, onConnection: (Boolean)->Unit) : BleManager` with `fun connectTo(device: BluetoothDevice)`.

- [ ] **Step 1: Write a failing JVM test** for the pure part of `FrameLog` in `app/src/test/kotlin/org/walkpadhealth/FrameLogTest.kt`:
```kotlin
package org.walkpadhealth

import org.walkpadhealth.ble.FrameLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameLogTest {
    private fun tmp() = Files.createTempDirectory("fl").toFile()

    @Test fun appendsHexLinesWithSource() {
        val log = FrameLog(tmp())
        log.append("fff1", byteArrayOf(0x02, 0x51, 0x0a))
        val parts = log.file().readLines().single().split('\t')
        assertEquals("fff1", parts[1]); assertEquals("02510a", parts[2])
    }

    @Test fun highBytesAreNotSignExtended() {
        val log = FrameLog(tmp())
        log.append("fff1", byteArrayOf(0x80.toByte(), 0xff.toByte(), 0x00))
        assertEquals("80ff00", log.file().readLines().single().split('\t')[2])
    }

    @Test fun truncatesOldestHalfWhenOverTheCap() {
        val log = FrameLog(tmp(), maxBytes = 2_000)
        repeat(200) { log.append("fff1", ByteArray(8) { it.toByte() }) }
        assertTrue(log.file().length() <= 2_000, "size ${log.file().length()}")
        assertTrue(log.file().readLines().isNotEmpty())
    }

    @Test fun clearEmptiesTheFile() {
        val log = FrameLog(tmp()); log.append("fff1", byteArrayOf(1)); log.clear()
        assertEquals(0L, log.file().length())
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testDebugUnitTest --tests '*FrameLogTest'` → FAIL (unresolved).

- [ ] **Step 3: Implement.**

`ble/FrameLog.kt`:
```kotlin
package org.walkpadhealth.ble

import java.io.File

/** Append-only hex log of raw BLE frames, used to map the pad's fields (Phase 0) and for bug reports. */
class FrameLog(dir: File, private val maxBytes: Long = 5_000_000) {
    private val f = File(dir.apply { mkdirs() }, "frames.log")

    fun file(): File = f

    @Synchronized fun append(source: String, bytes: ByteArray) {
        f.appendText("${System.currentTimeMillis()}\t$source\t${bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}\n")
        if (f.length() > maxBytes) {
            val lines = f.readLines()
            f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized fun clear() { f.writeText("") }
}
```

`AppPrefs.kt`:
```kotlin
package org.walkpadhealth

import android.content.Context

class AppPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var autoRecord: Boolean
        get() = p.getBoolean("auto", true); set(v) = p.edit().putBoolean("auto", v).apply()
    var rawLog: Boolean
        get() = p.getBoolean("rawlog", false); set(v) = p.edit().putBoolean("rawlog", v).apply()
    var crashOffer: Boolean
        get() = p.getBoolean("crash", true); set(v) = p.edit().putBoolean("crash", v).apply()
    /** MAC of the paired pad; connect directly when known, scan only when null. */
    var padAddress: String?
        get() = p.getString("pad", null); set(v) = p.edit().putString("pad", v).apply()
}
```

`ble/PadManager.kt`:
```kotlin
package org.walkpadhealth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import org.walkpadhealth.protocol.UrevoDriver
import java.util.UUID

private fun u16(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")
private val FFF0 = u16("fff0"); private val FFF1 = u16("fff1"); private val FFF2 = u16("fff2")
private val FTMS = u16("1826"); private val TREADMILL_DATA = u16("2acd")

@SuppressLint("MissingPermission") // callers verify BLUETOOTH_CONNECT before constructing
class PadManager(
    ctx: Context,
    private val onFff1: (ByteArray) -> Unit,
    private val onFtms: (ByteArray) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) : BleManager(ctx) {
    private var fff1: BluetoothGattCharacteristic? = null
    private var fff2: BluetoothGattCharacteristic? = null
    private var ftms: BluetoothGattCharacteristic? = null

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {}
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = onConnection(false)
            override fun onDeviceReady(device: BluetoothDevice) = onConnection(true)
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = onConnection(false)
        })
    }

    fun connectTo(device: BluetoothDevice) {
        connect(device).retry(3, 200).useAutoConnect(false).timeout(15_000).enqueue()
    }

    override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val s = gatt.getService(FFF0) ?: return false
            fff1 = s.getCharacteristic(FFF1); fff2 = s.getCharacteristic(FFF2)
            ftms = gatt.getService(FTMS)?.getCharacteristic(TREADMILL_DATA)
            return fff1 != null && fff2 != null
        }

        override fun initialize() {
            // Nordic's request queue runs these in order, so the handshake below is sent only after fff1 is subscribed.
            setNotificationCallback(fff1).with { _, d -> d.value?.let(onFff1) }
            enableNotifications(fff1).enqueue()
            ftms?.let { c ->
                setNotificationCallback(c).with { _, d -> d.value?.let(onFtms) }
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) enableNotifications(c).enqueue()
                else if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) enableIndications(c).enqueue()
            }
            UrevoDriver.handshakeFrames.forEach {
                writeCharacteristic(fff2, it, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
            }
        }

        override fun onServicesInvalidated() { fff1 = null; fff2 = null; ftms = null }
    }
}
```
If `writeCharacteristic` with `WRITE_TYPE_DEFAULT` is rejected by the pad (no frames arrive on `fff1`), switch to `WRITE_TYPE_NO_RESPONSE`; `fff2` supports both. Record which one works in `PROTOCOL.md` (Task 11).

- [ ] **Step 4: Run to verify it passes.** `./gradlew :app:testDebugUnitTest --tests '*FrameLogTest' && ./gradlew :app:assembleDebug` → PASS / BUILD SUCCESSFUL. Fix any API mismatches against the pinned Nordic version (compile errors are the signal).

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(ble): pad manager, frame log, prefs"`

---

### Task 6: Foreground service, manifest, permissions

**Files:**
- Create: `app/src/main/kotlin/org/walkpadhealth/{LiveState.kt,WalkpadApp.kt,service/WalkService.kt,service/BootReceiver.kt}`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `PadManager`, `FrameLog`, `AppPrefs` (Task 5), `SessionTracker`, `UrevoDriver`, `Profile`, `finalize` (Tasks 1-3), `AppDb`, `toEntity`, `toProfile` (Task 4).
- Produces: `enum class Problem { NONE, BLUETOOTH_OFF, PERMISSION, SCAN_FAILED }`; `data class Live(problem: Problem = NONE, connected: Boolean = false, status: BeltStatus = IDLE, speedKmh: Double = 0.0, activeSec: Long = 0, distanceM: Double = 0.0, steps: Int = 0, kcal: Double = 0.0)`; `object LiveState { val flow: MutableStateFlow<Live> }`; `WalkService` (start with `ContextCompat.startForegroundService`); `SyncScheduler.enqueue(ctx)` is called here and defined in Task 8 (until then it is a stub in this task, replaced in Task 8).

- [ ] **Step 1: Manifest.** Replace `AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.health.WRITE_EXERCISE" />
    <uses-permission android:name="android.permission.health.WRITE_STEPS" />
    <uses-permission android:name="android.permission.health.WRITE_DISTANCE" />
    <uses-permission android:name="android.permission.health.WRITE_TOTAL_CALORIES_BURNED" />

    <queries><package android:name="com.google.android.apps.healthdata" /></queries>

    <application
        android:name=".WalkpadApp"
        android:label="Walkpad Health"
        android:allowBackup="false"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>
        </activity>
        <activity-alias
            android:name="ViewPermissionUsageActivity"
            android:exported="true"
            android:targetActivity=".MainActivity"
            android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
            <intent-filter>
                <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
                <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
            </intent-filter>
        </activity-alias>

        <service android:name=".service.WalkService" android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <receiver android:name=".service.BootReceiver" android:exported="false">
            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
        </receiver>

        <provider android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files" android:exported="false" android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```
Create `app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths><files-path name="raw" path="raw/" /><files-path name="crash" path="crash/" /></paths>
```

- [ ] **Step 2: `LiveState.kt` and `WalkpadApp.kt`.**
```kotlin
package org.walkpadhealth

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import org.walkpadhealth.protocol.BeltStatus

enum class Problem { NONE, BLUETOOTH_OFF, PERMISSION, SCAN_FAILED }

data class Live(
    val problem: Problem = Problem.NONE,
    val connected: Boolean = false,
    val status: BeltStatus = BeltStatus.IDLE,
    val speedKmh: Double = 0.0,
    val activeSec: Long = 0,
    val distanceM: Double = 0.0,
    val steps: Int = 0,
    val kcal: Double = 0.0,
)

object LiveState { val flow = MutableStateFlow(Live()) }

class WalkpadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        org.walkpadhealth.crash.CrashReporter.install(this)   // defined in Task 10; stub until then
    }
}
```
Until Task 10, create `crash/CrashReporter.kt` with `object CrashReporter { fun install(app: android.app.Application) {} }` and replace it in Task 10. Likewise create `health/SyncScheduler.kt` with `object SyncScheduler { fun enqueue(ctx: android.content.Context) {} }` and replace it in Task 8.

- [ ] **Step 3: `WalkService.kt`.** All BLE callbacks are re-posted to the main handler, so the tracker, the ticker and persistence run on one thread. Callers start the service only through `WalkService.sync`, which checks the Bluetooth permission first.
```kotlin
package org.walkpadhealth.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.Live
import org.walkpadhealth.LiveState
import org.walkpadhealth.Problem
import org.walkpadhealth.R
import org.walkpadhealth.ble.FrameLog
import org.walkpadhealth.ble.PadManager
import org.walkpadhealth.data.AppDb
import org.walkpadhealth.data.toEntity
import org.walkpadhealth.data.toProfile
import org.walkpadhealth.health.SyncScheduler
import org.walkpadhealth.protocol.BeltStatus
import org.walkpadhealth.protocol.Estimators
import org.walkpadhealth.protocol.Profile
import org.walkpadhealth.protocol.SessionSummary
import org.walkpadhealth.protocol.SessionTracker
import org.walkpadhealth.protocol.UrevoDriver
import org.walkpadhealth.protocol.finalize
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.walkpadhealth.protocol.FinalSession

@SuppressLint("MissingPermission") // btGranted() is checked in onStartCommand before any BLE call
class WalkService : Service() {
    companion object {
        fun btGranted(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 31)
                listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                    .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
            else ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Single entry point for the UI, the boot receiver and the auto-record toggle. */
        fun sync(ctx: Context, prefs: AppPrefs) {
            val i = Intent(ctx, WalkService::class.java)
            if (prefs.autoRecord && btGranted(ctx)) ContextCompat.startForegroundService(ctx, i)
            else {
                ctx.stopService(i)
                LiveState.flow.value = Live(problem = if (prefs.autoRecord) Problem.PERMISSION else Problem.NONE)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = SessionTracker()
    private lateinit var prefs: AppPrefs
    private lateinit var log: FrameLog
    private var manager: PadManager? = null
    private var scanning = false
    private var connecting = false
    private var pendingAddress: String? = null     // saved as the paired pad only after the GATT services validate
    private var scanRetries = 0
    private var tickCount = 0
    private var last = Live()
    private val unsaved = ConcurrentLinkedQueue<FinalSession>()
    private val flushLock = Mutex()

    private val ticker = object : Runnable {
        override fun run() {
            tracker.tick(SystemClock.elapsedRealtime())?.let(::persist)
            if (++tickCount % 30 == 0 && unsaved.isNotEmpty()) flushUnsaved()
            publish(); main.postDelayed(this, 1000)
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { main.post { handleScanResult(result) } }
        override fun onScanFailed(errorCode: Int) { main.post { scanning = false; handleScanFailed() } }
    }

    private fun handleScanResult(result: ScanResult) {
        if (connecting) return
        val name = result.scanRecord?.deviceName ?: result.device.name
        if (name?.startsWith("URTM") == true) { stopScan(); connect(result.device) }
    }

    /** Bounded retry: five attempts 10 s apart, then a visible SCAN_FAILED state until auto-record is toggled. */
    private fun handleScanFailed() {
        if (++scanRetries > 5) { last = last.copy(problem = Problem.SCAN_FAILED); publish(); return }
        main.postDelayed({ findPad() }, 10_000)
    }

    private fun connect(device: BluetoothDevice) {
        if (connecting) return
        connecting = true; pendingAddress = device.address
        manager?.connectTo(device)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!btGranted(this)) { LiveState.flow.value = Live(problem = Problem.PERMISSION); stopSelf(); return START_NOT_STICKY }
        try { startInForeground() } catch (e: SecurityException) { LiveState.flow.value = Live(problem = Problem.PERMISSION); stopSelf(); return START_NOT_STICKY }
        prefs = AppPrefs(this)
        log = FrameLog(File(filesDir, "raw"))
        scope.launch { AppDb.cachedProfile = AppDb.get(this@WalkService).profile().get()?.toProfile() }
        if (manager == null) {
            manager = PadManager(this,
                { b -> main.post { handleFff1(b) } }, { b -> main.post { handleFtms(b) } }, { c -> main.post { handleConnection(c) } })
            main.post(ticker)
            findPad()
        }
        return START_STICKY
    }

    private fun handleFff1(bytes: ByteArray) {
        if (prefs.rawLog) log.append("fff1", bytes)
        val t = UrevoDriver.decodeFff1(bytes) ?: return
        tracker.onTelemetry(t, SystemClock.elapsedRealtime())?.let(::persist)
        last = last.copy(status = t.status, speedKmh = if (t.status == BeltStatus.RUNNING) (t.speedKmh ?: 0.0) else 0.0)
        publish()
    }

    private fun handleFtms(bytes: ByteArray) { if (prefs.rawLog) log.append("2acd", bytes) }

    private fun handleConnection(connected: Boolean) {
        connecting = false
        if (connected) { scanRetries = 0; pendingAddress?.let { prefs.padAddress = it } }   // onDeviceReady: services validated
        last = last.copy(connected = connected)
        if (!connected) {
            tracker.onDisconnect(SystemClock.elapsedRealtime())
            last = last.copy(status = BeltStatus.IDLE, speedKmh = 0.0)
            main.postDelayed({ findPad() }, 5_000)
        }
        publish()
    }

    /** Connect straight to the stored address, otherwise scan. Retries every 5 s while Bluetooth is off. */
    private fun findPad() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            last = last.copy(problem = Problem.BLUETOOTH_OFF); publish(); main.postDelayed({ findPad() }, 5_000); return
        }
        last = last.copy(problem = Problem.NONE)
        val addr = prefs.padAddress
        if (addr != null && BluetoothAdapter.checkBluetoothAddress(addr)) connect(adapter.getRemoteDevice(addr)) else startScan(adapter)
    }

    private fun startScan(adapter: BluetoothAdapter) {
        if (scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString("00001826-0000-1000-8000-00805f9b34fb"))).build()
        adapter.bluetoothLeScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCb)
        scanning = true
    }

    private fun stopScan() {
        if (!scanning) return
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(scanCb)
        scanning = false
    }

    private fun persist(s: SessionSummary) {
        // Tracker times are monotonic. Stored start is the epoch time captured when the session began; end = start + monotonic duration.
        val profile = AppDb.cachedProfile ?: Profile.DEFAULT
        unsaved.add(s.copy(startMs = s.wallStartMs, endMs = s.wallStartMs + (s.endMs - s.startMs)).finalize(profile))
        flushUnsaved()
    }

    /**
     * Inserts queued sessions in order. A failed insert (for example storage full) leaves the session queued, shows a notification,
     * and is retried every 30 s. A process death while storage is full loses the queued sessions; that cannot be avoided without storage.
     */
    private fun flushUnsaved() {
        scope.launch {
            flushLock.withLock {
                val db = AppDb.get(this@WalkService)
                while (true) {
                    val next = unsaved.peek() ?: break
                    try { db.sessions().insert(next.toEntity()); unsaved.poll() }
                    catch (e: Exception) { notifyStorageFull(); return@launch }
                }
            }
            SyncScheduler.enqueue(this@WalkService)
        }
    }

    private fun notifyStorageFull() {
        val n = NotificationCompat.Builder(this, "walk").setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.storage_full)).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun publish() {
        val p = tracker.progress()
        val prof = AppDb.cachedProfile ?: Profile.DEFAULT
        LiveState.flow.value = last.copy(
            activeSec = p.activeSec, distanceM = p.distanceM,
            steps = Estimators.steps(p.distanceM, prof.heightCm), kcal = Estimators.kcal(p.distanceM, p.activeSec.toDouble(), prof.weightKg),
        )
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("walk", getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "walk")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text)).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopScan()
        tracker.finish(SystemClock.elapsedRealtime())?.let(::persist)
        manager?.disconnect()?.enqueue()
        super.onDestroy()
    }
}
```
`AppDb.cachedProfile` is defined in Task 4; `MainViewModel` updates it when the profile is saved (Task 9).

- [ ] **Step 3b: Strings.** Create `app/src/main/res/values/strings.xml` with these keys now; Task 9 adds the UI ones:
```xml
<resources>
    <string name="app_name">Walkpad Health</string>
    <string name="notif_channel">Walking pad</string>
    <string name="notif_text">Waiting for your walking pad</string>
    <string name="storage_full">Storage is full. The last walk is not saved yet; free up space soon.</string>
</resources>
```
Set `android:label="@string/app_name"` in the manifest.

`BootReceiver.kt`:
```kotlin
package org.walkpadhealth.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.walkpadhealth.AppPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try { WalkService.sync(ctx, AppPrefs(ctx)) }
        catch (e: IllegalStateException) { /* OS refused a background start; the user opens the app once */ }
    }
}
```

- [ ] **Step 4: Minimal `MainActivity.kt`** so the app can be installed and the service started and permissions requested (replaced by the full UI in Task 9):
```kotlin
package org.walkpadhealth

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import org.walkpadhealth.service.WalkService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Start() } }
    }

    @Composable private fun Start() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            WalkService.sync(this, AppPrefs(this))          // starts only if Bluetooth permission was granted
        }
        Button(onClick = { launcher.launch(perms) }) { Text("Start recording") }
    }
}
```

- [ ] **Step 5: Build.** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. Run `./gradlew :app:lintDebug` and fix errors.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(service): foreground service, manifest, boot start"`

---

### CHECKPOINT (Phase 0): hardware capture. The agent stops here and the owner acts.

- [ ] **Step 1:** Owner installs the debug APK (`./gradlew :app:installDebug` with the phone on adb, or copy `app/build/outputs/apk/debug/app-debug.apk`).
- [ ] **Step 2:** Before building, set the default of `AppPrefs.rawLog` to `true` (temporary; the toggle UI arrives in Task 9) so frames are logged. Rebuild, install, open the app, tap Start recording and grant permissions.
- [ ] **Step 3:** Turn the pad on. Capture four controlled runs, noting the console readout at the end of each: (a) 60 s at 2.0 km/h, then 60 s at 4.0 km/h, then pause, resume, stop; (b) a second walk straight after (a) without disconnecting, to see whether pad totals reset or keep counting; (c) 10 minutes at 3.0 km/h with the console distance noted at the end; (d) one deliberate disconnect and reconnect mid-walk (walk out of range, or switch the phone's Bluetooth off for 20 s). Pull the log: `adb exec-out run-as org.walkpadhealth cat files/raw/frames.log > urtm059-capture.log`.
- [ ] **Step 4:** Note the console readout at 3 moments (time, distance, calories, steps if the pad shows them) so decoded fields can be checked against it.
- [ ] **Step 5:** Hand `urtm059-capture.log` and the console readings to the agent. Task 7 cannot start without them. Tasks 8-11 do not depend on the capture and may proceed before it.

---

### Task 7: URTM059 field map (needs the capture)

**Files:**
- Create: `protocol/src/test/resources/urtm059-capture.log` (copy of the owner's capture), `protocol/src/test/kotlin/org/walkpadhealth/protocol/CaptureTest.kt`
- Modify: `UrevoDriver.kt` (add `decodeFtms` and/or the extra `fff1` fields), `UrevoDriverTest.kt`, `PROTOCOL.md`

**Interfaces:**
- Consumes: capture log lines `<epochMs>\t<source>\t<hex>`.
- Produces: `UrevoDriver.decodeFff1` additionally filling `distanceM` / `steps` / `kcal` where the capture proves them; optionally `UrevoDriver.decodeFtms(frame): Telemetry?`.

- [ ] **Step 1: Dump.** Write `CaptureTest.kt` with a helper that loads the log and prints, per source, each distinct frame length and a column view of the bytes that change over time. Run it with `./gradlew :protocol:test --tests '*CaptureTest' -i` and read the output.
- [ ] **Step 2: Map.** For each field (elapsed, distance, steps, calories), find the offset whose values change monotonically and match the owner's console readings at the noted moments. A field is accepted only if it matches the console readings in at least two of the controlled runs, is monotonic within a run, and its behaviour across run (b) (reset or cumulative) and run (d) (reconnect) is recorded in `PROTOCOL.md`. Rollover behaviour is tested with a captured or hand-built frame pair. Record unconfirmed offsets as "unconfirmed" in `PROTOCOL.md`; do not decode them.
- [ ] **Step 3: Failing tests.** For each accepted field add a test in `UrevoDriverTest.kt` using a real captured frame copied verbatim, asserting the decoded value against the console reading. Run: expected FAIL.
- [ ] **Step 4: Implement** the decoding in `UrevoDriver.kt`, with length bounds checks (a short frame yields `null` for the field, never an exception). Keep Task 1's synthetic tests green.
- [ ] **Step 5: Verify.** `./gradlew :protocol:test` → PASS. Add a regression test that feeds every captured frame through the driver and `SessionTracker` and asserts: no exception, exactly one session produced for the walk, its `activeSec` within 3 s of the stopwatch time the owner recorded.
- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(protocol): URTM059 field map from capture"`

---

### Task 8: Health Connect sync

**Files:**
- Create: `app/src/main/kotlin/org/walkpadhealth/health/{HealthGateway.kt,SyncSessions.kt,SyncWorker.kt}` (replace the `SyncScheduler` stub)
- Test: `app/src/test/kotlin/org/walkpadhealth/SyncSessionsTest.kt`

**Interfaces:**
- Consumes: `SessionDao`, `SessionEntity` (Task 4).
- Produces: `interface HealthGateway { suspend fun hasPermissions(): Boolean; suspend fun write(s: SessionEntity) }`; `enum SyncResult { Done, Blocked, Retry }`; `class SyncSessions(dao: SessionDao, gw: HealthGateway) { suspend fun run(): SyncResult }`; `object SyncScheduler { fun enqueue(ctx: Context) }`; `class HealthConnectGateway(ctx) : HealthGateway`; `val HEALTH_PERMISSIONS: Set<String>`.

- [ ] **Step 1: Write the failing test.**
```kotlin
package org.walkpadhealth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.walkpadhealth.data.SessionDao
import org.walkpadhealth.data.SessionEntity
import org.walkpadhealth.health.HealthGateway
import org.walkpadhealth.health.SyncResult
import org.walkpadhealth.health.SyncSessions
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSessionsTest {
    private class FakeDao(val rows: MutableList<SessionEntity>) : SessionDao {
        val synced = mutableListOf<Long>()
        override suspend fun insert(s: SessionEntity) = 0L
        override fun observeAll(): Flow<List<SessionEntity>> = flowOf(rows)
        override suspend fun unsynced() = rows.filter { it.id !in synced }
        override suspend fun markSynced(id: Long) { synced += id }
    }
    private class FakeGw(var perms: Boolean = true, var failOn: Long? = null) : HealthGateway {
        val written = mutableListOf<Long>()
        override suspend fun hasPermissions() = perms
        override suspend fun write(s: SessionEntity) { if (s.id == failOn) error("boom"); written += s.id }
    }
    private fun row(id: Long) = SessionEntity(id, id, id + 1, 60, 50.0, "ESTIMATED", 70, "ESTIMATED", 3.0, "ESTIMATED")

    @Test fun writesAndMarksEveryUnsyncedSession() = runTest {
        val dao = FakeDao(mutableListOf(row(1), row(2))); val gw = FakeGw()
        assertEquals(SyncResult.Done, SyncSessions(dao, gw).run())
        assertEquals(listOf(1L, 2L), gw.written); assertEquals(listOf(1L, 2L), dao.synced)
    }

    @Test fun permissionDeniedWritesNothingAndReportsBlocked() = runTest {
        val dao = FakeDao(mutableListOf(row(1))); val gw = FakeGw(perms = false)
        assertEquals(SyncResult.Blocked, SyncSessions(dao, gw).run())
        assertEquals(emptyList(), gw.written); assertEquals(emptyList(), dao.synced)
    }

    @Test fun failedWriteIsNotMarkedSyncedAndAsksForRetry() = runTest {
        val dao = FakeDao(mutableListOf(row(1), row(2), row(3))); val gw = FakeGw(failOn = 2)
        assertEquals(SyncResult.Retry, SyncSessions(dao, gw).run())
        assertEquals(listOf(1L), dao.synced)                       // 2 failed, 3 not attempted
    }

    @Test fun rerunAfterFailureOnlyWritesWhatIsLeft() = runTest {
        val dao = FakeDao(mutableListOf(row(1), row(2))); val gw = FakeGw(failOn = 2)
        SyncSessions(dao, gw).run()
        gw.failOn = null
        assertEquals(SyncResult.Done, SyncSessions(dao, gw).run())
        assertEquals(listOf(1L, 2L), dao.synced); assertEquals(listOf(1L, 2L), gw.written)
    }

    @Test fun nothingToSyncIsDone() = runTest {
        assertEquals(SyncResult.Done, SyncSessions(FakeDao(mutableListOf()), FakeGw()).run())
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testDebugUnitTest --tests '*SyncSessionsTest'` → FAIL (unresolved).

- [ ] **Step 3: Implement.**

`SyncSessions.kt`:
```kotlin
package org.walkpadhealth.health

import kotlinx.coroutines.CancellationException
import org.walkpadhealth.data.SessionDao
import org.walkpadhealth.data.SessionEntity

interface HealthGateway {
    suspend fun hasPermissions(): Boolean
    suspend fun write(s: SessionEntity)
}

enum class SyncResult { Done, Blocked, Retry }

class SyncSessions(private val dao: SessionDao, private val gw: HealthGateway) {
    suspend fun run(): SyncResult {
        if (!gw.hasPermissions()) return SyncResult.Blocked
        for (s in dao.unsynced()) {
            try { gw.write(s); dao.markSynced(s.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return SyncResult.Retry }
        }
        return SyncResult.Done
    }
}
```

`HealthGateway.kt` (verify the `Metadata` and `Device` factories against the pinned `connect-client`; older alphas use `Metadata(clientRecordId = ...)`):
```kotlin
package org.walkpadhealth.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import org.walkpadhealth.data.SessionEntity
import java.time.Instant
import java.time.ZoneId

val HEALTH_PERMISSIONS: Set<String> = setOf(
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    HealthPermission.getWritePermission(StepsRecord::class),
    HealthPermission.getWritePermission(DistanceRecord::class),
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
)

class HealthConnectGateway(private val ctx: Context) : HealthGateway {
    private fun client(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(ctx) else null

    override suspend fun hasPermissions(): Boolean {
        val c = client() ?: return false
        return c.permissionController.getGrantedPermissions().containsAll(HEALTH_PERMISSIONS)
    }

    override suspend fun write(s: SessionEntity) {
        val c = client() ?: error("Health Connect unavailable")
        val start = Instant.ofEpochMilli(s.startMs)
        val end = Instant.ofEpochMilli(maxOf(s.endMs, s.startMs + 1000))
        val off = ZoneId.systemDefault().rules.getOffset(start)
        // Recorded by a device; one stable id per record type; the version is constant because sessions are immutable.
        fun meta(kind: String) = Metadata.autoRecorded(
            Device(type = Device.TYPE_UNKNOWN, manufacturer = "UREVO", model = "Walking pad"),
            clientRecordId = "walkpad-${s.id}-$kind", clientRecordVersion = 0L,
        )
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                metadata = meta("exercise"), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING, title = "Walking pad",
            ),
        )
        if (s.steps > 0) records += StepsRecord(start, off, end, off, s.steps.toLong(), meta("steps"))
        if (s.distanceM > 0.0) records += DistanceRecord(start, off, end, off, Length.meters(s.distanceM), meta("distance"))
        if (s.kcal > 0.0) records += TotalCaloriesBurnedRecord(start, off, end, off, Energy.kilocalories(s.kcal), meta("kcal"))
        c.insertRecords(records)   // an equal clientRecordId and version is ignored, so a retry after an ambiguous failure cannot duplicate
    }
}
```

`SyncWorker.kt`:
```kotlin
package org.walkpadhealth.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.walkpadhealth.data.AppDb
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result =
        when (SyncSessions(AppDb.get(applicationContext).sessions(), HealthConnectGateway(applicationContext)).run()) {
            SyncResult.Done, SyncResult.Blocked -> Result.success()   // Blocked: re-enqueued when the app opens or permission is granted
            SyncResult.Retry -> Result.retry()
        }
}

object SyncScheduler {
    fun enqueue(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, req)
    }
}
```
Delete the Task 6 `SyncScheduler` stub file.

- [ ] **Step 4: Run to verify it passes.** `./gradlew :app:testDebugUnitTest --tests '*SyncSessionsTest' && ./gradlew :app:assembleDebug` → PASS / BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(health): Health Connect sync with retry"`

---

### Task 9: UI

**Files:**
- Create: `app/src/main/kotlin/org/walkpadhealth/ui/{Format.kt,MainViewModel.kt,TodayScreen.kt,HistoryScreen.kt,SettingsScreen.kt,RawLogScreen.kt}`; modify `MainActivity.kt`
- Test: `app/src/test/kotlin/org/walkpadhealth/FormatTest.kt`

**Interfaces:**
- Consumes: `LiveState`, `Live`, `AppDb`, `SessionEntity`, `ProfileEntity`, `AppPrefs`, `FrameLog`, `HEALTH_PERMISSIONS`, `SyncScheduler`, `WalkService`.
- Produces: `fun fmtDuration(sec: Long): String`; `fun fmtKm(m: Double): String`; `data class DayTotals(steps: Int, activeSec: Long, kcal: Double, distanceM: Double)`; `MainViewModel`.

- [ ] **Step 1: Failing test** `FormatTest.kt`:
```kotlin
package org.walkpadhealth

import org.walkpadhealth.ui.fmtDuration
import org.walkpadhealth.ui.fmtKm
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun duration() {
        assertEquals("0:00", fmtDuration(0)); assertEquals("1:05", fmtDuration(65))
        assertEquals("1:00:00", fmtDuration(3600)); assertEquals("0:00", fmtDuration(-5))
    }
    @Test fun km() { assertEquals("0.00", fmtKm(0.0)); assertEquals("1.23", fmtKm(1234.0)); assertEquals("0.00", fmtKm(Double.NaN)) }
}
```
Run: `./gradlew :app:testDebugUnitTest --tests '*FormatTest'` → FAIL.

- [ ] **Step 2: `Format.kt`.**
```kotlin
package org.walkpadhealth.ui

import java.util.Locale

fun fmtDuration(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

fun fmtKm(m: Double): String = String.format(Locale.getDefault(), "%.2f", if (m.isFinite() && m > 0) m / 1000.0 else 0.0)
```
Run the test → PASS.

- [ ] **Step 4: `MainViewModel.kt`.**
```kotlin
package org.walkpadhealth.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walkpadhealth.LiveState
import org.walkpadhealth.data.AppDb
import org.walkpadhealth.data.ProfileEntity
import org.walkpadhealth.data.toProfile
import java.time.LocalDate
import java.time.ZoneId

data class DayTotals(val steps: Int = 0, val activeSec: Long = 0, val kcal: Double = 0.0, val distanceM: Double = 0.0)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    val live = LiveState.flow
    val sessions = db.sessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profile = db.profile().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val today = sessions.map { list ->
        val zone = ZoneId.systemDefault(); val d = LocalDate.now(zone)
        val t = list.filter { java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == d }
        DayTotals(t.sumOf { it.steps }, t.sumOf { it.activeSec }, t.sumOf { it.kcal }, t.sumOf { it.distanceM })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayTotals())

    fun saveProfile(weightKg: Double, heightCm: Double) = viewModelScope.launch {
        if (weightKg in 20.0..300.0 && heightCm in 80.0..250.0) {
            val e = ProfileEntity(weightKg = weightKg, heightCm = heightCm)
            db.profile().upsert(e); AppDb.cachedProfile = e.toProfile()
        }
    }
}
```

- [ ] **Step 5: Screens.** `TodayScreen.kt`:
```kotlin
package org.walkpadhealth.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.walkpadhealth.Live
import org.walkpadhealth.Problem
import org.walkpadhealth.R
import org.walkpadhealth.data.SessionEntity
import org.walkpadhealth.protocol.BeltStatus

@Composable
fun TodayScreen(live: Live, today: DayTotals, recent: List<SessionEntity>, profileSet: Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!profileSet) item { Text("Set your weight and height in Settings for better estimates.", color = MaterialTheme.colorScheme.error) }
        if (live.problem != Problem.NONE) item {
            Text(stringResource(when (live.problem) { Problem.BLUETOOTH_OFF -> R.string.problem_bt_off; Problem.SCAN_FAILED -> R.string.problem_scan; else -> R.string.problem_permission }),
                color = MaterialTheme.colorScheme.error)
        }
        item { LiveCard(live) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Steps", today.steps.toString()); Stat("Time", fmtDuration(today.activeSec))
                    Stat("km", fmtKm(today.distanceM)); Stat("kcal", "%.0f".format(today.kcal))
                }
            }
        }
        items(recent.take(5), key = { it.id }) { SessionRow(it) }
    }
}

@Composable private fun LiveCard(l: Live) = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Text(when { !l.connected -> "Looking for your pad..."; l.status == BeltStatus.RUNNING -> "Walking"; else -> "Connected" },
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("km/h", "%.1f".format(l.speedKmh)); Stat("Time", fmtDuration(l.activeSec))
            Stat("km", fmtKm(l.distanceM)); Stat("kcal", "%.0f".format(l.kcal)); Stat("Steps", l.steps.toString())
        }
    }
}

@Composable fun Stat(label: String, value: String) = Column {
    Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.labelSmall)
}

@Composable fun SessionRow(s: SessionEntity) = ListItem(
    headlineContent = { Text("${fmtDuration(s.activeSec)} - ${fmtKm(s.distanceM)} km - ${s.steps} steps - %.0f kcal".format(s.kcal)) },
    supportingContent = {
        val est = listOf(s.distanceSource, s.stepsSource, s.kcalSource).count { it == "ESTIMATED" }
        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.startMs)) +
            (if (est > 0) " - $est estimated" else "") + (if (!s.synced) " - not synced" else ""))
    },
)
```
`HistoryScreen.kt`:
```kotlin
package org.walkpadhealth.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.walkpadhealth.data.SessionEntity

@Composable fun HistoryScreen(all: List<SessionEntity>) =
    LazyColumn(contentPadding = PaddingValues(16.dp)) { items(all, key = { it.id }) { SessionRow(it) } }
```
`SettingsScreen.kt` (profile fields, Health Connect permission button, three toggles, 7-tap version row to open RawLog):
```kotlin
package org.walkpadhealth.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.data.ProfileEntity
import org.walkpadhealth.health.HEALTH_PERMISSIONS
import org.walkpadhealth.health.SyncScheduler
import org.walkpadhealth.service.WalkService

@Composable
fun SettingsScreen(profile: ProfileEntity?, prefs: AppPrefs, onSave: (Double, Double) -> Unit, onOpenRawLog: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var w by remember(profile) { mutableStateOf(profile?.weightKg?.toString() ?: "") }
    var h by remember(profile) { mutableStateOf(profile?.heightCm?.toString() ?: "") }
    var auto by remember { mutableStateOf(prefs.autoRecord) }
    var crash by remember { mutableStateOf(prefs.crashOffer) }
    var taps by remember { mutableIntStateOf(0) }
    val hc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { SyncScheduler.enqueue(ctx) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile (stays on this device)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(w, { w = it }, label = { Text("Weight (kg)") }, keyboardOptions = num, singleLine = true)
        OutlinedTextField(h, { h = it }, label = { Text("Height (cm)") }, keyboardOptions = num, singleLine = true)
        Button(onClick = { onSave(w.toDoubleOrNull() ?: 0.0, h.toDoubleOrNull() ?: 0.0) }) { Text("Save profile") }
        HorizontalDivider()
        Button(onClick = { hc.launch(HEALTH_PERMISSIONS) }) { Text("Allow writing to Health Connect") }
        HorizontalDivider()
        Row { Text("Record automatically", Modifier.weight(1f)); Switch(auto, { auto = it; prefs.autoRecord = it; WalkService.sync(ctx, prefs) }) }
        OutlinedButton(onClick = { prefs.padAddress = null; WalkService.sync(ctx, prefs) }) { Text("Forget paired pad") }
        Row { Text("Offer to send crash reports", Modifier.weight(1f)); Switch(crash, { crash = it; prefs.crashOffer = it }) }
        Text("Version 0.1.0", Modifier.clickable { if (++taps >= 7) { taps = 0; onOpenRawLog() } })
    }
}
```
`RawLogScreen.kt`:
```kotlin
package org.walkpadhealth.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.ble.FrameLog
import java.io.File

@Composable
fun RawLogScreen(prefs: AppPrefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val log = remember { FrameLog(File(ctx.filesDir, "raw")) }
    var on by remember { mutableStateOf(prefs.rawLog) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Raw frame log", style = MaterialTheme.typography.titleMedium)
        Row { Text("Record raw frames", Modifier.weight(1f)); Switch(on, { on = it; prefs.rawLog = it }) }
        Button(onClick = {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", log.file())
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share frame log"))
        }) { Text("Export") }
        OutlinedButton(onClick = { log.clear() }) { Text("Clear") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
```

- [ ] **Step 6: `MainActivity.kt`** (replaces the Task 6 stub): bottom `NavigationBar` with Today / History / Settings using `rememberSaveable` tab index, a `rawLog` boolean to show `RawLogScreen`, and the permission launcher plus service start from Task 6 retained (request on first launch if `prefs.autoRecord`, then `SyncScheduler.enqueue(this)` in `onResume`). Collect state with `collectAsStateWithLifecycle()`. Full code:
```kotlin
package org.walkpadhealth

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walkpadhealth.health.SyncScheduler
import org.walkpadhealth.service.WalkService
import org.walkpadhealth.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPrefs(this)
        setContent { WalkpadTheme { App(prefs) } }
    }

    override fun onResume() { super.onResume(); SyncScheduler.enqueue(this) }

    @Composable private fun App(prefs: AppPrefs) {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            WalkService.sync(this, prefs)          // starts only if Bluetooth permission was granted; otherwise Today shows why
        }
        LaunchedEffect(Unit) { if (prefs.autoRecord) { if (WalkService.btGranted(this@MainActivity)) WalkService.sync(this@MainActivity, prefs) else launcher.launch(perms) } }

        val live by vm.live.collectAsStateWithLifecycle()
        val today by vm.today.collectAsStateWithLifecycle()
        val sessions by vm.sessions.collectAsStateWithLifecycle()
        val profile by vm.profile.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var rawLog by rememberSaveable { mutableStateOf(false) }

        Scaffold(bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0; rawLog = false }, { Icon(Icons.Filled.DirectionsWalk, null) }, label = { Text("Today") })
                NavigationBarItem(tab == 1, { tab = 1; rawLog = false }, { Icon(Icons.Filled.History, null) }, label = { Text("History") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        }) { pad ->
            androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                when {
                    tab == 0 -> TodayScreen(live, today, sessions, profile != null)
                    tab == 1 -> HistoryScreen(sessions)
                    rawLog -> RawLogScreen(prefs) { rawLog = false }
                    else -> SettingsScreen(profile, prefs, { w, h -> vm.saveProfile(w, h) }, { rawLog = true })
                }
            }
        }
    }
}
```
Add `androidx.compose.material:material-icons-extended` (already in `libs.compose.icons`).

- [ ] **Step 6b: Theme and strings.** `ui/Theme.kt`:
```kotlin
package org.walkpadhealth.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun WalkpadTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
```
Then move every user-visible literal in `TodayScreen`, `HistoryScreen`, `SettingsScreen`, `RawLogScreen` and the crash dialog into `strings.xml` and read it with `stringResource`. Add at least: `problem_bt_off` ("Bluetooth is off. Turn it on to record walks."), `problem_permission` ("Walkpad Health needs Bluetooth permission to find your pad. Open Settings to allow it."), `looking_for_pad`, `walking`, `connected`, `set_profile_hint`, the tab labels, the Settings labels, `not_synced`, `estimated_count`, `problem_scan` ("Could not scan for your pad. Turn automatic recording off and on to retry.") and `connected_idle` ("Connected. Start the belt to begin recording."), which Today shows whenever `connected && status == IDLE` as a neutral status, not a warning. A pad that connects but never sends frames is diagnosed with the raw log screen, not an automatic warning. `storage_full` already exists from Task 6. The queued-session retry is best effort: the message says the walk is not saved yet, because a process death while storage is full loses it. A journal file cannot fix that, since it also needs free storage.

- [ ] **Step 7: Verify.** `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → all pass. Install on the phone and check: Today shows "Looking for your pad...", Settings saves a profile, the raw log screen opens after 7 taps on the version row.

- [ ] **Step 8: Commit.** `git add -A && git commit -m "feat(ui): today, history, settings, raw log screens"`

---

### Task 10: Crash reporter (opt-in, share sheet)

**Files:**
- Modify: `crash/CrashReporter.kt` (replace stub), `MainActivity.kt`
- Test: `app/src/test/kotlin/org/walkpadhealth/CrashReporterTest.kt`

**Interfaces:**
- Consumes: `AppPrefs.crashOffer` (Task 5).
- Produces: `object CrashReporter { fun install(app: Application); fun pending(ctx: Context): File?; fun discard(ctx: Context); fun render(t: Throwable, versionName: String, sdk: Int): String }`.

- [ ] **Step 1: Failing test.**
```kotlin
package org.walkpadhealth

import org.walkpadhealth.crash.CrashReporter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReporterTest {
    @Test fun reportHasVersionAndFramesButNeverTheMessage() {
        val text = CrashReporter.render(IllegalStateException("secret /Users/someone/file 02510314"), "0.1.0", 34)
        assertTrue("0.1.0" in text && "SDK 34" in text && "java.lang.IllegalStateException" in text && "CrashReporterTest" in text)
        assertFalse("secret" in text || "/Users/" in text || "02510314" in text)
    }

    @Test fun causesAreIncludedWithoutTheirMessages() {
        val text = CrashReporter.render(RuntimeException("outer", IllegalArgumentException("inner-secret")), "0.1.0", 34)
        assertTrue("java.lang.IllegalArgumentException" in text); assertFalse("inner-secret" in text)
    }
}
```
Run → FAIL.

- [ ] **Step 2: Implement.**
```kotlin
package org.walkpadhealth.crash

import android.app.Application
import android.content.Context
import org.walkpadhealth.AppPrefs
import org.walkpadhealth.BuildConfig
import java.io.File

object CrashReporter {
    /** Only class names, method names and line numbers. Exception messages are dropped because they can hold paths or user data. */
    fun render(t: Throwable, versionName: String, sdk: Int): String = buildString {
        append("Walkpad Health $versionName, SDK $sdk\n\n")
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 5) {
            append(c.javaClass.name).append('\n')
            c.stackTrace.take(40).forEach { append("  at ").append(it.className).append('.').append(it.methodName).append(':').append(it.lineNumber).append('\n') }
            c = c.cause
        }
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, "crash").apply { mkdirs() }, "last.txt")

    fun pending(ctx: Context): File? = file(ctx).takeIf { it.exists() && it.length() > 0 }
    fun discard(ctx: Context) { file(ctx).delete() }

    private fun offered(ctx: Context) = File(file(ctx).parentFile, "offered.txt")

    /** Called when the user taps Send: the file moves aside so it is not prompted again but stays readable for the share sheet. */
    fun markOffered(ctx: Context): File {
        val dst = offered(ctx).also { it.delete() }
        if (!file(ctx).renameTo(dst)) { file(ctx).copyTo(dst, overwrite = true); file(ctx).delete() }
        dst.setLastModified(System.currentTimeMillis())          // a rename keeps the old time; retention counts from the offer
        return dst
    }

    /** Offered files are kept 24 hours so a slow share target can still read them; never deleted merely because the app started. */
    fun cleanOffered(ctx: Context) {
        val f = offered(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() > 24 * 3_600_000L) f.delete()
    }

    fun install(app: Application) {
        cleanOffered(app)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try {
                if (AppPrefs(app).crashOffer) file(app).writeText(render(e, BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT))
            } catch (_: Exception) { /* never mask the original crash */ }
            prev?.uncaughtException(th, e)
        }
    }
}
```
Run the test → PASS.

- [ ] **Step 3: Prompt on next launch.** In `MainActivity.App`, before the `Scaffold`:
```kotlin
var crash by remember { mutableStateOf(org.walkpadhealth.crash.CrashReporter.pending(this)) }
crash?.let { f ->
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Walkpad Health crashed") },
        text = { Text("Send the crash details? They contain only the error type, code locations and app version, never messages or your data. Nothing is sent unless you tap Send.") },
        confirmButton = { TextButton({
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", org.walkpadhealth.crash.CrashReporter.markOffered(this))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Send crash report"))
            crash = null
        }) { Text("Send") } },
        dismissButton = { TextButton({ org.walkpadhealth.crash.CrashReporter.discard(this); crash = null }) { Text("Discard") } },
    )
}
```
On Send the file is renamed to `offered.txt` first and the share URI is built from that file, so the prompt does not repeat and the share sheet can still read it; `CrashReporter.install` deletes `offered.txt` only once it is more than 24 hours old.

- [ ] **Step 4: Verify.** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Manual: add a temporary crash button, tap it, relaunch, confirm the dialog, confirm Discard removes it; remove the button.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(crash): opt-in crash report via share sheet"`

---

### Task 11: Release, F-Droid, docs

**Files:**
- Create: `.github/workflows/release.yml`, `fastlane/metadata/android/en-US/{short_description.txt,full_description.txt,changelogs/1.txt}`, `PROTOCOL.md`, `CONTRIBUTING.md`; modify `README.md`

- [ ] **Step 1: Release workflow** `.github/workflows/release.yml`:
```yaml
name: release
on:
  push:
    tags: ['v*']
jobs:
  release:
    runs-on: ubuntu-latest
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_B64 }}" | base64 -d > "$RUNNER_TEMP/release.jks"
      - run: ./gradlew :protocol:test :app:testDebugUnitTest :app:assembleRelease
        env:
          KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
      - run: gh release create "$GITHUB_REF_NAME" app/build/outputs/apk/release/*.apk --generate-notes
        env: { GH_TOKEN: "${{ secrets.GITHUB_TOKEN }}" }
```
Owner action (the agent cannot do these): generate a keystore (`keytool -genkeypair -v -keystore release.jks -alias walkpad -keyalg RSA -keysize 4096 -validity 10000`), add the four secrets to the GitHub repo, and keep the keystore backed up offline. Losing it means existing installs cannot update.

- [ ] **Step 2: F-Droid metadata.** `fastlane/metadata/android/en-US/short_description.txt`: `Track walking pad sessions to Health Connect. No account, no analytics.` `full_description.txt`: what it does, supported pad (`URTM059`, others untested), privacy (no network permission, crash report only when you tap Send), Health Connect requirement. `changelogs/1.txt`: `First release.` (versionCode 1). Then the owner opens a merge request to `gitlab.com/fdroid/fdroiddata` with `metadata/org.walkpadhealth.yml` (License GPL-3.0-or-later, Categories Sports & Health, SourceCode and IssueTracker URLs, `Builds` entry with `gradle: [yes]`, `subdir: app`, `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`). The agent drafts the file; the owner submits it.

- [ ] **Step 3: Docs.** `PROTOCOL.md`: services and characteristics confirmed on `URTM059`, the handshake and which write type worked (Task 5), the decoded fields from Task 7 with the evidence for each, and the unconfirmed offsets. Credit: "Protocol research builds on TreadSpan (E1L) and urevo-darwin (5L); no code was copied." `README.md`: what it is, install (GitHub releases, F-Droid), first-run steps (grant Bluetooth and notification permissions, allow Health Connect in Settings, enter weight and height, exempt the app from battery optimization so it survives screen-off; if the OS kills the app it resumes at the next boot or app open), privacy statement, supported device, build instructions. `CONTRIBUTING.md`: how to add a driver (new class in `:protocol`, fixtures from a raw log, tests first).

- [ ] **Step 3b: Reproducibility.** Commit the drafted recipe as `fdroid/org.walkpadhealth.yml` (the file the owner submits to fdroiddata) and add a CI job to `.github/workflows/ci.yml`:
```yaml
  reproducible:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :app:assembleRelease && cp app/build/outputs/apk/release/app-release-unsigned.apk "$RUNNER_TEMP/a.apk"
      - run: ./gradlew clean :app:assembleRelease --no-build-cache && cmp "$RUNNER_TEMP/a.apk" app/build/outputs/apk/release/app-release-unsigned.apk
```
If `cmp` fails, find the non-determinism (usually an embedded timestamp; `dependenciesInfo` is already disabled in Task 0) before submitting to F-Droid.

- [ ] **Step 4: Hardware checklist** (add to `CONTRIBUTING.md`, run before every release): pad on, app closed -> walk 2 minutes -> session appears in History and Health Connect with correct totals; pause and resume keeps one session; walking out of range 30 s and back keeps one session; out of range 70 s ends it; reboot phone with auto-record on -> service starts; Bluetooth off shows a clear message; Health Connect permission denied queues sessions and syncs after granting; in the Health Connect app, one walk shows as one Walking session with steps, distance and calories, and re-running sync (toggle permission off and on, reopen the app) creates no duplicates; with Bluetooth off or the permission revoked, Today shows the matching message.

- [ ] **Step 5: Full verification.**
Run: `./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` → all pass. Tag locally `git tag v0.1.0` only after the owner has run the hardware checklist; the owner pushes the tag.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "docs: release workflow, F-Droid metadata, protocol notes"`

---

## Self-Review

**Spec coverage:** purpose/criteria (Tasks 6, 8, 9, 10), track-only and handshake-only writes (Tasks 1, 5), auto-record + boot (Task 6), profile in Settings with no HC read (Tasks 4, 9), two modules (Task 0), stack (Task 0), crash opt-in (Task 10), license and credits (Tasks 0, 11), protocol known/unknown and mitigation (Task 7 and checkpoint), architecture 4.1 (Tasks 1-3), 4.2 (Tasks 4-9), data flow (Tasks 5-8), numbers and source tags (Tasks 3, 9), UI incl. hidden raw log (Task 9), privacy/permissions (Tasks 0, 6), error handling (Tasks 6, 8; Bluetooth-off and out-of-range messages are shown by the Today card states and the hardware checklist, a dedicated Bluetooth-off banner is not built in v1), testing (every task), delivery (Task 11), phases (checkpoint = Phase 0). Open spec items (field map, steps reporting, final name) are carried by Task 7 and Task 11.

**Placeholder scan:** the only deliberate gate is Task 7, which cannot be written before real capture exists; it states the exact procedure and acceptance rule. Dependency versions are starting pins with a stated bump rule. API call shapes for Nordic BLE and Health Connect `Metadata` must be checked against the pinned versions at compile time; this is stated in Tasks 5 and 8.

**Type consistency:** `Telemetry`, `BeltStatus`, `SessionSummary`, `FinalSession`, `Profile`, `Progress`, `SessionDao`, `HealthGateway`, `Live` names and signatures match across tasks. `AppDb.cachedProfile` is defined in Task 4 and used by Tasks 6 and 9.

**Review log:** `2026-10-07-walkpad-health-REVIEW-LOG.md` records each Codex round, what changed and what was rejected.
