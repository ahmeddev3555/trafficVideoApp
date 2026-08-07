# Weak-GPS Location Confirmation Design

## Context

Backlog item (`docs/improvements-backlog.md`, "Location / GPS accuracy",
added 2026-08-02): prompt the user to confirm/correct the exact location
on a map when GPS accuracy is weak. Confirmed real-world impact: a
submitted report with 37.7m GPS accuracy resolved to the wrong OSM street
("Street 4", a small residential road) instead of the actual road in the
video ("Khayaban-e-Jinnah", a major arterial) - the true position was
~56m from the reported point, exceeding both the phone's own accuracy
estimate and the server's fixed 50m Overpass search radius.

The backlog entry itself flagged an unresolved fork: a client-side
map-confirmation UI vs. a server-side fix (scale the Overpass search
radius by reported accuracy). **User explicitly chose the client-side
map-confirmation approach** over the server-only alternative.

## Current state (confirmed via code inspection)

- `ReviewScreen.kt` already displays `location.accuracy` as plain text
  ("GPS Accuracy: ±X m") in its metadata card, alongside latitude/longitude
  - the natural point in the flow to add a correction step, since accuracy
  is already known and shown here.
- `ReviewViewModel.kt`'s `ReviewUiState.location: LocationData?` is the
  single field `submit()` reads to build the report - no other state
  needs to change for a correction to take effect, only this one field.
