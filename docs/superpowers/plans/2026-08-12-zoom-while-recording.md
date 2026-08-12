# Zoom While Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user zoom up to 2x while recording (pinch gesture + quick-select
buttons, locked once recording starts), and make the video-analysis math that's
genuinely zoom-sensitive scale correctly with the zoom level used.

**Architecture:** Zoom ratio is captured once at recording start (same
one-shot-snapshot shape as the existing compass heading) and threaded through
as one new optional field, end to end: Android capture → multipart upload →
`Report.zoomRatio` column → the existing `/v1/analyze` call to the Python
video-analysis service, which uses it to scale two pixel-space thresholds that
were traced and confirmed to be genuinely zoom-sensitive. Everything else in
the direction-analysis math was traced and confirmed to already be
zoom-invariant by construction - not touched.

**Tech Stack:** Kotlin (Android, Jetpack Compose, CameraX 1.3.3), Kotlin
(Spring Boot server), Python (FastAPI video-analysis service), JUnit 5,
pytest.

## Global Constraints

- Zoom is clamped to `[1.0, 2.0]` (never below 1.0, never above 2.0),
  further intersected with whatever the device's own hardware actually
  supports (`ZoomState.minZoomRatio`/`maxZoomRatio`) so `setZoomRatio` is
  never called with a value the device would reject.
- Zoom is captured **once**, at the moment recording starts - not tracked
  continuously. It is implicitly locked once recording starts (no separate
  lock mechanism - CameraX just keeps whatever ratio was last set).
- **Both zoom-sensitive thresholds scale UP with zoom ratio (multiply, not
  divide)**: `corridors.py`'s clustering threshold and
  `tracking_bearing.py`'s `MIN_DISPLACEMENT_PIXELS` floor. Verified by a
  concrete check: two same-lane tracks 3m apart show up as ~50px apart at
  1x zoom and ~100px apart at 2x zoom (zoom magnifies pixel distance for a
  fixed real-world distance) - the threshold must grow to keep recognizing
  them as the same lane; dividing would shrink it and make false splits
  worse, not better.
