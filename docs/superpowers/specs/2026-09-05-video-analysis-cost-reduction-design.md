# Video-Analysis Cost Reduction (Skip OCR/Frame-Encoding on Unqualifiable Tracks) — Design

**Status:** draft for review

Addresses the recurring "video-analysis service unavailable: Video analysis request
failed" symptom (report `71f78`'s 2026-09-05 replay; report `24908`'s earlier
timeout-then-succeed flakiness) — a real diagnostic and detection blocker, not yet
its own backlog entry.

## Context (spike findings, 2026-09-05)

The server's HTTP client to `video-analysis` has a 180s read timeout
(`app.video-analysis.read-timeout-ms`). A clip as short as ~10s has intermittently
exceeded it. Measured on the production VPS:

- **2 vCPUs, 3.7 GB total RAM.** `docker stats`: `trafficwatch-video-analysis` alone
  uses **2.47 GB (66%) of the whole box at idle** — just the resident YOLOv8n +
  EasyOCR models. Only ~121 MB is free at the OS level; no swap; no per-container
  memory limits, so all three containers (video-analysis, server, postgres) compete
  for the same starved pool. This explains the *non-determinism* (145s one run,
  timeout the next) better than a fixed algorithmic cost would: it's resource
  contention, not a constant.
- **`frame_stride = 1`** (shipped 2026-08-13 for the motorcycle-tracking fix, a
  justified trade at the time) means a ~10s/30fps clip runs **~300 YOLO
  inferences** at `imgsz=960`, CPU-only.
- **`AnalysisPipeline._summarize_track` runs OCR and frame-encoding for EVERY
  detected track, not just the one the server ends up using.** `_read_best_plate`
  makes up to `OCR_CROPS_PER_TRACK` (3) CPU-only EasyOCR `readtext()` calls per
  track; `encode_frame_to_base64_jpeg` JPEG-encodes and base64s each track's
  largest-bbox frame. A busy clip's raw track count runs into the twenties (a
  2026-08-30 diagnostic capture of report `759cd` had 23 raw tracks); most of that
  per-track OCR/encoding work is thrown away — the server (`ReportAnalysisJob`)
  only ever reads `plateText`/`plateConfidence`/`frameJpegBase64` off the **one**
  scored `best` candidate (or the stationary-approach path's one grower), never off
  any other track. It's also pure waste on every REJECTED report (the common
  outcome), since no track is ever selected at all.

This is infra sizing plus wasted compute, not a single algorithmic bug. This spec
addresses the **wasted compute** half — the part fixable in code without touching
`frame_stride` (protects the 2026-08-13 fix) or requiring a VPS resize (an ops/cost
decision, out of scope here — flagged separately to the user).

## Root cause, precisely

`AnalysisPipeline._summarize_track` (`video-analysis/app/pipeline.py`) calls
`resolve_bearing(...)` first; `tracking_bearing.resolve_bearing` (and
`scale_trend`) both **return early with no signal** when a track has fewer than
`MIN_OBSERVATIONS` (12) frames — `bearing_degrees` comes back `None`. On the server
side, `ClipFlowAnalyzer.qualifyVehicles`'s very first line is
`val frameBearing = vehicle.bearingDegrees ?: return@mapNotNull null` — **a vehicle
with a null bearing can never become a `FlowVehicle`, can never be scored, and can
never be the confirmed candidate.** The stationary-approach path's `strongGrowers`
filter additionally requires `trackFrameCount >= approachMinFrames` (default 30) —
well above 12 — so it can't rescue a sub-12-frame track either, and `scale_trend`
itself returns `"flat"` below `MIN_OBSERVATIONS`, so such a track can't even be an
approach-path grower by that route.

**A track with fewer than `MIN_OBSERVATIONS` frames is therefore provably incapable
of ever being the vehicle whose plate or frame the server reads.** Every EasyOCR
call and every JPEG encode spent on such a track today is guaranteed-wasted work,
with no exception.

## Scope decision (confirm before planning)

