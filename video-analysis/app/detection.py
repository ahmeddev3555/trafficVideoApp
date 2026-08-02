from __future__ import annotations

from dataclasses import dataclass
from typing import Iterator, Tuple

import cv2
import numpy as np
import supervision as sv
from ultralytics import YOLO

from app.config import Settings

# COCO class ids for the vehicle types this app cares about.
VEHICLE_CLASS_IDS: dict[int, str] = {
    2: "car",
    3: "motorcycle",
    5: "bus",
    7: "truck",
}


@dataclass
class TrackedFrame:
    track_id: int
    vehicle_type: str
    confidence: float
    frame_index: int
    centroid: Tuple[float, float]
    bbox: Tuple[float, float, float, float]  # x1, y1, x2, y2 in pixels
    frame: np.ndarray


class VehicleDetector:
    """YOLOv8 detection + ByteTrack tracking over a video file.

    Zero map/business awareness by design (see the plan's "kept deliberately dumb"
    decision) - yields raw per-frame tracked detections in pixel space; all business logic
    (legal direction, tolerance, CONFIRMED/REJECTED) lives in the Kotlin server.
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        self._model = YOLO(settings.yolo_model_path)
        self._tracker = sv.ByteTrack()

    def track_video(self, video_path: str) -> Iterator[TrackedFrame]:
        capture = cv2.VideoCapture(video_path)
        # Phones recording in portrait store landscape pixel data plus a rotation flag
        # (e.g. 90 degrees) that players apply automatically at display time - without
        # this, cv2 hands YOLO the raw sideways frame, which collapses detection
        # confidence for everything in it (confirmed: a real motorcycle went from a
        # spurious 0.08 to a solid 0.73+ once this was enabled on the same clip).
        capture.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
        frame_index = 0
        try:
            while True:
                read_ok, frame = capture.read()
                if not read_ok:
                    break

                if frame_index % self._settings.frame_stride != 0:
                    frame_index += 1
                    continue

                yield from self._detect_frame(frame, frame_index)
                frame_index += 1
        finally:
            capture.release()

    def _detect_frame(self, frame: np.ndarray, frame_index: int) -> Iterator[TrackedFrame]:
        result = self._model(frame, verbose=False, imgsz=self._settings.detection_imgsz)[0]
        detections = sv.Detections.from_ultralytics(result)

        vehicle_mask = np.isin(detections.class_id, list(VEHICLE_CLASS_IDS.keys()))
        confidence_mask = detections.confidence >= self._settings.min_detection_confidence
        detections = detections[vehicle_mask & confidence_mask]

        detections = self._tracker.update_with_detections(detections)

        for i in range(len(detections)):
            x1, y1, x2, y2 = detections.xyxy[i]
            class_id = int(detections.class_id[i])
            tracker_id = detections.tracker_id[i]
            if tracker_id is None:
                continue

            yield TrackedFrame(
                track_id=int(tracker_id),
                vehicle_type=VEHICLE_CLASS_IDS.get(class_id, "vehicle"),
                confidence=float(detections.confidence[i]),
                frame_index=frame_index,
                centroid=((float(x1) + float(x2)) / 2.0, (float(y1) + float(y2)) / 2.0),
                bbox=(float(x1), float(y1), float(x2), float(y2)),
                frame=frame,
            )
