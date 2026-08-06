# Recording Screen Heading Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a small, always-free-corner map on the Camera recording screen with the current GPS fix and (while recording) the live compass heading, so the user gets visual confirmation that location/direction data is actually being captured.

**Architecture:** `LocationMapView` gains an optional `headingDegrees` parameter that rotates its marker via osmdroid's built-in `Marker.setRotation()`. `CameraViewModel` surfaces its already-collected compass stream (currently only used to build the submitted report's `rotation_samples`) to a new `CameraUiState.currentHeadingDegrees` field. `CameraScreen` renders the map in the bottom-right corner whenever a GPS fix exists, passing the live heading through only while recording.

**Tech Stack:** Kotlin, Jetpack Compose, osmdroid 6.1.20 (existing Android app - no new dependencies).

## Global Constraints

- Map visibility: appears once `CameraUiState.locationState is LocationState.Fixed` - not before (the existing `GpsBadge` already communicates "no fix yet" in text; no placeholder/loading state duplicates that in the map corner).
- Heading arrow visibility: only while recording is active. Resets to `null` in `stopRecording()` so the arrow disappears rather than freezing on a stale value once recording ends.
- Placement: bottom-right corner, 64dp, matching the confirmed mockup selection - the one screen corner never contested by the GPS badge (top-left) or the recording timer (top-right, recording-only).
- `LocationMapView`'s existing call site (`ReportDetailScreen.kt:147`) must render visually unchanged after this plan - same 150dp-tall, full-width map, no rotation (it never passes `headingDegrees`).
- No automated test infrastructure exists for `LocationMapView` (`AndroidView`-wrapped osmdroid) or `CameraScreen`'s Compose layout - verification is manual, on a real device, not a unit test.

---

### Task 1: `LocationMapView` gains an optional heading indicator

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/core/ui/components/LocationMapView.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt:147`

**Interfaces:**
- Produces: `LocationMapView(latitude: Double, longitude: Double, headingDegrees: Float? = null, modifier: Modifier = Modifier): Unit` - the new signature, consumed by Task 3.

- [ ] **Step 1: Move sizing from the component to its callers**

`LocationMapView.kt` currently hardcodes `modifier.fillMaxWidth().height(150.dp)` internally on the `AndroidView`, so a caller's own size intent (e.g. a 64dp square) can't take effect - the component must use the `modifier` it's given as-is, and each caller specifies its own size.

Find this exact block in `app/src/main/java/com/trafficwatch/app/core/ui/components/LocationMapView.kt`:

```kotlin
@Composable
fun LocationMapView(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(150.dp),
```

Replace the signature line and the `AndroidView`'s `modifier` argument (leave everything else in the function body unchanged for now):

```kotlin
@Composable
fun LocationMapView(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
```

- [ ] **Step 2: Preserve `ReportDetailScreen.kt`'s existing appearance**

Its one call site currently passes no modifier at all, relying on the component's old internal default. Since sizing just moved to the caller, this call site must now specify the same size explicitly to render unchanged.

Find this exact line in `app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt` (line 147):

```kotlin
                            LocationMapView(latitude = r.location.latitude, longitude = r.location.longitude)
```

Replace it with:

```kotlin
                            LocationMapView(
                                latitude = r.location.latitude,
                                longitude = r.location.longitude,
                                modifier = Modifier.fillMaxWidth().height(150.dp)
                            )
```

Check the top of `ReportDetailScreen.kt`'s import block: if `androidx.compose.foundation.layout.fillMaxWidth`, `androidx.compose.foundation.layout.height`, and `androidx.compose.ui.unit.dp` are not already imported, add them (`androidx.compose.ui.Modifier` is very likely already imported given this file already builds Compose UI - confirm before adding it again to avoid a duplicate-import compile error).

- [ ] **Step 3: Add the `headingDegrees` parameter and marker rotation**

Find this exact block (now at the top of the function, right after the Step 1 edit):

```kotlin
@Composable
fun LocationMapView(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setMultiTouchControls(false)
                setOnTouchListener { _, _ -> true }
                val point = GeoPoint(latitude, longitude)
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(point)
                overlays.add(Marker(this).apply { position = point })
            }
        },
        update = { mapView ->
            val point = GeoPoint(latitude, longitude)
            mapView.controller.setCenter(point)
            mapView.overlays.clear()
            mapView.overlays.add(Marker(mapView).apply { position = point })
        },
        onRelease = { mapView ->
            mapView.onDetach()
        },
    )
}
```

Replace it with:

```kotlin
@Composable
fun LocationMapView(
    latitude: Double,
    longitude: Double,
    headingDegrees: Float? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setMultiTouchControls(false)
                setOnTouchListener { _, _ -> true }
                val point = GeoPoint(latitude, longitude)
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(point)
                overlays.add(
                    Marker(this).apply {
                        position = point
                        headingDegrees?.let { rotation = it }
                    }
                )
            }
        },
        update = { mapView ->
            val point = GeoPoint(latitude, longitude)
            mapView.controller.setCenter(point)
            mapView.overlays.clear()
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = point
                    headingDegrees?.let { rotation = it }
                }
            )
        },
        onRelease = { mapView ->
            mapView.onDetach()
        },
    )
}
```

(When `headingDegrees` is null - `ReportDetailScreen.kt`'s call site - the marker's `rotation` is simply never set, leaving osmdroid's default unrotated pin, identical to today's behavior.)

- [ ] **Step 4: Confirm the app still builds**

Run: `.\gradlew.bat :app:assembleDebug` (from the repo root)
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/ui/components/LocationMapView.kt app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt
git commit -m "feat: add optional heading rotation to LocationMapView

Moves sizing from the component to its callers (it previously
hardcoded a 150dp height internally, which would have silently
overridden any caller's own size intent) and adds an optional
headingDegrees param that rotates the marker via osmdroid's
Marker.setRotation() - backward compatible, ReportDetailScreen's
existing call site is updated to specify its prior size explicitly
and passes no heading, rendering identically to today."
```

