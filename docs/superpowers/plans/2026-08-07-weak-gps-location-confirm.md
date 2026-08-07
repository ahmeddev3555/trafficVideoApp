# Weak-GPS Location Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a report's captured GPS accuracy is weak (> 10m), require the user to confirm or correct their location on an interactive map - constrained to a radius of `accuracy × 1.5` from the original GPS point - before they can submit the report.

**Architecture:** A pure `pointAtBearingAndDistance` function (no Android dependencies) backs the boundary-clamping math. A new `ConfirmLocationScreen` wraps an interactive (pan/zoom/draggable-marker) osmdroid `MapView`, distinct from the existing non-interactive `LocationMapView`. `ReviewViewModel` gains a `SavedStateHandle`-based result channel (the standard Navigation Compose pattern for one screen returning a value to the screen that pushed it) and gates the Submit button on confirmation having happened. `ReviewScreen`/`AppNavigation` wire the new screen into the existing Camera→Trim→Review pipeline.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, osmdroid 6.1.20, Hilt (existing Android app - no new dependencies).

## Global Constraints

- Approach is client-side only - no server-side changes (`StreetDirectionResolver.kt`/`OverpassClient.kt` untouched). The server-side search-radius alternative remains a separate, un-picked-up backlog item.
- `ACCURACY_THRESHOLD_METERS = 10f` - the confirm-location step only appears when `location.accuracy` exceeds this. Below it, `ReviewScreen` is unchanged from today.
- `CONFIRMED_ACCURACY_METERS = 5f` - replaces the submitted accuracy after confirmation, regardless of whether the pin was actually moved.
- Movement constraint: the draggable pin cannot end a drag more than `accuracy × 1.5` meters from the *original* GPS point. A visible circle overlay shows this boundary. Dragging itself is unconstrained in-progress; only on release does an out-of-bounds position snap back to the nearest point on the boundary circle.
- `locationSamples` (the continuous GPS trajectory) is never touched by this plan - only the single primary `location` field.
- No automated test infrastructure exists for `LocationMapView`/`CameraScreen`/`TrimScreen`-style osmdroid/Compose-UI components in this codebase - `ConfirmLocationScreen.kt` and the `ReviewScreen.kt`/`AppNavigation.kt` wiring get no automated tests; verification for those is manual, on a real device.

---

