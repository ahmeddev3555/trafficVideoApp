# Confirmed Report Detail: Map, Annotated Frame, Wrong-Way Confidence

## Context

Today, `ReportDetailScreen` shows the same layout for every report status: a
status card (status name, analysis message, plate, plate-read confidence)
and a plain-text metadata card (recorded-at, duration, lat/long as numbers,
GPS accuracy, server report ID). For a `CONFIRMED` report specifically, this
under-serves what the app is actually for - a user who reported a wrong-way
violation has no visual confirmation of *where* it happened or *which
vehicle* was flagged, and the only confidence number shown is actually the
license-plate OCR confidence, not a measure of how confident the system is
that the vehicle was genuinely going the wrong way.

This spec adds, for `CONFIRMED` reports only: a small map pinning the exact
location, an image of the flagged vehicle with a red box drawn around it,
and a new, separately-computed wrong-way confidence score.

This lands on top of the (currently unmerged) `feature/real-video-analysis`
branch, which already gives this work its raw materials: `latitude`/
`longitude`/`streetName` per report, and a real (non-stub) analysis pipeline
with a Python CV service (YOLOv8 + ByteTrack + EasyOCR) the Kotlin server
calls per report.

## Scope

In scope: the three additions above, for newly-`CONFIRMED` reports going
forward.

Explicitly out of scope (decided during brainstorming):
- **No backfill.** Reports already `CONFIRMED` before this ships simply
  won't have a frame image or a wrong-way confidence score (that data was
  never computed for them) - the detail screen degrades gracefully rather
  than attempting to reprocess old videos (which may not even still exist
  on disk - the source video is deleted after a successful upload).
- **No Google Maps.** The map uses OpenStreetMap via osmdroid - free, no API
  key/billing account, and consistent with the server already using OSM for
  street/direction lookups.
- **The red box is drawn server-side** (in Kotlin, from data Python
  supplies), not by the Android app. The app only ever displays one already
  -annotated image.
- No changes to `PENDING`/`REJECTED` report layouts.

## Data model changes

**Python (`video-analysis/app/schemas.py`)**: `VehicleResult` gains a
base64-encoded JPEG of the vehicle's existing largest-bounding-box frame
(the same frame already selected for OCR - no new frame-selection logic)
plus its bounding box (x/y/width/height) within that frame. Returned for
*every* tracked vehicle, not just an eventual "winner" - Python has no
concept of legal direction/wrong-way and can't know in advance which
vehicle Kotlin will pick, and it stays stateless (no second round-trip).
Given a report clip is short with only a handful of vehicles, this is a
small, bounded cost.

**Server `Report` entity** (new Flyway migration): two new nullable
columns.
- `wrong_way_frame_path: String?` - path to the saved annotated JPEG, same
  convention as `video_path`. Null means "no frame available" (old report,
  or the rare case where annotation/save fails even though a wrong-way
  vehicle was found) - the app treats both identically.
- `wrong_way_confidence: BigDecimal?` - the new score (0.0-1.0, shown as
  0-100%). Kept **separate** from the existing `confidence` column, which
  keeps meaning what it means today (license-plate OCR confidence) -
  conflating the two would make the existing field misleading.

**`ReportStatusResponse` DTO**: gains `hasWrongWayFrame: Boolean` and
`wrongWayConfidence: BigDecimal?`.

**Android `Report` domain model / Room entity**: mirrors both new fields
(`hasWrongWayFrame: Boolean`, `wrongWayConfidence: Float?`), synced from
`ReportStatusResponse` like every other field already is.

## Frame capture, annotation, and serving

Kotlin (`ReportAnalysisJob`), once it picks the wrong-way vehicle (same
selection logic as today), draws the red rectangle onto *only that one*
vehicle's frame using plain Java AWT (`BufferedImage` +
`Graphics2D.drawRect` - standard library, no new Gradle dependency), then
saves it via a small addition to the existing storage pattern
(`<videoDirectory>/frames/<reportId>.jpg`) and sets `wrongWayFramePath`.
Every other candidate vehicle's frame data is discarded in memory - never
persisted, never sent further.

**New endpoint**: `GET /reports/{reportId}/wrong-way-frame` - same
per-user ownership/404 scoping as the existing `GET /reports/{reportId}/status`,
plus a 404 when `wrongWayFramePath` is null. Returns raw JPEG bytes,
`Content-Type: image/jpeg`. First time this API serves binary media back,
but a standard, unremarkable Spring pattern (`ResponseEntity<Resource>` or
equivalent).

