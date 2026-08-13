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
    # GPU assumed. Must stay at 1 (every frame) for reliable motorcycle tracking: ByteTrack's
    # hardcoded unconfirmed-track IoU threshold (0.7, not exposed via any constructor
    # parameter) fails for small/fast/near-camera objects once frame_stride widens the real
    # displacement between samples the tracker sees - confirmed empirically against a real
    # production clip (see
    # docs/superpowers/specs/2026-08-13-motorcycle-tracking-iou-fix-design.md). Car/bus/truck
    # tracking tolerates a wider stride fine since their boxes are larger relative to the
    # same absolute displacement, but this field is shared - there is no way to sample more
    # densely for one class without sampling more densely, period.
    frame_stride: int = 1

    min_detection_confidence: float = 0.4

    # Floor below which an OCR read is discarded rather than returned as a guess.
    plate_confidence_floor: float = 0.3

    # Corridor clustering: two tracks share a corridor when their paths run within
    # this fraction of the frame diagonal of each other (see app/corridors.py).
    corridor_cluster_threshold_fraction: float = 0.05

    # YOLO's own default inference size (640px) shrinks a 4K-cropped-to-1080p frame's
    # vehicles more than necessary; this modest bump gives extra margin for small/distant
    # objects (e.g. motorcycles) without the steeper per-frame CPU cost of jumping straight
    # to 1280+. Retune here alone if real-world testing shows it still needs adjustment -
    # no app rebuild required.
    detection_imgsz: int = 960


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
