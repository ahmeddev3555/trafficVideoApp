# Recording Heading Rotation Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the compass heading captured during recording
(`rotation_samples` and the one-shot `compassHeadingDegrees` snapshot) for
the phone's *current* physical rotation, instead of always reporting
relative to the device's fixed natural (portrait) orientation.

**Architecture:** `CameraController` already tracks the phone's current
`Surface.ROTATION_*` state via its own `OrientationEventListener` (used to
set `VideoCapture`'s target rotation) - it gains a `currentRotation:
StateFlow<Int>` exposing that same state. `CompassProvider` applies
`SensorManager.remapCoordinateSystem()` using that rotation value before
computing azimuth, both for the continuous `observeHeadings()` stream
(re-evaluated per sample, so it self-corrects if the phone is re-oriented
mid-recording) and the one-shot `getSnapshot()`. `CameraViewModel` wires the
two together - both are already its sibling dependencies.

**Tech Stack:** Kotlin, Android `SensorManager`/`OrientationEventListener`,
Hilt DI, JUnit 5, Kotlin coroutines `StateFlow`/`Flow`.

## Global Constraints

- Remap table (verbatim from the design spec):
  ```kotlin
  val (axisX, axisY) = when (rotation) {
      Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
      Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
      Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
      else -> SensorManager.AXIS_X to SensorManager.AXIS_Y // ROTATION_0: no remap needed
  }
  ```
- Declination is applied AFTER the rotation remap, unchanged in position -
  it's a location-based correction independent of device rotation.
- The heading correction must be continuous (re-evaluated per sample), not
  a one-time snapshot at recording start - a mid-recording re-orientation
  must be reflected in later `rotation_samples`.
- No new permissions, no new sensors - `CameraController` already computes
  the rotation state this needs; this plan only shares it.

---

### Task 1: Rotation-aware heading correction, wired end to end

This is one task, not split further: `CameraController`'s state exposure,
`CompassProvider`'s remap logic, and `CameraViewModel`'s wiring are three
parts of one indivisible change - none of them compiles or means anything
on its own (changing `CompassProvider`'s signatures without also updating
`CameraViewModel`'s call sites leaves the module in a non-compiling state),
so there is no meaningful earlier stopping point for a task boundary.

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`
- Test (new): `app/src/test/java/com/trafficwatch/app/core/util/CompassProviderTest.kt`

**Interfaces:**
- Produces: `CameraController.currentRotation: StateFlow<Int>` (a
  `Surface.ROTATION_*` value, initialized to `Surface.ROTATION_0`).
- Produces: `internal fun remapAxesFor(rotation: Int): Pair<Int, Int>` in
  `CompassProvider.kt` (top-level, package-private to
  `com.trafficwatch.app.core.util`).
- Changes: `CompassProvider.observeHeadings(latitude, longitude, altitude,
  intervalMs, currentRotation: StateFlow<Int>)` (new required parameter).
  `CompassProvider.getSnapshot(latitude, longitude, altitude, rotation:
  Int, timeoutMs = DEFAULT_TIMEOUT_MS)` (new required parameter, inserted
  before the existing defaulted `timeoutMs`).

- [ ] **Step 1: Write the failing test for the pure axis-remap function**

Create `app/src/test/java/com/trafficwatch/app/core/util/CompassProviderTest.kt`:

```kotlin
package com.trafficwatch.app.core.util

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CompassProviderTest {

    @Test
    fun `ROTATION_0 needs no remap`() {
        assertEquals(SensorManager.AXIS_X to SensorManager.AXIS_Y, remapAxesFor(Surface.ROTATION_0))
    }

    @Test
    fun `ROTATION_90 remaps to Y, MINUS_X`() {
        assertEquals(SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X, remapAxesFor(Surface.ROTATION_90))
    }

    @Test
    fun `ROTATION_180 remaps to MINUS_X, MINUS_Y`() {
        assertEquals(SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y, remapAxesFor(Surface.ROTATION_180))
    }

    @Test
    fun `ROTATION_270 remaps to MINUS_Y, X`() {
        assertEquals(SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X, remapAxesFor(Surface.ROTATION_270))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.CompassProviderTest"`
Expected: FAILURE - compile error, `remapAxesFor` is unresolved.

- [ ] **Step 3: Implement `remapAxesFor`**

In `app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt`, add
`import android.view.Surface` to the imports, and add this top-level function
right after the existing `private const val SAMPLE_COUNT = 3` line:

```kotlin
/**
 * Which [SensorManager.remapCoordinateSystem] axis pair corrects a rotation-vector
 * reading for [rotation] (a `Surface.ROTATION_*` value) - the device's *current*
 * physical orientation - as opposed to [SensorManager.getOrientation]'s default of
 * always reporting relative to the device's fixed natural (portrait) orientation.
 * Standard Android compass-correction pattern.
 */
internal fun remapAxesFor(rotation: Int): Pair<Int, Int> = when (rotation) {
    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.CompassProviderTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Expose `currentRotation` from `CameraController`**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt`,
add a new state flow right after the existing `_recordingState`/`recordingState`
pair:

```kotlin
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState = _recordingState.asStateFlow()

    private val _currentRotation = MutableStateFlow(Surface.ROTATION_0)
    val currentRotation = _currentRotation.asStateFlow()

```

Then, inside `ensureOrientationListener()`'s `onOrientationChanged`, set it
alongside the existing `videoCapture?.targetRotation` assignment - both driven
by the same locally-computed `rotation` value, so they can never disagree:

```kotlin
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                videoCapture?.targetRotation = rotation
                _currentRotation.value = rotation
```

- [ ] **Step 6: Apply the remap inside `CompassProvider`'s shared azimuth helper**

In `CompassProvider.kt`, replace `rawAzimuthDegrees` entirely:

```kotlin
    // rotationMatrix/remappedMatrix/orientation are reused across calls by both callers
    // below - safe because a single SensorEventListener's onSensorChanged is always
    // invoked serially on one thread, never concurrently.
    private fun rawAzimuthDegrees(
        event: SensorEvent,
        rotation: Int,
        rotationMatrix: FloatArray,
        remappedMatrix: FloatArray,
        orientation: FloatArray,
    ): Float {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val (axisX, axisY) = remapAxesFor(rotation)
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        SensorManager.getOrientation(remappedMatrix, orientation)
        return (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
    }
```

- [ ] **Step 7: Thread `rotation` through `getSnapshot`/`readAveragedAzimuthDegrees`**

Change `getSnapshot`'s signature (add `rotation: Int` before the existing
defaulted `timeoutMs`) and its call to `readAveragedAzimuthDegrees`:

