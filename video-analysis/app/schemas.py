from __future__ import annotations

from pydantic import BaseModel, Field


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


class AnalyzeResponse(BaseModel):
    vehicles: list[VehicleResult] = Field(default_factory=list)


class HealthResponse(BaseModel):
    status: str = "ok"
