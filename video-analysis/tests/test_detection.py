from __future__ import annotations

from unittest.mock import MagicMock, patch

import cv2

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
