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
    plate_text: str | None = None
    plate_confidence: float | None = None
    # The representative (largest-bounding-box) frame's box, and that same frame encoded
    # as a base64 JPEG - returned for every vehicle since this service can't know in
    # advance which one (if any) the Kotlin server will decide is wrong-way.
    bounding_box: BoundingBox | None = None
    frame_jpeg_base64: str | None = None


class AnalyzeResponse(BaseModel):
    vehicles: list[VehicleResult] = Field(default_factory=list)


class HealthResponse(BaseModel):
    status: str = "ok"