- `zoom_ratio` is optional at every layer (multipart field, DB column,
  Python request param), defaulting to `1.0` (no zoom / today's behavior)
  when absent - older app versions and pre-feature reports must not break.
- A `zoom_ratio <= 0` arriving anywhere in the Python service must not
  reach either scaling calculation - clamp to a `1.0` floor first.

---

### Task 1: Python video-analysis - zoom-aware thresholds

**Files:**
- Modify: `video-analysis/app/tracking_bearing.py`
- Modify: `video-analysis/app/pipeline.py`
- Modify: `video-analysis/app/main.py`
- Test: `video-analysis/tests/test_bearing.py`
- Test: `video-analysis/tests/test_pipeline.py`

**Interfaces:**
- Changes: `resolve_bearing(centroids, bboxes=None, sample_size=DEFAULT_SAMPLE_SIZE, min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS)`
  and `compute_displacement_pixels(centroids, bboxes=None, sample_size=DEFAULT_SAMPLE_SIZE, min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS)`
  (both gain one new optional parameter, appended last, defaulting to the
  existing module constant - every existing call site and test keeps
  working unchanged).
- Changes: `AnalysisPipeline.analyze(self, video_path: str, zoom_ratio: float = 1.0)`
  (new optional parameter).
- Produces: nothing new consumed by Task 2/3 directly - Task 2 calls this
  service's HTTP endpoint, not these Python functions.

- [ ] **Step 1: Write the failing tests for the new `min_displacement_pixels` parameter**

Add to `video-analysis/tests/test_bearing.py`, right after the existing
`test_compute_displacement_pixels_combines_scale_when_lateral_is_under_the_floor`
test at the end of the file:

```python
def test_resolve_bearing_honors_a_custom_min_displacement_pixels_floor():
    # resolve_bearing averages over a sample_size window (unlike compute_displacement_pixels,
    # which uses raw first-vs-last-frame), so a 24px endpoint-to-endpoint track produces
    # ~13.7px of AVERAGED displacement (24 * 4/7, given DEFAULT_SAMPLE_SIZE=4 and this
    # 8-point track) - clears the default 8.0px floor (would resolve via "centroid"), but
    # must be rejected as noise once the caller raises the floor to 20.0 (e.g. to represent
    # the same 8.0px real-world sensitivity at 2x-ish zoom).
    track = _linear_track((50.0, 100.0), (74.0, 100.0))
    assert resolve_bearing(track) is not None
    assert resolve_bearing(track, min_displacement_pixels=20.0) is None


def test_compute_displacement_pixels_honors_a_custom_min_displacement_pixels_floor():
    # Same 12px lateral motion, no bboxes: with the default floor this already clears
    # MIN_DISPLACEMENT_PIXELS so bboxes wouldn't even be consulted; with a raised floor and
    # a bbox-diagonal scale change supplied, the scale contribution must kick in instead -
    # proving the floor value actually gates which code path runs, not just the label.
    centroids = [(50.0, 100.0), (62.0, 100.0)]
    bboxes = [(0.0, 0.0, 10.0, 10.0), (0.0, 0.0, 10.0, 34.0)]  # diagonal ~14.14 -> ~35.44

    assert compute_displacement_pixels(centroids, bboxes) == pytest.approx(12.0, abs=1e-6)
    combined = compute_displacement_pixels(centroids, bboxes, min_displacement_pixels=20.0)
    assert combined > 12.0  # scale contribution added, since 12px alone no longer clears 20.0
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd video-analysis && python -m pytest tests/test_bearing.py -v -k "min_displacement_pixels_floor"`
Expected: FAIL - `resolve_bearing()`/`compute_displacement_pixels()` don't
accept a `min_displacement_pixels` keyword argument yet (`TypeError`).

- [ ] **Step 3: Add the `min_displacement_pixels` parameter**

In `video-analysis/app/tracking_bearing.py`, change `resolve_bearing`'s
signature and its one usage of the module constant:

```python
def resolve_bearing(
    centroids: Sequence[Tuple[float, float]],
    bboxes: Sequence[Tuple[float, float, float, float]] | None = None,
    sample_size: int = DEFAULT_SAMPLE_SIZE,
    min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS,
) -> Tuple[float, str] | None:
```

(Docstring unchanged otherwise.) Then change the body's
`if lateral_displacement >= MIN_DISPLACEMENT_PIXELS:` to:

```python
    if lateral_displacement >= min_displacement_pixels:
```

Change `compute_displacement_pixels`'s signature the same way:

```python
def compute_displacement_pixels(
    centroids: Sequence[Tuple[float, float]],
    bboxes: Sequence[Tuple[float, float, float, float]] | None = None,
    sample_size: int = DEFAULT_SAMPLE_SIZE,
    min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS,
) -> float:
```

and its body's `if lateral >= MIN_DISPLACEMENT_PIXELS or bboxes is None:` to:

```python
    if lateral >= min_displacement_pixels or bboxes is None:
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd video-analysis && python -m pytest tests/test_bearing.py -v`
Expected: all tests pass, including the 2 new ones and every pre-existing
one (unchanged - the default parameter value preserves current behavior).

- [ ] **Step 5: Write the failing test for zoom-aware corridor clustering**

Add to `video-analysis/tests/test_pipeline.py`, right after the existing
`test_same_lane_tracks_share_a_corridor_and_opposite_lane_does_not` test:

```python
def test_same_lane_tracks_still_cluster_at_higher_zoom_where_they_would_otherwise_split():
    # Track 3 sits at x=80, ~55px from tracks 1/2 (around x=17-25) - just OUTSIDE the
    # zoom=1.0 threshold (5% of the 100x100 frame's ~141.42px diagonal = ~7.07px), so at
    # zoom=1.0 it correctly forms its own corridor (mirrors the existing sibling test).
    # But interpret this as: at 2x zoom, this same real-world separation would show up at
    # roughly double the pixel distance it would at 1x - so a fixed (non-zoom-scaled)
    # threshold would ALSO wrongly split same-lane tracks that are much closer together in
    # real-world terms. This test instead proves the direct, simplest case: the SAME
    # geometry that's borderline-separate at zoom=1.0 must cluster together once the
    # threshold is correctly scaled up for zoom=2.0 (7.07px * 2.0 = 14.14px - still not
    # enough to catch track 3 at 55px, so use a closer track 3 instead, tuned to fall
    # between the zoom=1.0 and zoom=2.0 thresholds).
    frames = []
    for i in range(6):
        frames.append(_make_frame(track_id=1, frame_index=i, bbox=(15.0, 10.0 * i, 25.0, 10.0 * i + 10.0)))
        frames.append(_make_frame(track_id=2, frame_index=i, bbox=(17.0, 10.0 * i, 27.0, 10.0 * i + 10.0)))
    # Track 3 at x=31: ~10.6px from track 1 (x=20 centerline) - beyond the zoom=1.0
    # threshold (~7.07px) so it splits at zoom=1.0, but within the zoom=2.0 threshold
    # (~14.14px) so it must cluster once zoom_ratio=2.0 is passed through.
    for i in range(6):
        frames.append(_make_frame(track_id=3, frame_index=i, bbox=(26.0, 10.0 * i, 36.0, 10.0 * i + 10.0)))

    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    response_at_1x = pipeline.analyze("unused.mp4", zoom_ratio=1.0)
    by_track_1x = {v.track_id: v for v in response_at_1x.vehicles}
    assert by_track_1x[1].corridor_id != by_track_1x[3].corridor_id

    response_at_2x = pipeline.analyze("unused.mp4", zoom_ratio=2.0)
    by_track_2x = {v.track_id: v for v in response_at_2x.vehicles}
    assert by_track_2x[1].corridor_id == by_track_2x[3].corridor_id


def test_zoom_ratio_at_or_below_zero_is_clamped_to_1x_behavior():
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(15.0, 10.0 * i, 25.0, 10.0 * i + 10.0))
        for i in range(6)
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    # Must not raise (e.g. from a degenerate/negative threshold) and must behave exactly
    # like the 1.0 default - a single-track corridor either way.
    response = pipeline.analyze("unused.mp4", zoom_ratio=-3.0)
    assert len(response.vehicles) == 1
    assert response.vehicles[0].corridor_id == 0
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v -k "zoom"`
Expected: FAIL - `AnalysisPipeline.analyze()` doesn't accept a `zoom_ratio`
keyword argument yet (`TypeError`).

- [ ] **Step 7: Thread `zoom_ratio` through `pipeline.py`**

In `video-analysis/app/pipeline.py`, change `analyze`'s signature and body:

```python
    def analyze(self, video_path: str, zoom_ratio: float = 1.0) -> AnalyzeResponse:
        tracks: dict[int, list["TrackedFrame"]] = defaultdict(list)
        for tracked_frame in self._detector.track_video(video_path):
            tracks[tracked_frame.track_id].append(tracked_frame)

        if not tracks:
            return AnalyzeResponse()

        fps = self._detector.read_fps(video_path)

        # A zoom ratio below 1.0 is physically meaningless (this app never zooms OUT past
        # 1x) and would shrink both scaled thresholds below their calibrated 1x values -
        # clamp defensively rather than trust the caller.
        effective_zoom_ratio = max(zoom_ratio, 1.0)

        # All frames share the source video's dimensions; read them off any one.
        any_frame = next(iter(tracks.values()))[0].frame
        frame_height, frame_width = any_frame.shape[:2]

        paths = {
            track_id: [f.centroid for f in sorted(frames, key=lambda f: f.frame_index)]
            for track_id, frames in tracks.items()
        }
        # Scales UP with zoom: the same real-world lane separation shows up as MORE pixels
        # at higher zoom, so the pixel threshold representing "same lane" must grow to
        # match, not shrink - see this plan's Global Constraints for the worked example.
        threshold_px = (
            self._settings.corridor_cluster_threshold_fraction
            * math.hypot(frame_width, frame_height)
            * effective_zoom_ratio
        )
        assignments = cluster_tracks(paths, threshold_px)

        # Same scaling direction and reasoning as threshold_px above, applied to the
        # lateral-motion-vs-noise floor in tracking_bearing.py.
        min_displacement_pixels = MIN_DISPLACEMENT_PIXELS * effective_zoom_ratio

        vehicles = [
            self._summarize_track(
                track_id,
                frames,
                corridor_id=assignments[track_id],
                cohesion=corridor_cohesion(track_id, paths, assignments, threshold_px),
                fps=fps,
                min_displacement_pixels=min_displacement_pixels,
            )
            for track_id, frames in tracks.items()
        ]
        return AnalyzeResponse(vehicles=vehicles, frame_width=frame_width, frame_height=frame_height)
```

Add the new import at the top of the file, alongside the existing
`tracking_bearing` import:

```python
from app.tracking_bearing import (
    MIN_DISPLACEMENT_PIXELS,
    bbox_diagonal,
    compute_displacement_pixels,
    compute_track_midpoint_ms,
    resolve_bearing,
)
```

(This replaces the existing `from app.tracking_bearing import bbox_diagonal,
compute_displacement_pixels, compute_track_midpoint_ms, resolve_bearing`
line - only the import list changes, adding `MIN_DISPLACEMENT_PIXELS`.)

Change `_summarize_track`'s signature and its two calls that need the new
floor:

```python
    def _summarize_track(
        self,
        track_id: int,
        frames: list["TrackedFrame"],
        corridor_id: int,
        cohesion: float,
        fps: float | None,
        min_displacement_pixels: float,
    ) -> VehicleResult:
        frames_sorted = sorted(frames, key=lambda f: f.frame_index)
        centroids = [f.centroid for f in frames_sorted]
        bboxes = [f.bbox for f in frames_sorted]
        bearing_result = resolve_bearing(centroids, bboxes, min_displacement_pixels=min_displacement_pixels)
        bearing = bearing_result[0] if bearing_result else None
        bearing_source = bearing_result[1] if bearing_result else None
        track_midpoint_ms = compute_track_midpoint_ms(
            frames_sorted[0].frame_index, frames_sorted[-1].frame_index, fps
        )

        displacement = compute_displacement_pixels(centroids, bboxes, min_displacement_pixels=min_displacement_pixels)
```

(The rest of `_summarize_track`'s body - `vehicle_type` through the
`return VehicleResult(...)` - is unchanged.)

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v`
Expected: all tests pass, including the 2 new ones and every pre-existing
one (the `zoom_ratio=1.0` default preserves current behavior exactly).

- [ ] **Step 9: Wire the `/v1/analyze` endpoint's new form field**

In `video-analysis/app/main.py`, add a new `Form` parameter to `analyze`
and pass it through:

```python
@app.post("/v1/analyze", response_model=AnalyzeResponse, dependencies=[Depends(_verify_api_key)])
async def analyze(
    video: UploadFile = File(...),
    # Echoed/logged only - never used to look anything up, so this service stays stateless.
    report_id: str | None = Form(default=None),
    zoom_ratio: float = Form(default=1.0),
    settings: Settings = Depends(get_settings),
) -> AnalyzeResponse:
    pipeline = _get_pipeline(settings)

    suffix = Path(video.filename or "video.mp4").suffix or ".mp4"
    with tempfile.NamedTemporaryFile(suffix=suffix) as tmp_file:
        tmp_file.write(await video.read())
        tmp_file.flush()
        response = pipeline.analyze(tmp_file.name, zoom_ratio=zoom_ratio)

    return response
```

- [ ] **Step 10: Run the full Python test suite**

Run: `cd video-analysis && python -m pytest -v`
Expected: all tests pass (this also exercises `test_api_health.py`,
`test_corridors.py`, `test_detection.py`, `test_frame_encoding.py` -
unaffected by this task, confirming nothing else broke).

- [ ] **Step 11: Commit**

```bash
git add video-analysis/app/tracking_bearing.py video-analysis/app/pipeline.py video-analysis/app/main.py video-analysis/tests/test_bearing.py video-analysis/tests/test_pipeline.py
git commit -m "feat(video-analysis): scale zoom-sensitive thresholds by zoom ratio

corridors.py's clustering threshold and tracking_bearing.py's
MIN_DISPLACEMENT_PIXELS floor were both traced and confirmed to be
genuinely zoom-sensitive (unlike the bearing angle computation and
the server-side displacement-quality gate, both already zoom-invariant
by construction). Both now scale UP with zoom ratio - a fixed real-
world distance shows up as more pixels at higher zoom, so a threshold
representing a real-world quantity must grow with zoom, not shrink.
zoom_ratio defaults to 1.0 (today's behavior) end to end."
```

---

### Task 2: Server - thread zoom ratio through submission and analysis

**Files:**
- Create: `server/src/main/resources/db/migration/V10__add_zoom_ratio_to_reports.sql`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClient.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClientTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1 directly (this task only needs to know the
  Python service's `/v1/analyze` endpoint accepts an optional `zoom_ratio`
  form field - it does not call Python code directly).
- Produces: `Report.zoomRatio: BigDecimal?`, consumed by Task 3 indirectly
  (Task 3 only needs to know the multipart field name `zoom_ratio` the
  server's `ReportController` accepts - not this Kotlin type).
- Changes: `VideoAnalysisClient.analyze(videoPath: Path, reportId: UUID, zoomRatio: BigDecimal?): VideoAnalysisResponse`
  (new third parameter).

- [ ] **Step 1: Create the migration**

Create `server/src/main/resources/db/migration/V10__add_zoom_ratio_to_reports.sql`:

```sql
ALTER TABLE reports ADD COLUMN zoom_ratio NUMERIC(4,2);
```

(Nullable - mirrors `compass_heading_degrees`'s existing nullable column
exactly, since older app versions never send this field. `NUMERIC(4,2)`
comfortably holds the `1.00`-`2.00` range with 2 decimal places of
precision.)

- [ ] **Step 2: Add the column to the `Report` entity**

In `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`, add
a new field right after `compassHeadingDegrees`:

```kotlin
    // From the Android client's compass snapshot - absent on submissions from app
    // versions predating that capability (see ReportAnalysisJob's "no compass heading"
    // rejection path).
    @Column(name = "compass_heading_degrees")
    var compassHeadingDegrees: BigDecimal? = null,

    // The zoom ratio active when recording started (1.0-2.0), captured once, not tracked
    // continuously - absent on submissions from app versions predating zoom support, or
    // when the app itself couldn't determine it. Passed through to the video-analysis
    // service so its zoom-sensitive pixel-space thresholds scale correctly - see
    // ReportAnalysisJob/VideoAnalysisClient.
    @Column(name = "zoom_ratio")
    var zoomRatio: BigDecimal? = null,

    // Populated from StreetDirectionResolver whenever a street name is known - even for
```

(The final line above - `// Populated from StreetDirectionResolver...` - is
the existing next line in the file, shown only so the insertion point is
unambiguous; it is not itself part of what you're adding.)

- [ ] **Step 3: Thread the field through `ReportController`/`ReportService`**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt`,
add a new `@RequestParam` right after `compass_heading_degrees` and pass it
through to `reportService.submit(...)`:

```kotlin
        @RequestParam("compass_heading_degrees", required = false) compassHeadingDegrees: BigDecimal?,
        // Same "absent on older app versions" tolerance as compass_heading_degrees above.
        @RequestParam("zoom_ratio", required = false) zoomRatio: BigDecimal?,
        @RequestParam("location_samples", required = false) locationSamplesJson: String?,
        @RequestParam("rotation_samples", required = false) rotationSamplesJson: String?,
    ): SubmitReportResponse =
        reportService.submit(
            video = video,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            recordedAt = recordedAt,
            durationMs = durationMs,
            deviceId = deviceId,
            compassHeadingDegrees = compassHeadingDegrees,
            zoomRatio = zoomRatio,
            locationSamplesJson = locationSamplesJson,
            rotationSamplesJson = rotationSamplesJson,
        )
```

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`,
add the matching parameter to `submit(...)`'s signature (right after
`compassHeadingDegrees`) and to the `Report(...)` constructor call inside
it:

