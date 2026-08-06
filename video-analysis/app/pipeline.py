from __future__ import annotations

import math
from collections import defaultdict
from typing import TYPE_CHECKING

from app.config import Settings
from app.corridors import cluster_tracks, corridor_cohesion
from app.frame_encoding import encode_frame_to_base64_jpeg
from app.schemas import AnalyzeResponse, BoundingBox, VehicleResult
from app.tracking_bearing import bbox_diagonal, compute_bearing_degrees, compute_track_midpoint_ms

if TYPE_CHECKING:
    from app.detection import TrackedFrame, VehicleDetector
    from app.ocr import PlateReader

# Bound OCR cost: only the largest-bounding-box frames per track are read, keeping the
# single highest-confidence result above this floor.
OCR_CROPS_PER_TRACK = 3


def _bbox_area(frame: "TrackedFrame") -> float:
    x1, y1, x2, y2 = frame.bbox
    return max(0.0, x2 - x1) * max(0.0, y2 - y1)


class AnalysisPipeline:
    def __init__(self, settings: Settings, detector: "VehicleDetector", plate_reader: "PlateReader"):
        self._settings = settings
        self._detector = detector
        self._plate_reader = plate_reader

    def analyze(self, video_path: str) -> AnalyzeResponse:
        tracks: dict[int, list["TrackedFrame"]] = defaultdict(list)
        for tracked_frame in self._detector.track_video(video_path):
            tracks[tracked_frame.track_id].append(tracked_frame)

        if not tracks:
            return AnalyzeResponse()

        fps = self._detector.read_fps(video_path)

        # All frames share the source video's dimensions; read them off any one.
        any_frame = next(iter(tracks.values()))[0].frame
        frame_height, frame_width = any_frame.shape[:2]

        paths = {
            track_id: [f.centroid for f in sorted(frames, key=lambda f: f.frame_index)]
            for track_id, frames in tracks.items()
        }
        threshold_px = self._settings.corridor_cluster_threshold_fraction * math.hypot(
            frame_width, frame_height
        )
        assignments = cluster_tracks(paths, threshold_px)

        vehicles = [
            self._summarize_track(
                track_id,
                frames,
                corridor_id=assignments[track_id],
                cohesion=corridor_cohesion(track_id, paths, assignments, threshold_px),
                fps=fps,
            )
            for track_id, frames in tracks.items()
        ]
        return AnalyzeResponse(vehicles=vehicles, frame_width=frame_width, frame_height=frame_height)

    def _summarize_track(self, track_id: int, frames: list["TrackedFrame"], corridor_id: int, cohesion: float, fps: float | None) -> VehicleResult:
        frames_sorted = sorted(frames, key=lambda f: f.frame_index)
        centroids = [f.centroid for f in frames_sorted]
        bboxes = [f.bbox for f in frames_sorted]
        bearing = compute_bearing_degrees(centroids, bboxes)
        track_midpoint_ms = compute_track_midpoint_ms(
            frames_sorted[0].frame_index, frames_sorted[-1].frame_index, fps
        )

        lateral_displacement = math.hypot(
            centroids[-1][0] - centroids[0][0], centroids[-1][1] - centroids[0][1]
        )
        scale_displacement = abs(bbox_diagonal(bboxes[-1]) - bbox_diagonal(bboxes[0]))
        displacement = math.hypot(lateral_displacement, scale_displacement)

        vehicle_type = frames_sorted[0].vehicle_type
        detection_confidence = max(f.confidence for f in frames_sorted)

        plate_text, plate_confidence = self._read_best_plate(frames_sorted)

        representative_frame = max(frames_sorted, key=_bbox_area)
        x1, y1, x2, y2 = representative_frame.bbox
        bounding_box = BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2)
        frame_jpeg_base64 = encode_frame_to_base64_jpeg(representative_frame.frame)

        return VehicleResult(
            track_id=track_id,
            vehicle_type=vehicle_type,
            detection_confidence=detection_confidence,
            bearing_degrees=bearing,
            plate_text=plate_text,
            plate_confidence=plate_confidence,
            bounding_box=bounding_box,
            frame_jpeg_base64=frame_jpeg_base64,
            corridor_id=corridor_id,
            corridor_cohesion=cohesion,
            track_frame_count=len(frames_sorted),
            displacement_pixels=displacement,
            track_midpoint_ms=track_midpoint_ms,
        )

    def _read_best_plate(self, frames_sorted: list["TrackedFrame"]) -> tuple[str | None, float | None]:
        largest_frames = sorted(frames_sorted, key=_bbox_area, reverse=True)[:OCR_CROPS_PER_TRACK]

        best_text: str | None = None
        best_confidence = 0.0
        for frame in largest_frames:
            x1, y1, x2, y2 = (int(v) for v in frame.bbox)
            crop = frame.frame[max(y1, 0):max(y2, 0), max(x1, 0):max(x2, 0)]
            if crop.size == 0:
                continue

            text, confidence = self._plate_reader.read_plate(crop)
            if text is not None and confidence > best_confidence:
                best_text, best_confidence = text, confidence

        if best_text is None or best_confidence < self._settings.plate_confidence_floor:
            return None, None
        return best_text, best_confidence