- The existing `core/ui/components/LocationMapView.kt` is explicitly
  **non-interactive** - `setMultiTouchControls(false)` and an
  `setOnTouchListener { _, _ -> true }` that swallows all touch events,
  by design ("just enough to make a report's location immediately
  visible, not a navigable map"). Retrofitting it for dragging would mean
  bolting two very different interaction modes onto one component.
- `AppNavigation.kt` threads recording-pipeline state (location, samples,
  etc.) through top-level `remember`/`rememberSaveable` Compose state,
  passed down as composable parameters - screens are pushed onto the
  `NavHost` back stack without `popUpTo`, so a screen's own
  `hiltViewModel()` instance survives while a later screen is pushed on
  top and later popped back to.
- `LocationData` (the domain model) already has a `latitude`/`longitude`/
  `accuracy` (and other) fields with a standard `copy()` - correcting a
  location is just `location.copy(latitude = ..., longitude = ...,
  accuracy = ...)`.

## Scope (confirmed with user)

- **Approach:** client-side interactive map, not the server-side
  search-radius alternative (that remains a separate, un-picked-up
  backlog item).
- **Trigger:** the confirmation step only appears when
  `location.accuracy` exceeds a threshold (10m, matching the backlog
  entry's own example) - below that, `ReviewScreen` is completely
  unchanged from today.
- **Presentation:** a dedicated full-screen "Confirm Location" step, not
  a small inline map embedded in the Review card - chosen via a visual
  mockup comparison specifically because a small inline map (~120dp) is
  too cramped to meaningfully verify a position against real streets,
  which matters most exactly when GPS is already unreliable.
- **Movement constraint:** the user cannot move the pin more than
  `accuracy × 1.5` meters from the original GPS-reported point - shown as
  a visible circle overlay on the map, so the boundary is never a
  surprise. Dragging is unconstrained while in progress; on release, if
  the new position is outside the circle, it snaps back to the nearest
  point on the circle's edge rather than silently rejecting the drag.
- **Submitted accuracy after confirmation:** replaced with a small fixed
  "user-confirmed" value (5m) regardless of whether the pin was actually
  moved - once a human has looked at a map and explicitly agreed with (or
  corrected) the position, that's treated as higher-confidence than the
  raw GPS reading it started from.
- **Confirming without moving the pin still counts** as confirmation - it
  submits the original coordinates with the new fixed accuracy, since the
  point of this step is explicit human verification, not necessarily
  correction.

## Architecture

**New screen: `feature/confirmlocation/ConfirmLocationScreen.kt`** (new
package, matching this codebase's existing per-feature package
convention). A full-screen osmdroid `MapView` with:
- `setMultiTouchControls(true)` - real pan/zoom, unlike `LocationMapView`.
- A single draggable `Marker` (`marker.isDraggable = true`) initialized at
  the location passed in, with an `OnMarkerDragListener`.
- A `Polygon` (or `Polyline` forming a circle) overlay centered on the
  *original* GPS point with radius `accuracy × 1.5` meters, drawn once and
  never moved - this is the fixed reference boundary, independent of
  where the marker currently sits.
- `onMarkerDragEnd`: compute the distance and bearing from the *original*
  point to the marker's new position via `android.location.Location.distanceBetween(
  startLat, startLng, endLat, endLng, results: FloatArray)` - a built-in
  Android API (`results[0]` = distance in meters, `results[1]` = initial
  bearing), no custom haversine math needed. Confirmed no distance/bearing
  helper currently exists in the Android app module (the server module's
  `GeoPoint`/`BearingMath` isn't shared with it), but none is needed for
  this direction of the calculation given the built-in.
- If the distance exceeds `accuracy × 1.5`, reposition the marker to the
  nearest point on the boundary circle - same bearing from the original
  point, distance clamped to the radius. Neither `Location` nor osmdroid's
  `GeoPoint` has a built-in "project a point at a given bearing/distance"
  helper (that's the *inverse* of what `distanceBetween` computes), so
  this one small function - `pointAtBearingAndDistance(origin: GeoPoint,
  bearingDegrees: Double, distanceMeters: Double): GeoPoint`, standard
  spherical-projection trigonometry - does need to be written. This is the
  one genuinely new, pure piece of math in this design, and the one
  function called out for direct unit-test coverage below.
- A "Confirm Position" button, enabled at all times (confirming without
  moving is valid), that returns the marker's current lat/lon.

**Navigation wiring:** a new `Routes.CONFIRM_LOCATION` in
`AppNavigation.kt`, pushed from `ReviewScreen` (not replacing it - Review
stays on the back stack so its `ReviewViewModel` instance and the rest of
its state survive the round trip). The confirm screen receives the
current location as navigation arguments (or via the same top-level
Compose state pattern `AppNavigation.kt` already uses for the rest of the
pipeline) and returns the result via Navigation Compose's standard
`NavController.previousBackStackEntry?.savedStateHandle` result-passing
pattern - the officially recommended mechanism for this exact
screen-A-pushes-screen-B-which-returns-a-result shape, and the plan
should use it rather than inventing a new callback mechanism.

**`ReviewViewModel.kt` changes:**
- New `private const val ACCURACY_THRESHOLD_METERS = 10f` and
  `private const val CONFIRMED_ACCURACY_METERS = 5f`.
- New `ReviewUiState.locationConfirmed: Boolean = false` - false whenever
  `location.accuracy > ACCURACY_THRESHOLD_METERS` and confirmation
  hasn't happened yet; the Submit button's `enabled` condition gains
  `&& (location.accuracy <= ACCURACY_THRESHOLD_METERS || locationConfirmed)`.
- New `fun updateLocation(latitude: Double, longitude: Double)`: replaces
  `_uiState.value.location` with
  `location.copy(latitude = latitude, longitude = longitude, accuracy = CONFIRMED_ACCURACY_METERS)`
  and sets `locationConfirmed = true`.

**`ReviewScreen.kt` changes:** the existing GPS Accuracy metadata row
gains a "Confirm Location" button rendered only when
`location.accuracy > ACCURACY_THRESHOLD_METERS && !uiState.locationConfirmed`,
navigating to `Routes.CONFIRM_LOCATION`; a `LaunchedEffect` observing the
nav back stack's saved-state result calls `viewModel.updateLocation(...)`
when a result arrives.

## Non-goals

- The server-side "scale Overpass search radius by accuracy" alternative
  - remains available as a separate, independent backlog item if picked
  up later; this design doesn't touch `StreetDirectionResolver.kt` or
  `OverpassClient.kt` at all.
- Retroactively correcting `locationSamples` (the continuous GPS
  trajectory captured during recording, used for direction/flow analysis)
  - only the single primary `location` field (used for OSM street
  resolution) is affected. Correcting a whole trajectory based on one
  manual pin placement at review time doesn't make sense - `locationSamples`
  serves a different purpose (motion/direction, not street identification)
  and is out of scope here.
- Any change to the `Report` server-side entity, DTOs, or submission
  payload shape - `accuracy`/`latitude`/`longitude` are already submitted
  fields; this only changes what values the app puts in them before
  submission.
- Confirmation for reports where accuracy is already acceptable (≤10m) -
  `ReviewScreen` is untouched in that case.

## Testing

`ConfirmLocationScreen.kt` (an `AndroidView`-wrapped osmdroid `MapView`
with drag-gesture handling) has no automated test infrastructure
available, matching every other osmdroid/Compose-UI component touched
this session (`LocationMapView.kt`, `CameraScreen.kt`, `TrimScreen.kt`) -
verification is manual, on a real device. The one genuinely pure,
unit-testable piece of new logic is `pointAtBearingAndDistance` (see
above) - it should live in a small, dependency-free file (e.g.
`core/util/GeoMath.kt`) so it can get direct unit test coverage with
synthetic coordinates, the same "extract the pure core, leave UI glue
untested" split used for the upload-progress-indicator's
`UploadProgressTracker`.

Manual verification: submit a report with a weak GPS fix (or a debug/mock
location provider forcing low accuracy) and confirm the "Confirm Location"
button appears; open it, confirm the boundary circle is visible and drag
attempts beyond it snap back to the edge; confirm without moving the pin
and verify the submitted report's accuracy reflects the fixed
user-confirmed value; separately, submit a report with a *good* GPS fix
and confirm Review looks and behaves exactly as it does today, with no
Confirm Location button and no behavior change.