```kotlin
        compassHeadingDegrees: BigDecimal?,
        zoomRatio: BigDecimal?,
        locationSamplesJson: String?,
```

and:

```kotlin
            compassHeadingDegrees = compassHeadingDegrees,
            zoomRatio = zoomRatio,
            locationSamples = canonicalLocationSamples,
```

- [ ] **Step 4: Confirm the server builds**

Run: `cd server && ./gradlew.bat build -x test`
Expected: `BUILD SUCCESSFUL` (this also validates the new migration applies
cleanly, via Hibernate's `ddl-auto: validate`, which fails the build if the
entity and schema disagree).

- [ ] **Step 5: Write the failing test for `VideoAnalysisClient`'s new parameter**

Add to `server/src/test/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClientTest.kt`,
right after the existing
`analyze parses vehicles from a successful response and sends the API key header`
test:

```kotlin
    @Test
    fun `analyze includes zoom_ratio in the multipart request body when provided`() {
        mockServer.expect(requestTo("http://video-analysis.test/v1/analyze"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"zoom_ratio\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("1.50")))
            .andRespond(withSuccess("""{"vehicles": []}""", MediaType.APPLICATION_JSON))

        client.analyze(fakeVideoPath, UUID.randomUUID(), zoomRatio = java.math.BigDecimal("1.50"))

        mockServer.verify()
    }

    @Test
    fun `analyze omits zoom_ratio from the request body when null`() {
        mockServer.expect(requestTo("http://video-analysis.test/v1/analyze"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"zoom_ratio\""))))
            .andRespond(withSuccess("""{"vehicles": []}""", MediaType.APPLICATION_JSON))

        client.analyze(fakeVideoPath, UUID.randomUUID(), zoomRatio = null)

        mockServer.verify()
    }
```

