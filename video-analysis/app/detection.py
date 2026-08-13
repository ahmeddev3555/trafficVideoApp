from __future__ import annotations

import math
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

# COCO class id for motorcycles - tracked separately from every other vehicle type
# (see the design spec) because small, fast, near-camera objects systematically fail
# ByteTrack's hardcoded unconfirmed-track IoU threshold under this service's sampling.
MOTORCYCLE_CLASS_ID = 3

# Added to every motorcycle track's id before it leaves VehicleDetector. The two
# ByteTrack instances' internal id counters are not guaranteed to be independent
# per-instance (confirmed empirically against supervision 0.23.0 - id assignment is
# not scoped to a single tracker instance within a process), so without this offset a
# motorcycle track and a car track could plausibly share the same raw numeric id and
# get conflated by pipeline.py's grouping-by-track_id. Chosen far larger than any
# plausible single-clip track count.
MOTORCYCLE_TRACK_ID_OFFSET = 1_000_000


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
        # Car/bus/truck tracker: lost_track_buffer=90 restores occlusion tolerance after
        # frame_stride changed from 3 to 1. ByteTrack measures occlusion in ticks (calls to
        # update_with_detections), not seconds. At the old frame_stride=3 cadence,
        # lost_track_buffer=30 gave ~3.0 real seconds of tolerance (30 ticks ÷ 10
        # analyzed-ticks/real-second). At the new frame_stride=1 cadence (30 analyzed-ticks/
        # real-second now), reaching that same ~3.0s real tolerance requires lost_track_buffer=90
        # (90 ticks ÷ 30 ticks/sec = 3.0s). See ByteTrack.max_time_lost derivation for details.
        self._tracker = sv.ByteTrack(lost_track_buffer=90)
        self._moto_tracker = sv.ByteTrack()

    def track_video(self, video_path: str) -> Iterator[TrackedFrame]:
        capture = cv2.VideoCapture(video_path)
        # Phones recording in portrait store landscape pixel data plus a rotation flag
        # (e.g. 90 degrees) that players apply automatically at display time - without
        # this, cv2 hands YOLO the raw sideways frame, which collapses detection
        # confidence for everything in it (confirmed: a real motorcycle went from a
        # spurious 0.08 to a solid 0.73+ once this was enabled on the same clip).
        capture.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
        # Reset both trackers at the start of each video. This clears tracked/lost/removed
        # track lists, resets the per-tracker frame counter, and resets the process-global
        # external-id counter. Without this, a long-running VehicleDetector instance
        # (see main.py - one is constructed at startup and reused for every report) would
        # leak tracking state and ID numbering between separate videos.
        self._tracker.reset()
        self._moto_tracker.reset()
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

    def read_fps(self, video_path: str) -> float | None:
        """Video frame rate in fps, or None if unavailable/invalid - some malformed or
        variable-frame-rate videos report 0, negative, or NaN from CAP_PROP_FPS. Never a
        fabricated value; callers (see compute_track_midpoint_ms) treat None as
        "timing unavailable for this clip", same graceful-degradation contract as every
        other optional signal in this service."""
        capture = cv2.VideoCapture(video_path)
        try:
            fps = capture.get(cv2.CAP_PROP_FPS)
        finally:
            capture.release()
        if fps is None or fps <= 0 or math.isnan(fps):
            return None
        return fps

    def _detect_frame(self, frame: np.ndarray, frame_index: int) -> Iterator[TrackedFrame]:
        result = self._model(frame, verbose=False, imgsz=self._settings.detection_imgsz)[0]
        detections = sv.Detections.from_ultralytics(result)

        vehicle_mask = np.isin(detections.class_id, list(VEHICLE_CLASS_IDS.keys()))
        confidence_mask = detections.confidence >= self._settings.min_detection_confidence
        detections = detections[vehicle_mask & confidence_mask]

        moto_mask = detections.class_id == MOTORCYCLE_CLASS_ID
        moto_detections = self._moto_tracker.update_with_detections(detections[moto_mask])
        other_detections = self._tracker.update_with_detections(detections[~moto_mask])

        yield from self._tracked_frames_from(other_detections, frame, frame_index, id_offset=0)
        yield from self._tracked_frames_from(
            moto_detections, frame, frame_index, id_offset=MOTORCYCLE_TRACK_ID_OFFSET
        )

    def _tracked_frames_from(
        self, detections: sv.Detections, frame: np.ndarray, frame_index: int, id_offset: int
    ) -> Iterator[TrackedFrame]:
        for i in range(len(detections)):
            x1, y1, x2, y2 = detections.xyxy[i]
            class_id = int(detections.class_id[i])
            tracker_id = detections.tracker_id[i]
            if tracker_id is None:
                continue

            yield TrackedFrame(
                track_id=int(tracker_id) + id_offset,
                vehicle_type=VEHICLE_CLASS_IDS.get(class_id, "vehicle"),
                confidence=float(detections.confidence[i]),
                frame_index=frame_index,
                centroid=((float(x1) + float(x2)) / 2.0, (float(y1) + float(y2)) / 2.0),
                bbox=(float(x1), float(y1), float(x2), float(y2)),
                frame=frame,
            )
