from __future__ import annotations

import numpy as np

from app.config import Settings
from app.detection import TrackedFrame
from app.pipeline import AnalysisPipeline


class FakeDetector:
    def __init__(self, frames: list[TrackedFrame]):
        self._frames = frames

    def track_video(self, video_path: str):
        yield from self._frames


class FakePlateReader:
    def read_plate(self, crop):
        return "ABC-123", 0.75


def _make_frame(track_id: int, frame_index: int, bbox: tuple[float, float, float, float]) -> TrackedFrame:
    image = np.zeros((100, 100, 3), dtype=np.uint8)
    x1, y1, x2, y2 = bbox
    return TrackedFrame(
        track_id=track_id,
        vehicle_type="car",
        confidence=0.9,
        frame_index=frame_index,
        centroid=((x1 + x2) / 2.0, (y1 + y2) / 2.0),
        bbox=bbox,
        frame=image,
    )


def _fake_settings() -> Settings:
    return Settings(
        api_key="test-key",
        yolo_model_path="unused.pt",
        frame_stride=1,
        min_detection_confidence=0.5,
        plate_confidence_floor=0.3,
    )


def test_summarize_track_attaches_bounding_box_and_frame_from_the_largest_bbox_frame():
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(10.0, 10.0, 20.0, 20.0)),  # area 100
        _make_frame(track_id=1, frame_index=1, bbox=(5.0, 5.0, 30.0, 30.0)),   # area 625 - largest
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    results = pipeline.analyze("unused.mp4")

    assert len(results) == 1
    vehicle = results[0]
    assert vehicle.bounding_box is not None
    assert vehicle.bounding_box.x1 == 5.0
    assert vehicle.bounding_box.y1 == 5.0
    assert vehicle.bounding_box.x2 == 30.0
    assert vehicle.bounding_box.y2 == 30.0
    assert vehicle.frame_jpeg_base64 is not None
    assert len(vehicle.frame_jpeg_base64) > 0