Add the import `import org.springframework.test.web.client.match.MockRestRequestMatchers.content`
alongside the file's existing `MockRestRequestMatchers.*` imports.

- [ ] **Step 6: Run tests to verify they fail**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.videoanalysis.VideoAnalysisClientTest"`
Expected: FAIL - `analyze(fakeVideoPath, UUID.randomUUID(), zoomRatio = ...)`
doesn't compile yet (no such parameter).

- [ ] **Step 7: Add the `zoomRatio` parameter to `VideoAnalysisClient.analyze`**

In `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClient.kt`,
change `analyze`'s signature and body:

```kotlin
    /**
     * Uploads the video at [videoPath] for analysis. [reportId] is echoed/logged only by the
     * Python side. [zoomRatio] (the zoom level active when the recording started, if any) is
     * sent only when non-null, so the Python service's own `zoom_ratio` form-field default
     * (1.0) governs for reports from app versions predating zoom support. Returns the full
     * response (vehicles plus frame dimensions) - callers such as
     * [com.trafficwatch.server.reports.ReportAnalysisJob] need `frameWidth`/`frameHeight`
     * for corridor/flow analysis, not just the vehicle list.
     */
    fun analyze(videoPath: Path, reportId: UUID, zoomRatio: java.math.BigDecimal?): VideoAnalysisResponse {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("video", FileSystemResource(videoPath))
        body.add("report_id", reportId.toString())
        zoomRatio?.let { body.add("zoom_ratio", it.toString()) }
```