```kotlin
    suspend fun getSnapshot(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        rotation: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Float? = withTimeoutOrNull(timeoutMs) {
        val magneticAzimuthDegrees = readAveragedAzimuthDegrees(rotation) ?: return@withTimeoutOrNull null
        val declination = declinationDegrees(latitude, longitude, altitude)
        applyDeclination(magneticAzimuthDegrees, declination)
    }
```

Change `readAveragedAzimuthDegrees` to accept and thread `rotation` through,
and add the new `remappedMatrix` array:

```kotlin
    private suspend fun readAveragedAzimuthDegrees(rotation: Int): Float? = suspendCancellableCoroutine { continuation ->
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val samples = mutableListOf<Float>()
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                samples.add(rawAzimuthDegrees(event, rotation, rotationMatrix, remappedMatrix, orientation))

                if (samples.size >= SAMPLE_COUNT) {
                    sensorManager.unregisterListener(this)
                    if (continuation.isActive) {
                        continuation.resume(circularMeanDegrees(samples))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
    }
```

- [ ] **Step 8: Thread `currentRotation` through `observeHeadings`**

Add `import kotlinx.coroutines.flow.StateFlow` to `CompassProvider.kt`'s
imports. Change `observeHeadings`'s signature (add `currentRotation:
StateFlow<Int>` after the existing `intervalMs`) and its body to add the
`remappedMatrix` array and read `currentRotation.value` per sample:

```kotlin
    fun observeHeadings(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        intervalMs: Long,
        currentRotation: StateFlow<Int>,
    ): Flow<Float?> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val declination = declinationDegrees(latitude, longitude, altitude)
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var lastEmittedAt = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                // registerListener's samplingPeriodUs is only a hint - the OS may deliver
                // events faster than requested (e.g. another listener on the same physical
                // sensor, even this class's own readAveragedAzimuthDegrees(), can cause the
                // shared sensor to report at a faster rate to every registered listener).
                // Without this explicit throttle, a burst of events could grow the samples
                // list far beyond the ~50-per-10s-clip size the upload payload assumes,
                // eventually overflowing WorkManager's Data size limit at submit time.
                if (now - lastEmittedAt < intervalMs) return
                lastEmittedAt = now
                val magneticAzimuthDegrees = rawAzimuthDegrees(event, currentRotation.value, rotationMatrix, remappedMatrix, orientation)
                trySend(applyDeclination(magneticAzimuthDegrees, declination))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, (intervalMs * 1000).toInt())
        awaitClose { sensorManager.unregisterListener(listener) }
    }