- **This spec covers only the provably-dead-weight case** (`< MIN_OBSERVATIONS`
  frames). It does **not** attempt to also skip OCR/encoding for tracks that
  qualify (≥ 12 frames) but still won't end up being the winner — doing that
  requires the server to tell `video-analysis` which track it picked, which means
  either a second request (needs the service to hold per-track frame data between
  calls — new statefulness, a TTL/cache, and a new endpoint) or restructuring
  scoring across the service boundary. That is a real, larger win for busy clips
  with many *qualifying* vehicles, but it's an architectural change in its own
  right — recorded in Non-goals as candidate future work, not bundled in here.
- **No `video-analysis` wire/schema change.** `plate_text`, `plate_confidence`, and
  `frame_jpeg_base64` are already `Optional[...] = None` on `VehicleResult`
  (Pydantic) and `String? = null` on the Kotlin `VehicleAnalysisResult`. Nothing
  downstream needs to change to receive `None`/`null` for these tracks — the server
  already never reads plate/frame off a track that isn't `best`, and a
  `< MIN_OBSERVATIONS` track was already unable to become `best`.
- **`bounding_box` is NOT skipped.** It's cheap (four floats off data already in
  memory) and costs nothing to keep populated even for a short track.
- **No change to `frame_stride`, `imgsz`, `OCR_CROPS_PER_TRACK`, or
  `plate_confidence_floor`.** Those are separate tuning levers with their own
  accuracy trade-offs; this spec only removes work that has zero chance of
  affecting any outcome.
- **No retroactive re-analysis.**

## Design

`video-analysis/app/pipeline.py`, `AnalysisPipeline._summarize_track`:

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

    trend, growth_fraction = scale_trend(bboxes)

    vehicle_type = frames_sorted[0].vehicle_type
    detection_confidence = max(f.confidence for f in frames_sorted)

    representative_frame = max(frames_sorted, key=_bbox_area)
    x1, y1, x2, y2 = representative_frame.bbox
    bounding_box = BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2)

    # A track below MIN_OBSERVATIONS frames has bearing_degrees == None (see
    # resolve_bearing/scale_trend above) and can therefore never qualify as a
    # FlowVehicle or an approach-path grower on the server (ClipFlowAnalyzer.
    # qualifyVehicles requires a non-null bearing; the approach path additionally
    # requires >= approachMinFrames(30) >> MIN_OBSERVATIONS). It can never be the
    # server's `best` candidate, so its plate and frame are never read - skip the
    # two most expensive per-track operations entirely rather than compute and
    # discard them. See the 2026-09-05 cost-reduction design.
    if len(frames_sorted) >= MIN_OBSERVATIONS:
        plate_text, plate_confidence = self._read_best_plate(frames_sorted)
        frame_jpeg_base64 = encode_frame_to_base64_jpeg(representative_frame.frame)
    else:
        plate_text, plate_confidence = None, None
        frame_jpeg_base64 = None

    return VehicleResult(
        track_id=track_id,
        vehicle_type=vehicle_type,
        detection_confidence=detection_confidence,
        bearing_degrees=bearing,
        bearing_source=bearing_source,
        plate_text=plate_text,
        plate_confidence=plate_confidence,
        bounding_box=bounding_box,
        frame_jpeg_base64=frame_jpeg_base64,
        corridor_id=corridor_id,
        corridor_cohesion=cohesion,
        track_frame_count=len(frames_sorted),
        displacement_pixels=displacement,
        track_midpoint_ms=track_midpoint_ms,
        scale_trend=trend,
        scale_growth_fraction=growth_fraction,
    )