### Task 1: Pure boundary-clamping math

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/core/util/GeoMath.kt`
- Test: `app/src/test/java/com/trafficwatch/app/core/util/GeoMathTest.kt`

**Interfaces:**
- Produces: `pointAtBearingAndDistance(origin: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint` - consumed by Task 2.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/trafficwatch/app/core/util/GeoMathTest.kt`:

```kotlin
package com.trafficwatch.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.GeoPoint

class GeoMathTest {

    @Test
    fun `zero distance returns the same point regardless of bearing`() {
        val origin = GeoPoint(31.5204, 74.3587)

        val result = pointAtBearingAndDistance(origin, bearingDegrees = 45.0, distanceMeters = 0.0)

        assertEquals(origin.latitude, result.latitude, 1e-9)
        assertEquals(origin.longitude, result.longitude, 1e-9)
    }

    @Test
    fun `due north movement changes only latitude, by exactly the angular distance`() {
        val origin = GeoPoint(31.5204, 74.3587)
        // angularDistance = distanceMeters / EARTH_RADIUS_METERS = 63710.0 / 6371000.0 = 0.01 rad exactly
        val distanceMeters = 63710.0

        val result = pointAtBearingAndDistance(origin, bearingDegrees = 0.0, distanceMeters = distanceMeters)

        // For bearing 0, lat2 = lat1 + angularDistance (spherical angle-addition identity) -
        // toDegrees(0.01) = 0.5729577951308232
        assertEquals(31.5204 + 0.5729577951308232, result.latitude, 1e-6)
        assertEquals(74.3587, result.longitude, 1e-6)
    }

    @Test
    fun `due east movement at the equator changes only longitude, by exactly the angular distance`() {
        val origin = GeoPoint(0.0, 0.0)
        val distanceMeters = 63710.0 // angularDistance = 0.01 rad exactly

        val result = pointAtBearingAndDistance(origin, bearingDegrees = 90.0, distanceMeters = distanceMeters)

        assertEquals(0.0, result.latitude, 1e-6)
        assertEquals(0.5729577951308232, result.longitude, 1e-6)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.GeoMathTest"`
Expected: FAIL - `pointAtBearingAndDistance` does not exist yet (compile error).

- [ ] **Step 3: Create `GeoMath.kt`**

Create `app/src/main/java/com/trafficwatch/app/core/util/GeoMath.kt`:

```kotlin
package com.trafficwatch.app.core.util

import org.osmdroid.util.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val EARTH_RADIUS_METERS = 6371000.0

/**
 * The point [distanceMeters] from [origin] along initial bearing [bearingDegrees] (clockwise
 * from north), using standard spherical-projection trigonometry. Pure function - no Android
 * dependencies - so it's directly unit-testable.
 */
fun pointAtBearingAndDistance(origin: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
    val bearingRad = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(origin.latitude)
    val lon1 = Math.toRadians(origin.longitude)
    val angularDistance = distanceMeters / EARTH_RADIUS_METERS

    val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearingRad))
    val lon2 = lon1 + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )

    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.GeoMathTest"`
Expected: PASS (3/3)

- [ ] **Step 5: Run the full test suite to confirm nothing else broke**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/util/GeoMath.kt app/src/test/java/com/trafficwatch/app/core/util/GeoMathTest.kt
git commit -m "feat: add pointAtBearingAndDistance for location-boundary clamping

Pure spherical-projection function (no Android dependencies) that
computes the point a given distance/bearing from an origin -
directly unit-testable with hand-verified synthetic coordinates,
used to clamp a manually-dragged location pin back onto its allowed
boundary circle."
```

---

### Task 2: Interactive confirm-location map screen

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/feature/confirmlocation/ConfirmLocationScreen.kt`

**Interfaces:**
- Consumes: `pointAtBearingAndDistance(origin, bearingDegrees, distanceMeters): GeoPoint` (Task 1).
- Produces: `ConfirmLocationScreen(initialLatitude: Double, initialLongitude: Double, maxRadiusMeters: Double, onConfirm: (latitude: Double, longitude: Double) -> Unit, onNavigateBack: () -> Unit): Unit` - consumed by Task 4.

No automated test - Compose UI + osmdroid `MapView`/`Marker` drag handling, no test infra for this in the codebase (matches `LocationMapView.kt`/`CameraScreen.kt`/`TrimScreen.kt` from earlier work this session). Verification is manual, in Task 4's final on-device checklist.

- [ ] **Step 1: Create `ConfirmLocationScreen.kt`**

Create `app/src/main/java/com/trafficwatch/app/feature/confirmlocation/ConfirmLocationScreen.kt`:

```kotlin
package com.trafficwatch.app.feature.confirmlocation

import android.graphics.Color
import android.location.Location
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import com.trafficwatch.app.core.util.pointAtBearingAndDistance

private const val DEFAULT_ZOOM = 17.0

/**
 * Full-screen, interactive (pan/zoom/drag) map for confirming or correcting a report's
 * location when its captured GPS accuracy was weak. Distinct from [com.trafficwatch.app.core.ui.components.LocationMapView],
 * which is deliberately non-interactive - this screen needs real dragging and a boundary
 * overlay, different enough interaction modes that sharing one component would tangle both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmLocationScreen(
    initialLatitude: Double,
    initialLongitude: Double,
    maxRadiusMeters: Double,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onNavigateBack: () -> Unit
) {
    val originPoint = remember { GeoPoint(initialLatitude, initialLongitude) }
    val confirmedPosition = remember { mutableStateOf(GeoPoint(initialLatitude, initialLongitude)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm Location") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        MapView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setMultiTouchControls(true)
                            controller.setZoom(DEFAULT_ZOOM)
                            controller.setCenter(originPoint)

                            val boundary = Polygon(this).apply {
                                points = Polygon.pointsAsCircle(originPoint, maxRadiusMeters)
                                fillPaint.color = Color.argb(30, 255, 0, 0)
                                outlinePaint.color = Color.RED
                                outlinePaint.strokeWidth = 3f
                            }
                            overlays.add(boundary)

                            val marker = Marker(this).apply {
                                position = confirmedPosition.value
                                isDraggable = true
                                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                                    override fun onMarkerDrag(marker: Marker) = Unit
                                    override fun onMarkerDragStart(marker: Marker) = Unit
                                    override fun onMarkerDragEnd(marker: Marker) {
                                        val results = FloatArray(2)
                                        Location.distanceBetween(
                                            originPoint.latitude, originPoint.longitude,
                                            marker.position.latitude, marker.position.longitude,
                                            results
                                        )
                                        val distanceMeters = results[0]
                                        val bearingDegrees = results[1]
                                        if (distanceMeters > maxRadiusMeters) {
                                            val clamped = pointAtBearingAndDistance(
                                                originPoint, bearingDegrees.toDouble(), maxRadiusMeters
                                            )
                                            marker.position = clamped
                                            invalidate()
                                        }
                                        confirmedPosition.value = marker.position
                                    }
                                })
                            }
                            overlays.add(marker)
                        }
                    },
                    onRelease = { mapView -> mapView.onDetach() },
                )
            }

            Button(
                onClick = { onConfirm(confirmedPosition.value.latitude, confirmedPosition.value.longitude) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Confirm Position") }
        }
    }
}
```

- [ ] **Step 2: Confirm the app builds**

Run: `./gradlew.bat :app:assembleDebug` (from the repo root)
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/confirmlocation/ConfirmLocationScreen.kt
git commit -m "feat: add interactive confirm-location map screen

New ConfirmLocationScreen wraps an interactive (pan/zoom/draggable-
marker) osmdroid MapView with a visible boundary-circle overlay at
maxRadiusMeters from the original point. Dragging is unconstrained
in-progress; on release, an out-of-bounds position snaps back to the
nearest point on the boundary circle via pointAtBearingAndDistance."
```

---

### Task 3: `ReviewViewModel` confirmation state and result handling

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`
- Test: `app/src/test/java/com/trafficwatch/app/feature/review/ReviewViewModelTest.kt` (existing file - add new test cases)

**Interfaces:**
- Produces: `ReviewViewModel.KEY_CONFIRMED_LOCATION: String` (companion constant, the `SavedStateHandle` key), `ReviewViewModel.ACCURACY_THRESHOLD_METERS: Float` (top-level constant), `ReviewUiState.locationConfirmed: Boolean`, `ReviewViewModel.updateLocation(latitude: Double, longitude: Double): Unit` - consumed by Task 4.

- [ ] **Step 1: Write the failing tests**

First, read the existing `app/src/test/java/com/trafficwatch/app/feature/review/ReviewViewModelTest.kt` in full to see its current `@Before setUp()` (specifically how `ReviewViewModel` is constructed - it will need a second constructor argument added) and its existing test method style, so the new tests you add match its conventions exactly (same `testDispatcher`/`mockk` patterns already in the file).

Add these test methods to the existing `ReviewViewModelTest.kt` class body (do not remove or modify any existing test):

```kotlin
    @Test
    fun `updateLocation replaces lat-lon and accuracy, and marks location confirmed`() {
        val savedStateHandle = SavedStateHandle()
        val viewModel = ReviewViewModel(submitReportUseCase, savedStateHandle)
        viewModel.init(testFile, location, locationSamples, emptyList(), 1000L, 8000L)

        viewModel.updateLocation(latitude = 31.6, longitude = 74.4)

        val updated = viewModel.uiState.value.location
        assertEquals(31.6, updated?.latitude)
        assertEquals(74.4, updated?.longitude)
        assertEquals(ReviewViewModel.CONFIRMED_ACCURACY_METERS, updated?.accuracy)
        assertTrue(viewModel.uiState.value.locationConfirmed)
    }

    @Test
    fun `a value posted to the SavedStateHandle confirmed-location key triggers updateLocation`() = runTest {
        val savedStateHandle = SavedStateHandle()
        val viewModel = ReviewViewModel(submitReportUseCase, savedStateHandle)
        viewModel.init(testFile, location, locationSamples, emptyList(), 1000L, 8000L)

        savedStateHandle[ReviewViewModel.KEY_CONFIRMED_LOCATION] = doubleArrayOf(31.7, 74.5)
        testDispatcher.scheduler.advanceUntilIdle()

        val updated = viewModel.uiState.value.location
        assertEquals(31.7, updated?.latitude)
        assertEquals(74.5, updated?.longitude)
        assertTrue(viewModel.uiState.value.locationConfirmed)
    }
```

Add the necessary new imports to the top of the test file if not already present: `androidx.lifecycle.SavedStateHandle`, `kotlinx.coroutines.test.runTest`, `org.junit.Assert.assertEquals`, `org.junit.Assert.assertTrue` (check the existing import block first - some of these, e.g. `runTest`/`assertTrue`, may already be imported; do not add a duplicate import).

Update the existing `@Before setUp()` method's `ReviewViewModel(submitReportUseCase)` construction call to `ReviewViewModel(submitReportUseCase, SavedStateHandle())` - the constructor signature is changing in Step 3 below, so every existing construction call in this file must be updated to match, not just the two new tests above.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.review.ReviewViewModelTest"`
Expected: FAIL - `ReviewViewModel` doesn't accept a second constructor argument yet, `updateLocation`/`KEY_CONFIRMED_LOCATION`/`CONFIRMED_ACCURACY_METERS`/`locationConfirmed` don't exist yet (compile error).

- [ ] **Step 3: Update `ReviewViewModel.kt`**

Replace the full contents of `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt` with:

```kotlin
package com.trafficwatch.app.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample
import com.trafficwatch.app.core.domain.usecase.SubmitReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ReviewUiState(
    val trimmedFilePath: String = "",
    val location: LocationData? = null,
    val locationSamples: List<LocationData> = emptyList(),
    val rotationSamples: List<RotationSample> = emptyList(),
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val showCellularPrompt: Boolean = false,
    val isSubmitting: Boolean = false,
    val locationConfirmed: Boolean = false
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val submitReportUseCase: SubmitReportUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState = _uiState.asStateFlow()

    // Buffered (not conflated) so a send that happens before the collector attaches - e.g.
    // ReviewScreen's LaunchedEffect racing the coroutine launched by submit() - is never
    // dropped; ReviewScreen only ever calls submit() once per screen instance, so at most
    // one item is ever buffered in practice.
    private val _submitted = Channel<Unit>(Channel.BUFFERED)
    val submitted: Flow<Unit> = _submitted.receiveAsFlow()

    private var lastReportId: String? = null
    private var lastEffectiveLocation: LocationData? = null

    init {
        observeConfirmedLocationResult()
    }

    // ConfirmLocationScreen (pushed on top of Review, not replacing it) posts a result here
    // via NavController.previousBackStackEntry's SavedStateHandle - the standard Navigation
    // Compose pattern for one screen returning a value to the screen that pushed it, without
    // either screen needing direct references to the other. DoubleArray is a natively
    // Bundle-safe type, avoiding any Parcelable requirement on LocationData.
    private fun observeConfirmedLocationResult() {
        viewModelScope.launch {
            savedStateHandle.getStateFlow<DoubleArray?>(KEY_CONFIRMED_LOCATION, null).collect { confirmed ->
                if (confirmed != null && confirmed.size == 2) {
                    updateLocation(confirmed[0], confirmed[1])
                    // Clear immediately so this doesn't re-fire on a later recomposition/
                    // process-death-restore replaying the same saved value.
                    savedStateHandle[KEY_CONFIRMED_LOCATION] = null
                }
            }
        }
    }

    fun init(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        _uiState.update {
            it.copy(
                trimmedFilePath = trimmedFile.absolutePath,
                location = location,
                locationSamples = locationSamples,
                rotationSamples = rotationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length()
            )
        }
    }

    /**
     * Replaces the current location's latitude/longitude with a user-confirmed/corrected
     * position from [com.trafficwatch.app.feature.confirmlocation.ConfirmLocationScreen], and
     * replaces its accuracy with [CONFIRMED_ACCURACY_METERS] - once a human has looked at a
     * map and explicitly agreed with (or corrected) the position, that's treated as
     * higher-confidence than the raw GPS reading it started from, regardless of whether the
     * pin was actually moved.
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        _uiState.update { state ->
            val current = state.location ?: return@update state
            state.copy(
                location = current.copy(latitude = latitude, longitude = longitude, accuracy = CONFIRMED_ACCURACY_METERS),
                locationConfirmed = true
            )
        }
    }

    fun submit() {
        if (_uiState.value.isSubmitting) return
        val state = _uiState.value
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.locationSamples, state.rotationSamples, state.recordingStartedAt, state.durationMs
            )
            lastReportId = result.reportId
            lastEffectiveLocation = result.effectiveLocation
            if (result.onWifi) {
                _uiState.update { it.copy(isSubmitting = false) }
                _submitted.send(Unit)
            } else {
                _uiState.update { it.copy(showCellularPrompt = true, isSubmitting = false) }
            }
        }
    }

    /** User explicitly confirmed uploading over cellular data for the current submission. */
    fun confirmCellularSubmit() {
        if (_uiState.value.isSubmitting) return
        val reportId = lastReportId ?: return
        val location = lastEffectiveLocation ?: return
        val state = _uiState.value
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            submitReportUseCase.confirmCellular(
                reportId, state.trimmedFilePath, location, state.locationSamples, state.rotationSamples, state.recordingStartedAt, state.durationMs
            )
            _uiState.update { it.copy(showCellularPrompt = false, isSubmitting = false) }
            _submitted.send(Unit)
        }
    }

    /** User dismissed the cellular prompt - the Wi-Fi-only enqueue from submit() already stands. */
    fun dismissCellularPrompt() {
        _uiState.update { it.copy(showCellularPrompt = false) }
        viewModelScope.launch { _submitted.send(Unit) }
    }

    companion object {
        const val ACCURACY_THRESHOLD_METERS = 10f
        const val CONFIRMED_ACCURACY_METERS = 5f
        const val KEY_CONFIRMED_LOCATION = "confirmed_location"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.review.ReviewViewModelTest"`
Expected: PASS (all tests in the file, including the two new ones)

- [ ] **Step 5: Run the full test suite to confirm nothing else broke**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt app/src/test/java/com/trafficwatch/app/feature/review/ReviewViewModelTest.kt
git commit -m "feat: add location-confirmation state to ReviewViewModel

ReviewViewModel now injects SavedStateHandle (the standard
Navigation Compose result-passing mechanism) and observes a
DoubleArray posted under KEY_CONFIRMED_LOCATION, calling the new
updateLocation() which replaces lat/lon and sets accuracy to
CONFIRMED_ACCURACY_METERS (5m), marking locationConfirmed true -
regardless of whether the pin was actually moved from its original
position."
```

---

### Task 4: Wire the confirm-location step into Review and navigation

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`

**Interfaces:**
- Consumes: `ConfirmLocationScreen(initialLatitude, initialLongitude, maxRadiusMeters, onConfirm, onNavigateBack)` (Task 2), `ReviewViewModel.ACCURACY_THRESHOLD_METERS`, `ReviewViewModel.KEY_CONFIRMED_LOCATION`, `ReviewUiState.locationConfirmed` (Task 3).

No automated test - Compose UI/navigation wiring, no test infra for this in the codebase. Manual on-device verification is this task's final step.

- [ ] **Step 1: Add the "Confirm Location" button and gate Submit in `ReviewScreen.kt`**

Find this exact block in `app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt` (the function signature, currently lines 58-69):

```kotlin
@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun ReviewScreen(
    trimmedFile: File,
    location: LocationData?,
    locationSamples: List<LocationData>,
    rotationSamples: List<RotationSample>,
    recordingStartedAt: Long,
    durationMs: Long,
    onSubmit: () -> Unit,
    onRetrim: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
```

Replace it with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun ReviewScreen(
    trimmedFile: File,
    location: LocationData?,
    locationSamples: List<LocationData>,
    rotationSamples: List<RotationSample>,
    recordingStartedAt: Long,
    durationMs: Long,
    onSubmit: () -> Unit,
    onRetrim: () -> Unit,
    onNavigateBack: () -> Unit,
    onConfirmLocation: (LocationData) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
```

Find this exact block, including both closing braces at the end (currently lines 142-154 - the `if`/`else` for the location metadata, followed by the closing brace of the inner `Column` and the closing brace of the `Card`):

```kotlin
                        if (location != null) {
                            HorizontalDivider()
                            MetadataRow("Latitude", "%.6f°".format(location.latitude))
                            HorizontalDivider()
                            MetadataRow("Longitude", "%.6f°".format(location.longitude))
                            HorizontalDivider()
                            MetadataRow("GPS Accuracy", "±%.0f m".format(location.accuracy))
                        } else {
                            HorizontalDivider()
                            MetadataRow("Location", "Not available")
                        }
                    }
                }