```

- [ ] **Step 9: Wire `CameraController.currentRotation` into `CameraViewModel`'s existing calls**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`,
both `compassProvider.observeHeadings(...)` calls (the `declinationReference
!= null` branch and its `else` fallback, inside `onStartRecording`) gain
`currentRotation = cameraController.currentRotation`:

```kotlin
            val headings = if (declinationReference != null) {
                compassProvider.observeHeadings(
                    latitude = declinationReference.latitude,
                    longitude = declinationReference.longitude,
                    altitude = declinationReference.altitude,
                    intervalMs = ROTATION_SAMPLE_INTERVAL_MS,
                    currentRotation = cameraController.currentRotation,
                )
            } else {
                compassProvider.observeHeadings(
                    latitude = 0.0, longitude = 0.0, altitude = 0.0,
                    intervalMs = ROTATION_SAMPLE_INTERVAL_MS,
                    currentRotation = cameraController.currentRotation,
                )
            }
```

Both `compassProvider.getSnapshot(...)` calls (same two branches, a few lines
below) gain `rotation = cameraController.currentRotation.value`:

```kotlin
        viewModelScope.launch {
            snapshotCompassHeading = if (declinationReference != null) {
                compassProvider.getSnapshot(
                    latitude = declinationReference.latitude,
                    longitude = declinationReference.longitude,
                    altitude = declinationReference.altitude,
                    rotation = cameraController.currentRotation.value,
                )
            } else {
                compassProvider.getSnapshot(
                    latitude = 0.0, longitude = 0.0, altitude = 0.0,
                    rotation = cameraController.currentRotation.value,
                )
            }
        }
```

- [ ] **Step 10: Confirm the app builds**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Run the full app unit test suite**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (existing tests unaffected - this
change doesn't touch anything they exercise; the 4 new `CompassProviderTest`
cases are included in the count).

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt app/src/test/java/com/trafficwatch/app/core/util/CompassProviderTest.kt
git commit -m "fix(app): correct recording heading for the phone's current physical rotation

CompassProvider's heading was always relative to the device's fixed
natural (portrait) orientation, so a landscape-held recording
reported a systematically wrong heading - confirmed via an on-device
test (portrait/landscape-left/landscape-right facing the same
direction produced three different headings). Now applies
SensorManager.remapCoordinateSystem() using CameraController's
already-tracked Surface.ROTATION_* state, re-evaluated per sample so
a mid-recording re-orientation is reflected in later rotation_samples
rather than only the value captured at recording start."
```

- [ ] **Step 13: Install on device and manually verify**

Run: `./gradlew.bat :app:installDebug` (device must be connected via adb -
run `adb devices` first to confirm)

Manual verification steps (this is where the real correctness confirmation
happens - `SensorManager`'s remap/orientation calls aren't meaningfully
mockable, and the earlier handheld test was too imprecise to numerically
confirm the exact table):

1. Rest the phone flat on a table (not handheld) somewhere you can also see
   a compass reading (a separate compass app on another device, or a
   physical compass) - this eliminates the handheld-aiming imprecision
   that muddied the earlier test.
2. With the app open on the Camera screen (no need to actually record -
   `currentHeadingDegrees` in `CameraUiState` updates live once a
   recording starts, so start a recording for this step), note the
   live heading shown, and compare it against the real compass reading.
   They should agree (within normal compass/sensor noise, a few degrees).
3. Without moving the phone's position, physically spin it in place 90°
   (still flat on the table) to a landscape orientation. Confirm the live
   heading is still close to the same real-world compass value it was in
   portrait - NOT shifted by ~90° or any other systematic offset.
4. Repeat for the other landscape orientation, and for upside-down
   portrait. All four should read close to the same true compass value,
   since the phone hasn't actually changed which way it's pointing -
   only how it's rotated.
5. Start a recording in portrait, let it run a few seconds, then rotate to
   landscape mid-recording without stopping. Stop the recording. Confirm
   (via a temporary debug log, or by checking the submitted report's
   `rotation_samples` after upload) that the heading values recorded
   *after* the rotation reflect the corrected value for the new
   orientation, not a value stuck at whatever was current at recording
   start - this is the specific behavior the continuous
   (re-evaluated-per-sample) design exists to deliver.