---

### Task 2: `CameraViewModel` surfaces the live compass heading

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`

**Interfaces:**
- Consumes: nothing new - uses the existing `rotationSamplingJob`/`compassProvider.observeHeadings(...)` collection already present in `onStartRecording()`.
- Produces: `CameraUiState.currentHeadingDegrees: Float?`, consumed by Task 3.

- [ ] **Step 1: Add the new state field**

Find this exact block (currently lines 34-37):

```kotlin
data class CameraUiState(
    val locationState: LocationState = LocationState.Acquiring,
    val cameraError: String? = null
)
```

Replace it with:

```kotlin
data class CameraUiState(
    val locationState: LocationState = LocationState.Acquiring,
    val cameraError: String? = null,
    val currentHeadingDegrees: Float? = null
)
```

- [ ] **Step 2: Update the state on every heading emission**

Find this exact block (currently lines 131-133, inside `onStartRecording()`'s `rotationSamplingJob`):

```kotlin
            headings.filterNotNull().collect { heading ->
                rotationSamples.add(RotationSample(capturedAt = System.currentTimeMillis(), headingDegrees = heading))
            }
```

Replace it with:

```kotlin
            headings.filterNotNull().collect { heading ->
                rotationSamples.add(RotationSample(capturedAt = System.currentTimeMillis(), headingDegrees = heading))
                _uiState.update { it.copy(currentHeadingDegrees = heading) }
            }
```

- [ ] **Step 3: Reset the heading when recording stops**

Find this exact block (currently lines 168-173):

```kotlin
    fun stopRecording() {
        maxDurationJob?.cancel()
        samplingJob?.cancel()
        rotationSamplingJob?.cancel()
        cameraController.stopRecording()
    }
```

Replace it with:

```kotlin
    fun stopRecording() {
        maxDurationJob?.cancel()
        samplingJob?.cancel()
        rotationSamplingJob?.cancel()
        cameraController.stopRecording()
        _uiState.update { it.copy(currentHeadingDegrees = null) }
    }