```

Replace it with (identical up through the `Card`'s closing brace, with the new conditional button appended immediately after - this button lives inside the outer `Column(modifier = Modifier.padding(16.dp))` from line 131, as a sibling to the `Card`, not inside it):

```kotlin
                        if (location != null) {
                            HorizontalDivider()
                            MetadataRow("Latitude", "%.6f°".format(location.latitude))
                            HorizontalDivider()
                            MetadataRow("Longitude", "%.6f°".format(location.longitude))
                            HorizontalDivider()
                            MetadataRow("GPS Accuracy", "±%.0f m".format(location.accuracy))
                        } else {
                            HorizontalDivider()
                            MetadataRow("Location", "Not available")
                        }
                    }
                }

                val weakAccuracyUnconfirmed = location != null &&
                    location.accuracy > ReviewViewModel.ACCURACY_THRESHOLD_METERS &&
                    !uiState.locationConfirmed
                if (weakAccuracyUnconfirmed) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { onConfirmLocation(location!!) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Confirm Location") }
                }
```

Find this exact block (currently lines 158-162, the Submit button):

```kotlin
                Button(
                    onClick = viewModel::submit,
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Submit Report") }
```

Replace it with:

```kotlin
                val locationBlocksSubmit = location != null &&
                    location.accuracy > ReviewViewModel.ACCURACY_THRESHOLD_METERS &&
                    !uiState.locationConfirmed
                Button(
                    onClick = viewModel::submit,
                    enabled = !uiState.isSubmitting && !locationBlocksSubmit,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Submit Report") }
```

- [ ] **Step 2: Wire the new route in `AppNavigation.kt`**

Find this exact line in `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt` (inside `private object Routes`, currently line 38):

```kotlin
    const val REVIEW = "review"
```

Add directly after it:

```kotlin
    const val REVIEW = "review"
    const val CONFIRM_LOCATION = "confirm_location"
```

Find this exact line (currently line 29, the last import before `java.io.File`):

```kotlin
import com.trafficwatch.app.feature.trim.TrimScreen
import java.io.File
```

Replace it with:

```kotlin
import com.trafficwatch.app.feature.confirmlocation.ConfirmLocationScreen
import com.trafficwatch.app.feature.trim.TrimScreen
import java.io.File
```

Find this exact line (currently line 55, in the "Shared state threaded through the recording pipeline" block):

```kotlin
    var snapshotLocation by remember { mutableStateOf<LocationData?>(null) }
```

Add directly after it:

```kotlin
    var snapshotLocation by remember { mutableStateOf<LocationData?>(null) }
    var locationForConfirmation by remember { mutableStateOf<LocationData?>(null) }
```

Find this exact block (the `ReviewScreen(...)` call inside `composable(Routes.REVIEW) { ... }`, currently lines 180-200):

```kotlin
            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                locationSamples = filteredLocationSamples,
                rotationSamples = filteredRotationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    locationSamples = emptyList()
                    rotationSamples = emptyList()
                    trimStartMs = 0L
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
```

Replace it with:

```kotlin
            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                locationSamples = filteredLocationSamples,
                rotationSamples = filteredRotationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    locationSamples = emptyList()
                    rotationSamples = emptyList()
                    trimStartMs = 0L
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() },
                onConfirmLocation = { location ->
                    locationForConfirmation = location
                    navController.navigate(Routes.CONFIRM_LOCATION)
                }
            )
        }

        composable(Routes.CONFIRM_LOCATION) {
            val loc = locationForConfirmation ?: return@composable
            ConfirmLocationScreen(
                initialLatitude = loc.latitude,
                initialLongitude = loc.longitude,
                maxRadiusMeters = loc.accuracy * 1.5,
                onConfirm = { latitude, longitude ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(ReviewViewModel.KEY_CONFIRMED_LOCATION, doubleArrayOf(latitude, longitude))
                    navController.popBackStack()
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
```

Add the `ReviewViewModel` import needed for `ReviewViewModel.KEY_CONFIRMED_LOCATION`. Find this exact line (currently line 28):

```kotlin
import com.trafficwatch.app.feature.review.ReviewScreen
```

Replace it with:

```kotlin
import com.trafficwatch.app.feature.review.ReviewScreen
import com.trafficwatch.app.feature.review.ReviewViewModel
```

- [ ] **Step 3: Confirm the app builds**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt
git commit -m "feat: wire weak-GPS location confirmation into Review and navigation

ReviewScreen shows a 'Confirm Location' button and blocks Submit
whenever accuracy exceeds ACCURACY_THRESHOLD_METERS (10m) and
locationConfirmed is still false. AppNavigation pushes the new
ConfirmLocationScreen (not replacing Review, so ReviewViewModel's
instance survives the round trip) and posts the result back via the
standard NavController.previousBackStackEntry SavedStateHandle
pattern - closes the weak-GPS-accuracy backlog item."
```

- [ ] **Step 6: Install on device and manually verify**

Run: `./gradlew.bat :app:installDebug` (device must be connected via adb - run `adb devices` first to confirm)

Manual verification steps:
1. Record and trim a clip somewhere with a strong GPS fix (accuracy ≤ 10m, e.g. outdoors with a clear sky view).
2. On the Review screen, confirm it looks and behaves exactly as it does today - no "Confirm Location" button, Submit works immediately.
3. Record another clip somewhere GPS accuracy is likely to be weak (indoors, near tall buildings), or use a debug/mock location provider to force a low-accuracy fix.
4. On Review, confirm the "Confirm Location" button appears, and that the Submit button is disabled (or shows as such) until it's used.
5. Tap "Confirm Location". Confirm the full-screen map opens, centered on the reported point, with a visible circle boundary.
6. Try dragging the pin to a point clearly outside the circle. Confirm it snaps back to the edge of the circle on release, not to wherever it was dropped.
7. Drag the pin to a point clearly inside the circle. Confirm it stays exactly where dropped (no unwanted clamping).
8. Tap "Confirm Position" without having moved the pin at all. Confirm it returns to Review, the button disappears, and Submit is now enabled.
9. Repeat steps 3-6, this time actually moving the pin within bounds before tapping "Confirm Position". Submit the report and confirm (via the server/DB, or the report detail screen once analysis completes) that the corrected latitude/longitude were what got submitted, with accuracy reflecting the new fixed confirmed value (5m), not the original weak GPS accuracy.
10. From Review, tap "Confirm Location" then use the back arrow (not "Confirm Position") to return without confirming. Confirm Submit is still disabled and the button is still present - backing out must not count as confirmation.
