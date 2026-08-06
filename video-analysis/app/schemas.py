from __future__ import annotations

from pydantic import BaseModel, Field


class BoundingBox(BaseModel):
    x1: float
    y1: float
    x2: float
    y2: float


class VehicleResult(BaseModel):
    track_id: int
    vehicle_type: str
    detection_confidence: float
    # Frame-relative bearing in degrees [0, 360), clockwise from "up" in the frame - null
    # if displacement was too small or the track too brief to trust. This service has no
    # map/compass awareness at all; converting this to a real-world bearing is the Kotlin
    # server's job (see StreetDirectionResolver / ReportAnalysisJob).
    bearing_degrees: float | None = None
    # Which computation produced bearing_degrees: "centroid" (real lateral pixel motion) or
    # "scale" (bbox-diagonal-derived fallback for near-head-on motion - the Kotlin server
    # must corroborate this with the recording vehicle's own GPS speed before trusting it as
    # a genuine approach, since bbox growth alone cannot distinguish the OTHER vehicle
    # approaching from the CAMERA closing the distance on a stationary/slower vehicle). None
    # when bearing_degrees is also None.
    bearing_source: str | None = None
    plate_text: str | None = None
    plate_confidence: float | None = None
    # The representative (largest-bounding-box) frame's box, and that same frame encoded
    # as a base64 JPEG - returned for every vehicle since this service can't know in
    # advance which one (if any) the Kotlin server will decide is wrong-way.
    bounding_box: BoundingBox | None = None
    frame_jpeg_base64: str | None = None
    # Corridor assignment: tracks whose paths trace the same physical corridor of
    # the frame share a corridor_id (direction-agnostic - see app/corridors.py).
    # Raw frame-space facts only; all consensus/flow judgment is the Kotlin
    # server's job.
    corridor_id: int = 0
    corridor_cohesion: float = 1.0
    track_frame_count: int = 0
    displacement_pixels: float = 0.0
    # Elapsed ms from the clip's start to this track's observation midpoint - None when
    # FPS was unavailable (see VehicleDetector.read_fps). The Kotlin server uses this to
    # look up the camera's orientation at roughly this vehicle's own moment in the clip,
    # instead of one static reading for the whole video.
    track_midpoint_ms: int | None = None


class AnalyzeResponse(BaseModel):
    vehicles: list[VehicleResult] = Field(default_factory=list)
    # Source video frame dimensions in pixels, used by the server only to validate
    # that a real frame was analyzed (non-null, non-zero). Displacement is
    # normalized against the vehicle's own bounding-box diagonal, not the frame
    # diagonal. 0 x 0 when the video had no frames.
    frame_width: int = 0
    frame_height: int = 0


class HealthResponse(BaseModel):
    status: str = "ok"
