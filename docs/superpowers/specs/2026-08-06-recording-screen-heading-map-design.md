# Recording Screen Heading Map Design

## Context

Backlog item (`docs/improvements-backlog.md`, "Camera / Recording", added
2026-08-06): show a small map on the recording screen with the current
location and the direction the camera is pointing. User-requested - today
there's no visual indication that GPS/compass data is actually being
captured, and this could help a user understand why a report might later
come back with weak/`Unknown` direction evidence (e.g. no GPS fix yet).

This is the first item worked from the "Camera / Recording" backlog
section (distinct from the just-completed "Navigation / UI flow" section's
3 items).

## Current state (confirmed via code inspection)

- `CameraViewModel` already runs continuous location observation
  (`locationUtil.observeLocation()`, default 3s interval) from `init {}`,
  for the entire time the Camera screen is open - not just during
  recording. Its result already feeds `CameraUiState.locationState`
  (`Acquiring`/`Fixed(data)`/`Unavailable`), which the existing `GpsBadge`
  (top-left) renders as a text status today.
- Continuous compass heading observation
  (`compassProvider.observeHeadings(lat, lon, alt, intervalMs)`,
  rotation-vector sensor) only starts once recording begins
  (`onStartRecording()`'s `rotationSamplingJob`), feeding a private
  `rotationSamples` list used to build the report's `rotation_samples`
  payload. Its latest value is not currently exposed to `CameraUiState` or
  rendered anywhere - it exists only to be collected and submitted.
- `core/ui/components/LocationMapView.kt` already exists: a small,
  non-interactive (no pan/zoom) osmdroid `MapView` centered on a
  lat/lon with a single pin `Marker`, used today by
  `ReportDetailScreen.kt:147` (`LocationMapView(latitude = ..., longitude = ...)`,
  positional named args only, no other params).
- `CameraScreen.kt`'s recording UI (`Box(fillMaxSize)`) already places:
  `GpsBadge` top-left, a recording timer top-right (only while
  `RecordingState.Recording`), the record/stop `FloatingActionButton`
  bottom-center, and a GPS-not-ready hint / camera-error `Snackbar`
  bottom-center (~136dp up). Bottom-right is the one corner nothing
  occupies at any point in the screen's lifecycle.

## Scope (confirmed with user)

- **Visibility timing:** the map itself appears as soon as the Camera
  screen opens and a GPS fix exists (reusing the location stream that's
  already always-on - no new continuous capture). The **heading
  arrow only appears once recording starts** (reusing the existing
  recording-triggered compass stream - no new continuous sensor usage
  while idly viewing the camera screen before ever tapping record).
- **Placement/size:** a small (64dp) map in the **bottom-right corner** -
  chosen over a larger variant or a top-right/idle-only placement (a
  visual mockup comparison was used for this decision) specifically
  because bottom-right is the one screen corner that's never contested by
  the GPS badge (top-left) or the recording timer (top-right, appears only
  while recording) - it stays put and never gets covered or relocated at
  any point in the screen's lifecycle.

## Fix

**1. `LocationMapView.kt` gains an optional `headingDegrees` parameter,**
fully backward compatible with its one existing call site
(`ReportDetailScreen.kt:147`, which passes only `latitude`/`longitude` and
will keep rendering exactly as it does today - a plain pin, no arrow):

```kotlin
@Composable
fun LocationMapView(
    latitude: Double,
    longitude: Double,
    headingDegrees: Float? = null,
    modifier: Modifier = Modifier
) {
    // ... existing MapView/Marker setup unchanged ...
    // the Marker gains: headingDegrees?.let { marker.rotation = it }
    // (osmdroid's Marker.setRotation(Float) rotates the marker's icon
    // around its anchor point - a standard, built-in osmdroid feature,
    // no new dependency or custom overlay needed)
}
```

When `headingDegrees` is null, the marker renders as today (a plain pin).
When non-null, the marker's icon is rotated to point in that compass
direction - the existing default marker icon reads reasonably as a
directional indicator once rotated, so no new custom arrow/cone drawable
asset is needed for a first version (a custom icon can be a follow-up
polish item if the default pin doesn't read clearly enough once shipped).

**2. `CameraViewModel`'s `CameraUiState` gains `currentHeadingDegrees: Float? = null`.**
The existing `rotationSamplingJob` (started in `onStartRecording()`,
already collecting headings into the private `rotationSamples` list) also
updates this new state field on every emission - no new subscription, just
surfacing a value that's already being collected:

```kotlin
headings.filterNotNull().collect { heading ->
    rotationSamples.add(RotationSample(capturedAt = System.currentTimeMillis(), headingDegrees = heading))
    _uiState.update { it.copy(currentHeadingDegrees = heading) }
}
```

`stopRecording()` resets it back to `null`, so the arrow disappears rather
than freezing on a stale heading once recording ends (matching the
"heading only during recording" scope decision above - the map itself
stays visible, showing the current fix, but the direction indicator goes
away with the thing it was describing).

**3. `CameraScreen.kt` renders the map** in the bottom-right corner
(64dp, matching the mockup-selected size) whenever
`uiState.locationState is LocationState.Fixed`:

```kotlin
if (uiState.locationState is LocationState.Fixed) {
    val fixed = uiState.locationState as LocationState.Fixed
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 136.dp)
            .size(64.dp)
    ) {
        LocationMapView(
            latitude = fixed.data.latitude,
            longitude = fixed.data.longitude,
            headingDegrees = uiState.currentHeadingDegrees,
            modifier = Modifier.fillMaxSize()
        )
    }
}
```

(bottom padding of 136dp matches the existing GPS-not-ready-hint's and
camera-error-snackbar's own bottom offset in this file - `CameraScreen.kt`'s
current `padding(bottom = 136.dp)` on both of those elements, confirmed by
direct inspection - keeping the map clear of the record button without
introducing a new magic-number convention.)

**Why not render anything before a GPS fix exists:** the existing
`GpsBadge` (top-left) already communicates "Acquiring GPS…"/"GPS
Unavailable" in text. Rendering an empty/placeholder map tile in the
bottom-right corner during that time would duplicate that signal in a
second place without adding information - simpler and less visually noisy
to just have the map corner appear once there's actually something to
show.

## Non-goals

- A custom rotating arrow/cone drawable asset - the default osmdroid
  marker icon, rotated via `Marker.setRotation()`, is the first-version
  approach; a custom icon is a follow-up polish item if needed.
- Any change to `ReportDetailScreen.kt`'s existing `LocationMapView` usage
  - it continues to render a plain, non-rotated pin (its call site never
    passes `headingDegrees`).
- Any change to what gets captured/submitted with a report -
  `rotation_samples`/`location_samples` capture is entirely unchanged;
  this only adds a live *display* of data already being collected.
- Interactivity (pan/zoom/tap) on the recording-screen map - stays
  non-interactive, matching `LocationMapView`'s existing behavior.

## Testing

No automated test infrastructure exists for `LocationMapView` (an
`AndroidView`-wrapped osmdroid component) or for `CameraScreen`'s Compose
layout (confirmed - no test file exists for either; matches the pattern
already accepted for `TrimScreen.kt`/`CameraScreen.kt` in this session's
earlier items). `CameraViewModel`'s new `currentHeadingDegrees` state
field is a plain data-class property update inside an existing, already
untested coroutine collection block - not meaningfully different in
testability from the existing `rotationSamples`/`recordingStartedAt`
fields it sits alongside. Verification is manual: build, install, open the
Camera screen, confirm the map appears in the bottom-right corner once a
GPS fix is acquired (and not before), tap Record, confirm a heading
indicator appears and visibly rotates as the phone is turned, stop
recording, confirm the indicator disappears while the map itself remains.
