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


def test_head_on_approaching_track_gets_a_real_bearing_and_a_scale_dominated_displacement():
    # Centroid stays fixed (zero lateral motion) while the bounding box grows dramatically -
    # a vehicle driving straight at the camera. Before this fix, bearing_degrees would be
    # None and displacement_pixels would be 0.0 (lateral-only), silently dropping the vehicle
    # from all downstream flow analysis regardless of how obvious the approach was visually.
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(40.0, 40.0, 60.0, 60.0))
        for i in range(4)
    ] + [
        _make_frame(track_id=1, frame_index=i, bbox=(0.0, 0.0, 100.0, 100.0))
        for i in range(4, 8)
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    vehicle = response.vehicles[0]
    assert vehicle.bearing_degrees == 180.0
    # lateral displacement is 0 (all _make_frame calls use bbox center (50,50) as centroid,
    # since _make_frame derives centroid from the bbox passed in); scale displacement is
    # |diagonal(100,100 box) - diagonal(20,20 box)| = |141.42... - 28.28...| ~= 113.14,
    # combined via hypot(0, scale) = the same ~113.14 - large enough to clear
    # ClipFlowAnalyzer's existing displacement floor on the Kotlin side (unmodified by this
    # plan), where today's lateral-only 0.0 would not have.
    assert vehicle.displacement_pixels == pytest.approx(113.137, abs=0.01)
    assert vehicle.bearing_source == "scale"


def test_ordinary_lateral_track_displacement_is_unaffected_by_bbox_size_change():
    # A track with clear lateral motion (well over the 8px floor) whose bbox ALSO changes
    # size substantially (as a real vehicle's box naturally does while crossing the frame) -
    # displacement_pixels must reflect lateral motion ONLY, not be inflated by the
    # unrelated bbox size change, restoring this plan's own invariant.
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(0.0, 0.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=1, bbox=(0.0, 0.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=2, bbox=(0.0, 0.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=3, bbox=(0.0, 0.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=4, bbox=(50.0, 0.0, 250.0, 200.0)),
        _make_frame(track_id=1, frame_index=5, bbox=(50.0, 0.0, 250.0, 200.0)),
        _make_frame(track_id=1, frame_index=6, bbox=(50.0, 0.0, 250.0, 200.0)),
        _make_frame(track_id=1, frame_index=7, bbox=(50.0, 0.0, 250.0, 200.0)),
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    vehicle = response.vehicles[0]
    # Centroid moves from (10,10) to (150,100): lateral = hypot(140, 90) ~= 166.5, well over
    # the 8px floor, even though the bbox diagonal also grew dramatically (28.28 -> 269.26).
    assert vehicle.displacement_pixels == pytest.approx(166.5, abs=0.5)
    assert vehicle.bearing_source == "centroid"
