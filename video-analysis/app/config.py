from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Shared-secret auth for POST /v1/analyze - no default, mirrors the Kotlin server's
    # "secrets have no code default" convention (see app.jwt.secret / app.video-analysis.api-key).
    api_key: str

    yolo_model_path: str = "yolov8n.pt"

    # Process every Nth frame - the main knob for bounding CPU runtime on hardware with no
    # GPU assumed. Needs empirical tuning against real sample clips.
    frame_stride: int = 3

    min_detection_confidence: float = 0.4

    # Floor below which an OCR read is discarded rather than returned as a guess.
    plate_confidence_floor: float = 0.3

    # Corridor clustering: two tracks share a corridor when their paths run within
    # this fraction of the frame diagonal of each other (see app/corridors.py).
    corridor_cluster_threshold_fraction: float = 0.05


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
