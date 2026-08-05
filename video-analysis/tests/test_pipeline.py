from __future__ import annotations

import numpy as np
import pytest

from app.config import Settings
from app.detection import TrackedFrame
from app.pipeline import AnalysisPipeline


class FakeDetector:
    def __init__(self, frames: list[TrackedFrame], fps: float | None = 30.0):
        self._frames = frames
        self._fps = fps

    def track_video(self, video_path: str):
        yield from self._frames

    def read_fps(self, video_path: str) -> float | None:
        return self._fps


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
        corridor_cluster_threshold_fraction=0.05,
    )


def test_summarize_track_attaches_bounding_box_and_frame_from_the_largest_bbox_frame():
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(10.0, 10.0, 20.0, 20.0)),  # area 100
        _make_frame(track_id=1, frame_index=1, bbox=(5.0, 5.0, 30.0, 30.0)),   # area 625 - largest
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    assert response.frame_width == 100
    assert response.frame_height == 100
    assert len(response.vehicles) == 1
    vehicle = response.vehicles[0]
    assert vehicle.bounding_box is not None
    assert vehicle.bounding_box.x1 == 5.0
    assert vehicle.bounding_box.y1 == 5.0
    assert vehicle.bounding_box.x2 == 30.0
    assert vehicle.bounding_box.y2 == 30.0
    assert vehicle.frame_jpeg_base64 is not None
    assert len(vehicle.frame_jpeg_base64) > 0
    assert vehicle.track_frame_count == 2
    assert vehicle.displacement_pixels > 0.0


def test_same_lane_tracks_share_a_corridor_and_opposite_lane_does_not():
    frames = []
    # Tracks 1 and 2: near-identical vertical paths around x=20.
    for i in range(6):
        frames.append(_make_frame(track_id=1, frame_index=i, bbox=(15.0, 10.0 * i, 25.0, 10.0 * i + 10.0)))
        frames.append(_make_frame(track_id=2, frame_index=i, bbox=(17.0, 10.0 * i, 27.0, 10.0 * i + 10.0)))
    # Track 3: vertical path far away around x=80 (distance > 5% of the 100x100
    # frame's diagonal, ~7.07px threshold).
    for i in range(6):
        frames.append(_make_frame(track_id=3, frame_index=i, bbox=(75.0, 10.0 * i, 85.0, 10.0 * i + 10.0)))

    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )
    response = pipeline.analyze("unused.mp4")

    by_track = {v.track_id: v for v in response.vehicles}
    assert by_track[1].corridor_id == by_track[2].corridor_id
    assert by_track[3].corridor_id != by_track[1].corridor_id
    assert 0.0 <= by_track[1].corridor_cohesion <= 1.0


def test_empty_video_returns_empty_response_with_zero_dimensions():
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector([]), plate_reader=FakePlateReader()
    )
    response = pipeline.analyze("unused.mp4")
    assert response.vehicles == []
    assert response.frame_width == 0
    assert response.frame_height == 0


def test_summarize_track_attaches_track_midpoint_ms_from_fps():
    # frame_index 0 and 9 at 30fps (FakeDetector's default) -> midpoint frame 4.5 -> 150ms.
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(10.0, 10.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=9, bbox=(10.0, 10.0, 20.0, 20.0)),
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    assert response.vehicles[0].track_midpoint_ms == pytest.approx(150, abs=1)


def test_summarize_track_track_midpoint_ms_is_none_when_fps_unavailable():
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(10.0, 10.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=9, bbox=(10.0, 10.0, 20.0, 20.0)),
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames, fps=None), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    assert response.vehicles[0].track_midpoint_ms is None
