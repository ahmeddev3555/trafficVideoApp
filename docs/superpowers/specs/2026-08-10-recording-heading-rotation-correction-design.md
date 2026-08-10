# Recording Heading Rotation Correction - Design

## Context

Backlog item ("Camera / Recording" section, added 2026-08-06): the heading
captured during recording (`rotation_samples`, and the one-shot
`compassHeadingDegrees` snapshot) reflects the phone's physical
top-of-device axis via `SensorManager.getOrientation()`, which is always
relative to the device's fixed *natural* (portrait) orientation - it never
accounts for how the phone is *currently* being physically held. This
heading feeds directly into the server's wrong-way bearing math
(`OrientationTimeline` → `ClipFlowAnalyzer.qualifyVehicles`,
`absoluteBearingDegrees = orientation + frameBearing`), so a systematically
wrong heading isn't just a display issue - it can corrupt real wrong-way
verdicts.

**Confirmed via an on-device test** (2026-08-10): recording while facing the
same direction in portrait, then landscape rotated one way, then landscape
rotated the other way, produced three different headings (~66°, ~261°,
~99.8°) - conclusively proving the heading is not invariant to physical
device rotation, as suspected. The test was handheld (not on a fixed
mount), so the *exact* numeric offset couldn't be cleanly extracted - real
handheld imprecision in reproducing "facing the same direction" while also
rotating the phone 90° in hand plausibly explains why the differences
weren't a clean ±90°. The fix therefore uses Android's own well-established
`remapCoordinateSystem()` pattern rather than a hand-derived numeric
correction, with a controlled (phone-on-a-table) re-test as part of
verification.

**Deliberately out of scope**: this only corrects the heading for the
device's *own* physical rotation. It does not attempt to correct for the
gap between "device top-axis" and "camera lens optical axis" in any other
sense (e.g. camera pitch/tilt) - that's a separate, harder problem the
original backlog item's UI-framing alternative (explicit "device heading,
not camera heading" labeling) was meant to cover if this fix turns out to
be insufficient on its own. This fix addresses the specific, confirmed,
correctable defect: heading changing based on portrait vs. landscape hold,
which is now understood precisely enough to fix.

## Architecture overview

Three files change, no new files:

1. **`CameraController.kt`** exposes its already-computed physical rotation
   state as a new `currentRotation: StateFlow<Int>` (a `Surface.ROTATION_*`
   value) - set alongside the existing `videoCapture?.targetRotation`
   assignment inside its `OrientationEventListener`. No new sensor, no new
   permission - this data already exists, it's just not shared today.
2. **`CompassProvider.kt`** applies `SensorManager.remapCoordinateSystem()`
   to the rotation-vector's matrix, using the current `Surface.ROTATION_*`
   value, before calling `getOrientation()` - both for the continuous
   `observeHeadings()` stream (re-evaluated per sample, so a mid-recording
   re-orientation is reflected in later samples) and the one-shot
   `getSnapshot()`.
3. **`CameraViewModel.kt`** wires `cameraController.currentRotation`
   (already a sibling dependency) into both `compassProvider` calls it
   already makes - no new dependency injected, just an existing one's state
   passed through.

## `CameraController.kt` changes

Add a `MutableStateFlow<Int>` initialized to `Surface.ROTATION_0`, exposed
as `currentRotation: StateFlow<Int>`. Inside the existing
`OrientationEventListener.onOrientationChanged`, set it alongside the
existing `videoCapture?.targetRotation = rotation` line - both driven by
the same locally-computed `rotation` value, so they can never disagree.

## `CompassProvider.kt` changes

**`observeHeadings`** gains a new parameter,
`currentRotation: StateFlow<Int>`, supplied by the caller as
`cameraController.currentRotation`. Because it's a `StateFlow` (not a cold
`Flow`), its `.value` is always synchronously readable - no `combine()`
needed. Inside the existing `onSensorChanged` callback, each sample reads
`currentRotation.value` at that instant and uses it for that sample's
remap, so a phone rotated partway through a recording gets
correctly-corrected samples both before and after the re-orientation, not
just at recording start.

**`getSnapshot`** gains a `rotation: Int` parameter (the caller passes
`cameraController.currentRotation.value` at call time - no flow needed,
since this is already a single point-in-time read).

**`rawAzimuthDegrees`** (the shared helper both call) gains the remap step:

```kotlin
private fun rawAzimuthDegrees(
    event: SensorEvent,
    rotation: Int,
    rotationMatrix: FloatArray,
    remappedMatrix: FloatArray,
    orientation: FloatArray,
): Float {
    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
    val (axisX, axisY) = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y // ROTATION_0: no remap needed
    }
    SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
    SensorManager.getOrientation(remappedMatrix, orientation)
    return (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
}
```

Declination continues to be applied after this (in `applyDeclination`,
unchanged) - it's a location-based correction independent of device
rotation, so it stays last in the chain.

## `CameraViewModel.kt` changes

Both existing `compassProvider.observeHeadings(...)` calls (declination-
available and fallback branches) gain
`currentRotation = cameraController.currentRotation`. Both existing
`compassProvider.getSnapshot(...)` calls gain
`rotation = cameraController.currentRotation.value`.

## Testing

- **Unit tests**: `CompassProvider`'s rotation-matrix remap logic is pure
  math once isolated from the live `SensorManager`/`SensorEvent` calls -
  the plan should extract the axis-selection `when` block (shown above)
  into a small pure function (e.g. `remapAxesFor(rotation: Int): Pair<Int,
  Int>`) that a plain JUnit test can exercise directly for all four
  `Surface.ROTATION_*` values, without needing Android framework mocking.
- **Manual on-device verification** (this is where the real correctness
  confirmation happens, given `getOrientation()`/`remapCoordinateSystem()`
  aren't mockable in a meaningful way): phone resting flat on a table (not
  handheld, to eliminate the aiming imprecision that muddied the earlier
  test), physically spun in place through portrait and both landscape
  orientations without translating or changing which way it's pointed,
  comparing the app's live heading display against a real compass (phone
  compass app, or a physical compass) at each orientation. Also verify a
  mid-recording re-orientation (start recording in portrait, rotate to
  landscape partway through) produces a heading that changes to match the
  new orientation, not one that stays stuck at the recording-start value -
  this is the specific behavior the "continuous, per-sample" design
  decision exists to deliver.

## Files touched

- **Modify**: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt`,
  `app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt`,
  `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`.
- **Test**: a new or extended test file for `CompassProvider`'s pure
  axis-remap-selection function.