```

Import `MIN_OBSERVATIONS` from `app.tracking_bearing` (already imports several names
from that module). No other file changes: the Kotlin side already treats a null
`plateText`/`frameJpegBase64` on any vehicle as "nothing to show" (a track this
short was already being silently excluded from every scoring path — this spec only
stops computing values that were never read).

## Will this help?

**Yes, measurably, but it doesn't guarantee every busy clip finishes under 180s** —
see Scope decision. From the one real distribution on record (`759cd`, 23 raw
tracks, 2026-08-30 diagnostic capture): roughly half the tracks were single- to
low-teens-frame blips (`frames=1,2,7,8,9,12,13,...` in that dump) that this change
removes from OCR/encoding entirely. The remaining qualifying tracks (the majority
of the real cost, since each is 3 EasyOCR calls + one JPEG encode) are unaffected —
this spec doesn't reduce their cost, it removes the *additional*, purely wasted
tracks. Expect a meaningful reduction on noisy clips, not a guaranteed fix for a
clip with many genuinely long-tracked vehicles.

## Testing

`video-analysis/tests/test_pipeline.py` (or wherever `_summarize_track` /
`AnalysisPipeline.analyze` is exercised — check the existing suite's structure):

- A track with `< MIN_OBSERVATIONS` frames → `VehicleResult.plate_text is None`,
  `plate_confidence is None`, `frame_jpeg_base64 is None`; `bounding_box` still
  populated; `PlateReader.read_plate` and `encode_frame_to_base64_jpeg` **not
  called** (mock/spy and assert zero calls — the point of this change is the calls
  not happening).
- A track with exactly `MIN_OBSERVATIONS` frames → OCR and encoding **do** run
  (boundary case, off-by-one check).
- A track with `>= MIN_OBSERVATIONS` frames but no resolvable bearing (below the
  *displacement* floor, not the frame-count floor) → OCR/encoding still run (this
  spec's filter is frame-count only, not "has a bearing"; a track that's long but
  stationary could theoretically still matter for the approach path's scale-trend
  signal at `>= 30` frames, so don't over-filter on bearing nullness — only the
  frame-count floor is provably safe cheaply).
- Existing tests asserting `plate_text`/`frame_jpeg_base64` populated for a normal
  (long) track fixture stay green untouched.

### Regression / production verification

- `./gradlew test` (server) unaffected — no server-side change.
- `pytest` (video-analysis) green.
- Manual: re-run `24908` and `71f78` (the two reports that have hit the timeout)
  post-deploy and note wall-clock time to completion. Given the resource-contention
  root cause, don't treat one clean run as proof — the VPS's memory pressure means
  timing will stay somewhat noisy regardless of this fix; look for the *distribution*
  to shift, not a single guaranteed pass.

## Non-goals

- **Defer-to-winner architecture** (only OCR/encode the server's actual chosen
  candidate, for *every* clip, not just the short-track case) — the bigger win,
  requires new cross-service state (a short-TTL per-track frame cache in
  `video-analysis` keyed by report id, plus a second lightweight endpoint the
  server calls only when it has a `best`). Real candidate follow-up if this spec's
  measured improvement isn't enough; deserves its own brainstorm + spec given the
  new statefulness and failure modes (cache miss on container restart, etc.).
- **VPS resize** — flagged to the user separately; a cost/ops decision, not a code
  change. The measured 66%-idle-RAM finding stands regardless of what ships here.
- **Reducing `frame_stride`** — would partially regress the 2026-08-13
  motorcycle-tracking fix; not on the table.
- **Reducing `detection_imgsz` (960) or `OCR_CROPS_PER_TRACK` (3)** — real levers,
  but each trades detection/OCR accuracy for speed; a separate decision with its
  own accuracy-impact analysis, not bundled into a "remove pure waste" change.
- **Raising the 180s server-side read timeout** — treats the symptom (a slow
  request eventually succeeds) rather than the cause, and lengthens how long a
  report sits `PENDING`. Not proposed here; if this fix's measured improvement is
  insufficient, a modest bump is a cheap next lever, but it's not this spec's job.

## Note: diagnostic side effect

A raw dump of the `video-analysis` response (the diagnostic re-run flow used for
false-positive / false-negative investigations) no longer carries a frame image or
plate for sub-`MIN_OBSERVATIONS` tracks. Production outcomes are unaffected — those
tracks were never analyzed — but a future track-fragmentation investigation (e.g.
confirming "same rider across two track IDs" where one fragment is only 8 frames,
per the open backlog item) that needs the short fragment's frame image must
temporarily disable this gate to recover it.
