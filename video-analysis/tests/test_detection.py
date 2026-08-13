from __future__ import annotations

from unittest.mock import MagicMock, patch

import cv2
import numpy as np
import pytest
import supervision as sv

from app.config import Settings


def _fake_settings() -> Settings:
    return Settings(
        api_key="test-key",
        yolo_model_path="unused.pt",
    )


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_track_video_enables_orientation_auto_before_reading_frames(mock_video_capture, mock_yolo):
    """Phone-recorded video stores landscape pixel data plus a rotation flag (90 degrees
    for a portrait recording) for players to apply at display time; cv2.VideoCapture does
    not apply it by default, so a real clip's frames arrive sideways and YOLO's detection
    confidence collapses (confirmed empirically: a real motorcycle went from a spurious
    0.08 to a solid 0.73+ once this flag was enabled). CAP_PROP_ORIENTATION_AUTO tells
    OpenCV's FFmpeg backend to apply the file's own rotation metadata automatically."""
    mock_capture = MagicMock()
    mock_capture.read.return_value = (False, None)  # no frames - loop exits immediately
    mock_video_capture.return_value = mock_capture

    from app.detection import VehicleDetector

    detector = VehicleDetector(_fake_settings())
    list(detector.track_video("irrelevant.mp4"))

    mock_capture.set.assert_called_once_with(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
    # Must be set before any frame is read - setting it after the first read() would
    # already have handed YOLO an un-rotated frame.
    assert mock_capture.method_calls[0] == ("set", (cv2.CAP_PROP_ORIENTATION_AUTO, 1), {})


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_read_fps_returns_the_capture_fps(mock_video_capture, mock_yolo):
    mock_capture = MagicMock()
    mock_capture.get.return_value = 29.97
    mock_video_capture.return_value = mock_capture

    from app.detection import VehicleDetector

    detector = VehicleDetector(_fake_settings())
    fps = detector.read_fps("irrelevant.mp4")

    assert fps == pytest.approx(29.97)
    mock_capture.get.assert_called_once_with(cv2.CAP_PROP_FPS)
    mock_capture.release.assert_called_once()


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_read_fps_returns_none_for_zero_or_invalid_fps(mock_video_capture, mock_yolo):
    mock_capture = MagicMock()
    mock_video_capture.return_value = mock_capture

    from app.detection import VehicleDetector

    detector = VehicleDetector(_fake_settings())

    mock_capture.get.return_value = 0.0
    assert detector.read_fps("irrelevant.mp4") is None

    mock_capture.get.return_value = float("nan")
    assert detector.read_fps("irrelevant.mp4") is None

    mock_capture.get.return_value = -1.0
    assert detector.read_fps("irrelevant.mp4") is None


def test_dense_sampling_persists_a_low_iou_motorcycle_track_that_sparse_sampling_drops():
    """Directly demonstrates the real bug mechanism against the real (un-mocked)
    ByteTrack, independent of VehicleDetector's own mocking - no code under test here
    yet, this documents WHY frame_stride must be 1, verified empirically against the
    exact library version this service uses (supervision 0.23.0). A brand-new
    ByteTrack track only auto-confirms if created on frame_id==1 of the tracker's
    lifetime; every other new track gets exactly one chance, on its very next
    reconsidered frame, to re-match with IoU >= 0.7 - a threshold hardcoded inside the
    library, not exposed via any constructor parameter (see the design spec). The same
    underlying motion, sampled densely (every real tick) vs sparsely (every 3rd tick,
    matching today's frame_stride=3), demonstrates the difference directly: small
    per-tick steps keep consecutive-frame IoU above 0.7; skipping two out of three
    ticks triples the apparent displacement the tracker sees between updates and
    collapses it below 0.7."""
    # A small (20x30px) box moving 8px per real tick - representative of a motorcycle
    # near a moving recording camera.
    true_ticks = [(10.0 + 8 * i, 10.0 + 2 * i, 30.0 + 8 * i, 40.0 + 2 * i) for i in range(18)]

    def run(boxes):
        tracker = sv.ByteTrack()
        results = []
        for x1, y1, x2, y2 in boxes:
            dets = sv.Detections(
                xyxy=np.array([[x1, y1, x2, y2]], dtype=np.float32),
                confidence=np.array([0.6], dtype=np.float32),
                class_id=np.array([3]),
            )
            post = tracker.update_with_detections(dets)
            results.append(post.tracker_id[0] if len(post) > 0 else None)
        return results

    dense = true_ticks[0::1][:6]  # every tick - frame_stride=1 equivalent
    sparse = true_ticks[0::3][:6]  # every 3rd tick - today's frame_stride=3 equivalent

    dense_ids = run(dense)
    sparse_ids = run(sparse)

    assert dense_ids.count(None) == 0, f"dense sampling should track every observation, got {dense_ids}"
    assert len(set(dense_ids)) == 1, f"dense sampling should be one continuous track, got {dense_ids}"
    assert sparse_ids[1:].count(None) == len(sparse_ids) - 1, (
        f"sparse sampling of the same motion should drop every observation after the first "
        f"(reproducing today's bug), got {sparse_ids}"
    )


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_motorcycle_track_with_low_frame_to_frame_iou_still_persists(mock_video_capture, mock_yolo):
    """Integration-level regression test for the real production bug through the actual
    VehicleDetector: a small, fast-moving motorcycle sampled at frame_stride=1 via the
    dedicated motorcycle tracker must produce a persisted track - the old shared-tracker,
    frame_stride=3 code dropped this same class of motion entirely (see the sibling test
    above for the direct, un-mocked ByteTrack evidence of why)."""
    num_frames = 6
    dummy_frames = [np.zeros((10, 10, 3), dtype=np.uint8) for _ in range(num_frames)]
    mock_capture = MagicMock()
    mock_capture.read.side_effect = [(True, f) for f in dummy_frames] + [(False, None)]
    mock_video_capture.return_value = mock_capture

    # Same 8px-per-frame motion confirmed above to persist under dense sampling.
    moto_boxes = [(10.0 + 8 * i, 10.0 + 2 * i, 30.0 + 8 * i, 40.0 + 2 * i) for i in range(num_frames)]

    call_count = {"n": 0}

    def fake_model_call(frame, **kwargs):
        idx = call_count["n"]
        call_count["n"] += 1
        return [moto_boxes[idx]]  # result[0] below picks this back out

    mock_model_instance = MagicMock(side_effect=fake_model_call)
    mock_yolo.return_value = mock_model_instance

    def fake_from_ultralytics(result):
        x1, y1, x2, y2 = result
        return sv.Detections(
            xyxy=np.array([[x1, y1, x2, y2]], dtype=np.float32),
            confidence=np.array([0.6], dtype=np.float32),
            class_id=np.array([3]),  # motorcycle
        )

    from app.detection import VehicleDetector

    settings = _fake_settings()
    settings.frame_stride = 1

    with patch("app.detection.sv.Detections.from_ultralytics", side_effect=fake_from_ultralytics):
        detector = VehicleDetector(settings)
        results = list(detector.track_video("irrelevant.mp4"))

    motorcycle_frames = [r for r in results if r.vehicle_type == "motorcycle"]
    assert len(motorcycle_frames) == num_frames, (
        f"expected all {num_frames} observations to persist as one track, got "
        f"{len(motorcycle_frames)} - the old single-tracker code drops all but the first"
    )
    track_ids = {r.track_id for r in motorcycle_frames}
    assert len(track_ids) == 1, f"expected one persisted track, got {len(track_ids)} distinct ids"
    assert next(iter(track_ids)) >= 1_000_000, "motorcycle track_id must carry the offset"


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_car_and_motorcycle_track_ids_never_collide(mock_video_capture, mock_yolo):
    """The two ByteTrack instances' internal id counters are not guaranteed to be
    independent per-instance (confirmed empirically: supervision 0.23.0's id assignment
    is not scoped to a single tracker instance within a process), so a car track and a
    motorcycle track could plausibly share the same raw underlying numeric id. The
    explicit offset applied to every motorcycle track_id must keep the two id spaces
    disjoint regardless of what the raw underlying values happen to be - this test
    checks the invariant, not specific hardcoded id values, since the raw values are
    not part of any documented, stable library contract."""
    dummy_frame = np.zeros((10, 10, 3), dtype=np.uint8)
    mock_capture = MagicMock()
    mock_capture.read.side_effect = [(True, dummy_frame), (False, None)]
    mock_video_capture.return_value = mock_capture

    def fake_model_call(frame, **kwargs):
        return ["one_frame"]  # single call - content unused, from_ultralytics is faked below

    mock_model_instance = MagicMock(side_effect=fake_model_call)
    mock_yolo.return_value = mock_model_instance

    def fake_from_ultralytics(result):
        # One car (class 2) and one motorcycle (class 3) in the same frame.
        return sv.Detections(
            xyxy=np.array([[0.0, 0.0, 50.0, 50.0], [60.0, 60.0, 90.0, 100.0]], dtype=np.float32),
            confidence=np.array([0.9, 0.6], dtype=np.float32),
            class_id=np.array([2, 3]),
        )

    from app.detection import VehicleDetector

    settings = _fake_settings()
    settings.frame_stride = 1

    with patch("app.detection.sv.Detections.from_ultralytics", side_effect=fake_from_ultralytics):
        detector = VehicleDetector(settings)
        results = list(detector.track_video("irrelevant.mp4"))

    by_type = {r.vehicle_type: r.track_id for r in results}
    assert by_type["car"] < 1_000_000, "car track_id should never carry the motorcycle offset"
    assert by_type["motorcycle"] >= 1_000_000, "motorcycle track_id must carry the offset"
    assert by_type["car"] != by_type["motorcycle"]