```

- [ ] **Step 4: Confirm the app still builds**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt
git commit -m "feat: surface live compass heading in CameraUiState

CameraUiState gains currentHeadingDegrees, updated from the
existing recording-triggered compass stream (no new sensor
subscription - just surfacing a value already being collected for
rotation_samples). Resets to null when recording stops so a
consumer doesn't display a stale heading afterward."
```

---

### Task 3: Render the map on the Camera screen

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`

**Interfaces:**
- Consumes: `LocationMapView(latitude, longitude, headingDegrees, modifier)` (Task 1), `CameraUiState.currentHeadingDegrees: Float?` (Task 2), `LocationState.Fixed(data: LocationData)` (existing).

No automated test - Compose UI, no test infra for this in the codebase (matches `TrimScreen.kt`/`HistoryScreen.kt` from earlier items this session). Manual on-device verification only.

- [ ] **Step 1: Add the map to the recording screen's layout**

Find this exact block in `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt` (currently lines 148-168, the record/stop FAB):

```kotlin
        // Record / Stop FAB (bottom-centre)
        val locationReady = uiState.locationState is LocationState.Fixed

        FloatingActionButton(
            onClick = {
                if (isRecording) viewModel.stopRecording()
                else if (locationReady) viewModel.onStartRecording(outputFile)
            },
            containerColor = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(72.dp)
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = if (isRecording) "Stop" else "Record",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
```

Add the following directly after this block (still inside the outer `Box(modifier = Modifier.fillMaxSize())`):

```kotlin

        // Location + heading map (bottom-right) - shown once a GPS fix exists; the
        // existing GpsBadge (top-left) already covers "no fix yet" in text, so this
        // corner simply doesn't render until there's something real to show.
        val fixedLocation = uiState.locationState as? LocationState.Fixed
        if (fixedLocation != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 136.dp)
                    .size(64.dp)
            ) {
                LocationMapView(
                    latitude = fixedLocation.data.latitude,
                    longitude = fixedLocation.data.longitude,
                    headingDegrees = uiState.currentHeadingDegrees,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
```

- [ ] **Step 2: Add the new import**

In the same file's import block, add:

```kotlin
import com.trafficwatch.app.core.ui.components.LocationMapView
```

- [ ] **Step 3: Confirm the app builds**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run the full test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (no existing test touches `CameraScreen.kt`, `CameraViewModel.kt`'s `CameraUiState`, or `LocationMapView.kt`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt
git commit -m "feat: show location + heading map on the Camera recording screen

Renders LocationMapView in the bottom-right corner (the one screen
corner never contested by the GPS badge or recording timer) once a
GPS fix exists, passing the live compass heading through only while
recording - closes the Camera/Recording backlog item asking for
visual confirmation that GPS/compass data is actually being
captured."
```

- [ ] **Step 6: Install on device and manually verify**

Run: `.\gradlew.bat :app:installDebug` (device must be connected via adb - run `adb devices` first to confirm)

Manual verification steps:
1. Open the app and navigate to the Camera screen (do not tap Record yet).
2. **Expected**: no map is visible in the bottom-right corner while GPS is still acquiring (`GpsBadge` shows "Acquiring GPS…").
3. Wait for a GPS fix (`GpsBadge` shows "GPS Fixed").
4. **Expected**: a small map appears in the bottom-right corner, centered on the current location, showing a plain (non-rotated) pin.
5. Tap Record.
6. **Expected**: the map stays in place; within a couple seconds the pin gains a visible rotation reflecting the phone's current compass heading.
7. Physically rotate the phone (e.g. turn 90°) while still recording.
8. **Expected**: the pin's rotation visibly updates to track the new heading within a second or two.
9. Tap Stop.
10. **Expected**: the map remains visible (still showing the current location), but the pin returns to its plain, non-rotated appearance.
11. Confirm the map never overlaps or obscures the record/stop button, the GPS badge, or the recording timer at any point in this sequence.
12. Also open `ReportDetailScreen` for any existing report with a location and confirm its map still renders as before (150dp tall, full width, plain non-rotated pin) - no regression from Task 1's signature change.