(Everything else in the function - the `try`/`catch` and the request
building below `body.add("report_id", ...)` - is unchanged. Add
`import java.math.BigDecimal` to the file's imports and use the plain
`BigDecimal` type name instead of the fully-qualified form shown above, to
match this file's existing import style.)

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.videoanalysis.VideoAnalysisClientTest"`
Expected: BUILD SUCCESSFUL, all tests pass (2 new + 3 existing).

- [ ] **Step 9: Update `ReportAnalysisJob`'s call site**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`,
change the `videoAnalysisClient.analyze(...)` call:

```kotlin
        val analysis = try {
            videoAnalysisClient.analyze(
                videoStorageService.resolve(report.videoPath),
                requireNotNull(report.id) { "Report must have a generated id before analysis" },
                report.zoomRatio,
            )
        } catch (ex: VideoAnalysisException) {
```

- [ ] **Step 10: Run the full server test suite**

Run: `cd server && ./gradlew.bat test`
Expected: BUILD SUCCESSFUL (this also confirms `ReportAnalysisJobTest`'s
existing mocked `videoAnalysisClient.analyze(...)` calls still compile -
see the note below if they don't).

⚠️ If `ReportAnalysisJobTest.kt` fails to compile because it mocks
`videoAnalysisClient.analyze(...)` with the old 2-argument signature: this
file has many such call sites (mirroring the same pattern the OSM
street/direction accuracy plan hit with `streetDirectionResolver.resolve`).
If so, every `videoAnalysisClient.analyze(videoStorageService.resolve(...), reportId)`-shaped
mock call in that file needs a third argument. Since every test in that
file uses reports with `zoomRatio = null` (the field doesn't exist in that
test's report-building helper before this task), add `, any()` (MockK's
wildcard matcher) as the third argument to every such mocked call - this
keeps the existing tests focused on what they're actually testing (the
analysis outcome, not the exact zoom value passed through) rather than
forcing every one of them to also assert a specific zoom value.

- [ ] **Step 11: Commit**

```bash
git add server/src/main/resources/db/migration/V10__add_zoom_ratio_to_reports.sql server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt server/src/main/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClient.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/test/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClientTest.kt
git commit -m "feat(server): thread zoom ratio from submission through to video analysis

Report.zoomRatio is a new nullable column, following the exact same
optional-field pattern as compassHeadingDegrees - absent on older app
versions, threaded through to the video-analysis service's zoom_ratio
form field so its zoom-sensitive thresholds scale correctly."
```

---

### Task 3: Android - zoom capture UI and upload

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/domain/model/LocationData.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`
- Test (new): `app/src/test/java/com/trafficwatch/app/feature/camera/ZoomRatioTest.kt`

**Interfaces:**
- Consumes: the server's `POST /reports` endpoint accepting an optional
  `zoom_ratio` multipart field (Task 2).
- Produces: `CameraController.currentZoomRatio: StateFlow<Float>`,
  `CameraController.setZoomRatio(requested: Float)`,
  `LocationData.zoomRatio: Float?` (verified real CameraX 1.3.3 APIs used
  below: `Camera.cameraControl.setZoomRatio(Float): ListenableFuture<Void>`,
  `Camera.cameraInfo.zoomState: LiveData<ZoomState>`,
  `ZoomState.minZoomRatio`/`maxZoomRatio` - confirmed via `javap` against
  the actual resolved `camera-core-1.3.3-api.jar`, not assumed).

- [ ] **Step 1: Write the failing test for the pure zoom-clamping function**

Create `app/src/test/java/com/trafficwatch/app/feature/camera/ZoomRatioTest.kt`:

```kotlin
package com.trafficwatch.app.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomRatioTest {

    @Test
    fun `a value within both the app cap and device range passes through unchanged`() {
        assertEquals(1.5f, clampZoomRatio(1.5f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }

    @Test
    fun `a value above the app's 2x cap is clamped to 2x even when the device supports more`() {
        assertEquals(2.0f, clampZoomRatio(5.0f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }

    @Test
    fun `a value below 1x is clamped to 1x`() {
        assertEquals(1.0f, clampZoomRatio(0.5f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }

    @Test
    fun `the app cap is further reduced when the device's own max zoom is below 2x`() {
        assertEquals(1.6f, clampZoomRatio(2.0f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 1.6f), 0.001f)
    }

    @Test
    fun `the app floor is raised when the device's own min zoom is above 1x`() {
        assertEquals(1.2f, clampZoomRatio(1.0f, deviceMinZoomRatio = 1.2f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.camera.ZoomRatioTest"`
Expected: FAILURE - compile error, `clampZoomRatio` is unresolved.

- [ ] **Step 3: Implement `clampZoomRatio` and zoom control in `CameraController`**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt`,
add these imports alongside the existing ones:

```kotlin
import androidx.camera.core.Camera
import kotlin.math.max
import kotlin.math.min
```

Add a top-level constant and pure function near the top of the file (below
the `package`/`import` lines, above `sealed class RecordingState`):

```kotlin
/** This app's own hard cap, independent of whatever a device's hardware would otherwise allow. */
private const val APP_MAX_ZOOM_RATIO = 2.0f
private const val APP_MIN_ZOOM_RATIO = 1.0f

/**
 * Clamps [requested] to the intersection of this app's own [APP_MIN_ZOOM_RATIO]/
 * [APP_MAX_ZOOM_RATIO] cap and the device's actual supported range
 * ([deviceMinZoomRatio]/[deviceMaxZoomRatio], from [androidx.camera.core.ZoomState]) - so
 * [androidx.camera.core.CameraControl.setZoomRatio] is never called with a value outside
 * what the device itself would accept, on a device whose own range doesn't happen to
 * bracket this app's [1.0, 2.0] range exactly.
 */
internal fun clampZoomRatio(requested: Float, deviceMinZoomRatio: Float, deviceMaxZoomRatio: Float): Float {
    val lowerBound = max(APP_MIN_ZOOM_RATIO, deviceMinZoomRatio)
    val upperBound = min(APP_MAX_ZOOM_RATIO, deviceMaxZoomRatio)
    return requested.coerceIn(lowerBound, upperBound)
}
```

Inside the `CameraController` class, add zoom state alongside the existing
`_currentRotation`/`currentRotation` pair (from the earlier
recording-heading-rotation-correction work):

```kotlin
    private val _currentZoomRatio = MutableStateFlow(APP_MIN_ZOOM_RATIO)
    val currentZoomRatio = _currentZoomRatio.asStateFlow()

    private var camera: Camera? = null
```

In `bindCamera(...)`'s success path, capture the `Camera` returned by
`bindToLifecycle` (currently discarded) and reset zoom state for the fresh
binding:

```kotlin
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
                _currentZoomRatio.value = APP_MIN_ZOOM_RATIO
                startOrientationTracking()
                onBound()
            } catch (e: Exception) {
                onError(e.message ?: "Camera bind failed")
            }
```

Add a new public method, anywhere after `bindCamera`:

```kotlin
    /**
     * Requests [requested] as the new zoom ratio, clamped via [clampZoomRatio] against both
     * this app's own cap and the bound camera's actual supported range. A no-op before
     * [bindCamera] has completed (no bound [Camera] yet) - the zoom controls are only shown
     * once the preview is live, so this should not normally be reachable that early, but a
     * stray call must not crash rather than silently do nothing.
     */
    fun setZoomRatio(requested: Float) {
        val boundCamera = camera ?: return
        val zoomState = boundCamera.cameraInfo.zoomState.value ?: return
        val clamped = clampZoomRatio(requested, zoomState.minZoomRatio, zoomState.maxZoomRatio)
        boundCamera.cameraControl.setZoomRatio(clamped)
        _currentZoomRatio.value = clamped
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.camera.ZoomRatioTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Add the zoom badge, pill buttons, and pinch gesture to `CameraScreen`**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`,
check the existing import list, then add whichever of these aren't already
present (`RoundedCornerShape`, `Column`, and `collectAsStateWithLifecycle`
are already imported in this file - only the rest are new):

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
```

Collect the zoom state near the top of `CameraScreen`, alongside the
existing `uiState`/`recordingState` collection:

```kotlin
    val currentZoomRatio by viewModel.currentZoomRatio.collectAsStateWithLifecycle()
```

Add a pinch gesture to the camera preview `AndroidView` - change:

```kotlin
        // Camera preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
```

to:

```kotlin
        // Camera preview - pinch anywhere to zoom (no-op once recording has started,
        // since CameraController.setZoomRatio has no effect on VideoCapture's already-
        // locked-in framing, so there's no reason to keep reading gesture events then).
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isRecording) {
                    if (!isRecording) {
                        detectTransformGestures { _, _, zoom, _ ->
                            viewModel.onZoomGesture(zoom)
                        }
                    }
                }
        )
```

Add the zoom badge (top-left, alongside the existing `GpsBadge` box - both
top-left, so stack them in a `Column` instead of two separate top-left
`Box`es). Replace the existing GPS badge block:

```kotlin
        // GPS status badge (top-left)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            GpsBadge(uiState.locationState)
        }
```

with:

```kotlin
        // GPS status badge + zoom level, stacked (top-left)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            GpsBadge(uiState.locationState)
            Spacer(Modifier.height(8.dp))
            ZoomBadge(currentZoomRatio)
        }
```

Add the pill buttons row, positioned bottom-center directly above the
record `FloatingActionButton` (matching the confirmed mockup). Insert this
new block immediately before the existing `FloatingActionButton(...)`
block:

```kotlin
        // Quick-select zoom buttons (bottom-centre, above the record FAB) - hidden once
        // recording has started, same reasoning as the pinch gesture above.
        if (!isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 112.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(1.0f, 1.5f, 2.0f).forEach { ratio ->
                    val selected = kotlin.math.abs(currentZoomRatio - ratio) < 0.01f
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) Color.White else Color.Transparent,
                        modifier = Modifier
                            .padding(2.dp)
                            .clickable { viewModel.setZoomRatio(ratio) }
                    ) {
                        Text(
                            text = formatZoomLabel(ratio),
                            color = if (selected) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
```

Add `ZoomBadge` and `formatZoomLabel`, alongside the existing
`GpsBadge`/`RecordingDot` private composables/helpers:

```kotlin
@Composable
private fun ZoomBadge(zoomRatio: Float) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(formatZoomLabel(zoomRatio), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** "1x", "1.5x", "1.7x" - one decimal place, omitted when the value is a whole number. */
private fun formatZoomLabel(ratio: Float): String {
    val rounded = (ratio * 10).toInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) "${rounded.toInt()}x" else "${rounded}x"
}
```

- [ ] **Step 6: Wire zoom state and gesture handling through `CameraViewModel`**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`,
expose `cameraController.currentZoomRatio` and add the two methods
`CameraScreen` now calls. Add right after the existing
`val recordingState = cameraController.recordingState` line:

```kotlin
    val currentZoomRatio = cameraController.currentZoomRatio
```

Add these two new public methods, anywhere after `bindCamera`:

```kotlin
    /** Absolute zoom level, from a pill button tap. */
    fun setZoomRatio(ratio: Float) = cameraController.setZoomRatio(ratio)

    /**
     * Relative zoom, from a pinch gesture's per-frame scale factor (Compose's
     * `detectTransformGestures` reports zoom as a multiplier on the CURRENT scale each
     * callback, not an absolute target - e.g. 1.02 means "2% bigger than a moment ago",
     * not "set zoom to 1.02x").
     */
    fun onZoomGesture(scaleFactor: Float) = cameraController.setZoomRatio(currentZoomRatio.value * scaleFactor)
```

Capture the zoom ratio once at recording start, alongside the existing
`snapshotCompassHeading` capture in `onStartRecording`:

```kotlin
        viewModelScope.launch {
            snapshotCompassHeading = if (declinationReference != null) {
```

Add `private var snapshotZoomRatio: Float? = null` alongside the existing
`private var snapshotCompassHeading: Float? = null` field declaration, and
set it synchronously (not inside a `viewModelScope.launch`, since
`cameraController.currentZoomRatio.value` is already available
synchronously - no sensor read needed) right after the `recordingStartedAt = System.currentTimeMillis()`
line near the top of `onStartRecording`:

```kotlin
        recordingStartedAt = System.currentTimeMillis()
        snapshotZoomRatio = cameraController.currentZoomRatio.value
```

Change `getSnapshotLocation()` to attach it:

```kotlin
    fun getSnapshotLocation(): LocationData? =
        snapshotLocation?.copy(compassHeadingDegrees = snapshotCompassHeading, zoomRatio = snapshotZoomRatio)
```

- [ ] **Step 7: Add `zoomRatio` to `LocationData`**

In `app/src/main/java/com/trafficwatch/app/core/domain/model/LocationData.kt`:

```kotlin
    // True-north compass heading the camera was pointed at recording start - null if no
    // rotation-vector sensor exists or the read timed out. See CompassProvider.
    val compassHeadingDegrees: Float? = null,
    // Zoom ratio (1.0-2.0) active when recording started, captured once - null on
    // submissions from app versions predating zoom support, or if capture failed. See
    // CameraController.currentZoomRatio.
    val zoomRatio: Float? = null
```

(Only the trailing comma on the `compassHeadingDegrees` line and the new
`zoomRatio` line change - the rest of the data class is unchanged.)

- [ ] **Step 8: Thread `zoom_ratio` through the upload multipart request**

In `app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt`,
add a new `@Part` right after `compass_heading_degrees`:

```kotlin
        @Part("compass_heading_degrees") compassHeadingDegrees: RequestBody?,
        @Part("zoom_ratio") zoomRatio: RequestBody?,
        @Part("location_samples") locationSamples: RequestBody?,
```

In `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`,
follow the exact `KEY_COMPASS_HEADING` pattern for a new `KEY_ZOOM_RATIO`.
Add the constant alongside `KEY_COMPASS_HEADING`:

```kotlin
        const val KEY_COMPASS_HEADING = "compass_heading_degrees"
        const val KEY_ZOOM_RATIO = "zoom_ratio"
```

In `doWork()`, add the read right after the existing `compassHeadingDegrees` read:

```kotlin
        val compassHeadingDegrees = if (inputData.hasKeyWithValueOfType<Float>(KEY_COMPASS_HEADING)) {
            inputData.getFloat(KEY_COMPASS_HEADING, 0f)
        } else {
            null
        }
        val zoomRatio = if (inputData.hasKeyWithValueOfType<Float>(KEY_ZOOM_RATIO)) {
            inputData.getFloat(KEY_ZOOM_RATIO, 0f)
        } else {
            null
        }
```

Pass it to `apiService.submitReport(...)`, right after `compassHeadingDegrees`:

```kotlin
                compassHeadingDegrees = compassHeadingDegrees?.toString()?.toRequestBody(),
                zoomRatio = zoomRatio?.toString()?.toRequestBody(),
                locationSamples = locationSamplesJson?.toRequestBody(),
```

In `buildInputData(...)`, add the same "omit when null" handling right
after the existing `location.compassHeadingDegrees?.let { ... }` line:

```kotlin
            location.compassHeadingDegrees?.let { builder.putFloat(KEY_COMPASS_HEADING, it) }
            location.zoomRatio?.let { builder.putFloat(KEY_ZOOM_RATIO, it) }
```

- [ ] **Step 9: Confirm the app builds**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Run the full app unit test suite**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (existing tests unaffected; the
5 new `ZoomRatioTest` cases are included in the count).

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt app/src/main/java/com/trafficwatch/app/core/domain/model/LocationData.kt app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt app/src/test/java/com/trafficwatch/app/feature/camera/ZoomRatioTest.kt
git commit -m "feat(app): add pinch-to-zoom and quick-select zoom buttons, capped at 2x

CameraController exposes zoom control via CameraX's
CameraControl.setZoomRatio, clamped to the intersection of this app's
own [1.0, 2.0] cap and the bound camera's actual supported range.
Driven by both a pinch gesture on the preview and three pill buttons
(1x/1.5x/2x) - both controls share the same underlying state. Zoom is
captured once at recording start (mirrors the existing compass-
heading snapshot pattern) and threaded through the upload pipeline as
a new optional zoom_ratio field."
```

- [ ] **Step 12: Install on device and manually verify**

Run: `./gradlew.bat :app:installDebug` (device must be connected via adb -
run `adb devices` first to confirm)

Manual verification steps:
1. Open the Camera screen. Confirm the zoom badge reads "1x" and the pill
   buttons show "1x / 1.5x / 2x" above the record button.
2. Pinch out on the preview. Confirm the zoom badge updates live and the
   preview visibly zooms in, up to but never past "2x" - even if you keep
   pinching further.
3. Pinch in past 1x. Confirm it stops at "1x", never goes below.
4. Tap the "1.5x" pill. Confirm the badge and preview both jump to exactly
   1.5x, and the "1.5x" pill highlights as selected.
5. Set zoom to 1.5x, then start recording. Confirm the pill buttons and
   pinch gesture both stop responding once recording has started - zoom
   stays locked at 1.5x for the whole clip.
6. Submit the report. Once analysis completes (or by checking the server/DB
   directly, matching this project's established verification pattern),
   confirm the stored `zoom_ratio` is `1.50`, not null and not some other
   value.
7. Record a second clip at the default 1x zoom (don't touch the pinch
   gesture or buttons at all) and confirm it still submits and analyzes
   normally - the zoom feature must not regress the un-zoomed path.