## Wrong-way confidence score

Two signals already exist per candidate vehicle by the time a winner is
picked, both currently unused beyond a binary threshold check:
- `detectionConfidence` - YOLO's confidence this is actually a vehicle.
- Angular distance between the vehicle's absolute bearing and the illegal
  (opposite-of-legal) direction - today only checked against
  `wrongWayToleranceDegrees` as a yes/no gate; the actual distance is
  discarded once it passes.

```
bearingMatchScore = 1 - (angularDistance / wrongWayToleranceDegrees)   // 1.0 = dead-on opposite the legal direction, -> 0 at the edge of tolerance
wrongWayConfidence = detectionConfidence * bearingMatchScore
```

A vehicle moving almost exactly opposite the legal direction, that YOLO is
very sure is a vehicle, scores near 100%. A borderline case that barely
squeaked inside the tolerance window (plausibly a crossing/turning vehicle,
not genuinely wrong-way) scores much lower even though it still passes
today's flat threshold - exactly the nuance a single confidence number
should surface. Requires no new data from Python - purely a new calculation
inside `ReportAnalysisJob`, using inputs that already exist server-side.

## Android: map component

New dependency: `org.osmdroid:osmdroid-android` (no API key, no billing).

New composable `core/ui/components/LocationMapView.kt` - a small, fixed
-height (~150dp), non-interactive (no pan/zoom) map card: an osmdroid
`MapView` wrapped for Compose via `AndroidView`, centered on the report's
lat/long at a fixed zoom appropriate for "show which street this is," with
one pin marker at that exact point. Not meant to replace the existing
`street_name` text - just makes the location immediately visible. Uses
`INTERNET` (already granted); osmdroid's local tile cache lives in
app-private storage, no new runtime permission needed.

## `ReportDetailScreen` layout changes

Added only when `status == CONFIRMED`; `PENDING`/`REJECTED` layouts are
unchanged.

1. **Map card** (new, top of the CONFIRMED-only section):
   `LocationMapView(latitude, longitude)`.
2. **Wrong-way vehicle card** (new): if `hasWrongWayFrame`, a Coil
   `AsyncImage` loading `GET /reports/{id}/wrong-way-frame` through the
   app's existing authenticated OkHttp client (the same one
   `AuthInterceptor` already attaches the JWT to for every request - Coil
   just needs to be configured to reuse it). If `hasWrongWayFrame` is
   false, this card is omitted entirely - no broken-image placeholder.
3. **Confidence rows** in the existing Status card: relabel today's
   `DetailRow("Confidence", ...)` to **"Plate Read Confidence"** (still a
   real, useful number - just not the one being added), and add
   `DetailRow("Wrong-Way Confidence", "${(wrongWayConfidence * 100).roundToInt()}%")`
   when `wrongWayConfidence != null`.

## Error handling / graceful degradation

- Old `CONFIRMED` reports (predating this feature): `hasWrongWayFrame =
  false`, `wrongWayConfidence = null` -> frame card and new confidence row
  both simply don't render. The map card still renders (lat/long always
  existed) - the one part of this feature that works retroactively for
  free.
- Frame capture failing for a *new* report (AWT drawing throws, disk write
  fails): caught in `ReportAnalysisJob`, logged, `wrongWayFramePath` stays
  null - doesn't fail the whole analysis or block `CONFIRMED` status; the
  report ends up in the same "no frame" state as an old report.
- `GET /wrong-way-frame` for a report that isn't the caller's, doesn't
  exist, or has no frame: 404, matching the existing `getStatus` convention
  exactly.

## Testing

- **Python**: unit test that a track's chosen frame + bbox survive into
  `VehicleResult` unchanged (pure data, no model inference needed - same
  style as existing `test_bearing.py`).
- **Kotlin**: unit test for the AWT annotation step (draw box, verify
  output image dimensions/non-null bytes); table-driven unit test for the
  `wrongWayConfidence` formula over angular-distance/detection-confidence
  inputs; controller test for the new endpoint's 404/ownership scoping,
  mirroring existing `ReportControllerTest` patterns.
- **Android**: manual verification on the connected device against a real
  `CONFIRMED` report (same pattern used throughout this project) - map
  renders at the right pin, frame image loads with the red box visible,
  both confidence numbers display correctly; and against an old/pre-feature
  `CONFIRMED` report to confirm graceful omission of the new sections.
