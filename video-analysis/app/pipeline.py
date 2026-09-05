from __future__ import annotations

import math
from collections import defaultdict
from typing import TYPE_CHECKING

from app.config import Settings
from app.corridors import cluster_tracks, corridor_cohesion
from app.frame_encoding import encode_frame_to_base64_jpeg
from app.schemas import AnalyzeResponse, BoundingBox, VehicleResult
from app.tracking_bearing import (
    MIN_DISPLACEMENT_PIXELS,
    MIN_OBSERVATIONS,
    bbox_diagonal,
    compute_displacement_pixels,
    compute_track_midpoint_ms,
    resolve_bearing,
    scale_trend,
)

if TYPE_CHECKING:
    from app.detection import TrackedFrame, VehicleDetector
    from app.ocr import PlateReader

# Bound OCR cost: only the largest-bounding-box frames per track are read, keeping the
# single highest-confidence result above this floor.
OCR_CROPS_PER_TRACK = 3

# corridors.py's path_distance is O(len(a) * len(b)) and was designed around "tens of
# points" per track (see its own docstring) - true at the old frame_stride=3, no longer
# true at frame_stride=1 (a full-length track in a long clip can have hundreds of raw
# points). Corridor clustering only needs a path's overall shape/direction, not every
# point, so cap what's actually passed to cluster_tracks/corridor_cohesion - this does
# NOT affect track_frame_count, resolve_bearing, or compute_displacement_pixels, which
# all still see the full, un-subsampled track data below.
MAX_CORRIDOR_PATH_POINTS = 30


def _subsample_path(points: list, max_points: int) -> list:
    """Evenly subsamples a path down to at most max_points, preserving its overall
    shape - see MAX_CORRIDOR_PATH_POINTS for why this exists."""
    if len(points) <= max_points:
        return points
    step = len(points) / max_points
    return [points[int(i * step)] for i in range(max_points)]


def _bbox_area(frame: "TrackedFrame") -> float:
    x1, y1, x2, y2 = frame.bbox
    return max(0.0, x2 - x1) * max(0.0, y2 - y1)


class AnalysisPipeline:
    def __init__(self, settings: Settings, detector: "VehicleDetector", plate_reader: "PlateReader"):
        self._settings = settings
        self._detector = detector
        self._plate_reader = plate_reader

    def analyze(self, video_path: str, zoom_ratio: float = 1.0) -> AnalyzeResponse:
        tracks: dict[int, list["TrackedFrame"]] = defaultdict(list)
        for tracked_frame in self._detector.track_video(video_path):
            tracks[tracked_frame.track_id].append(tracked_frame)

        if not tracks:
            return AnalyzeResponse()

        fps = self._detector.read_fps(video_path)

        # A zoom ratio below 1.0 is physically meaningless (this app never zooms OUT past
        # 1x) and would shrink both scaled thresholds below their calibrated 1x values -
        # clamp defensively rather than trust the caller. Likewise cap the upper bound at
        # 2.0, the app's own documented hard cap on zoom - a crafted or buggy client could
        # otherwise submit an extreme value (e.g. 1000) that isn't blocked by a floor-only
        # clamp.
        effective_zoom_ratio = max(min(zoom_ratio, 2.0), 1.0)

        # All frames share the source video's dimensions; read them off any one.
        any_frame = next(iter(tracks.values()))[0].frame
        frame_height, frame_width = any_frame.shape[:2]

        paths = {
            track_id: _subsample_path(
                [f.centroid for f in sorted(frames, key=lambda f: f.frame_index)],
                MAX_CORRIDOR_PATH_POINTS,
            )
            for track_id, frames in tracks.items()
        }
        # Scales UP with zoom: the same real-world lane separation shows up as MORE pixels
        # at higher zoom, so the pixel threshold representing "same lane" must grow to
        # match, not shrink - see this plan's Global Constraints for the worked example.
        threshold_px = (
            self._settings.corridor_cluster_threshold_fraction
            * math.hypot(frame_width, frame_height)
            * effective_zoom_ratio
        )
        assignments = cluster_tracks(paths, threshold_px)

        # Same scaling direction and reasoning as threshold_px above, applied to the
        # lateral-motion-vs-noise floor in tracking_bearing.py.
        min_displacement_pixels = MIN_DISPLACEMENT_PIXELS * effective_zoom_ratio

        vehicles = [
            self._summarize_track(
                track_id,
                frames,
                corridor_id=assignments[track_id],
                cohesion=corridor_cohesion(track_id, paths, assignments, threshold_px),
                fps=fps,
                min_displacement_pixels=min_displacement_pixels,
            )
            for track_id, frames in tracks.items()
        ]
        return AnalyzeResponse(vehicles=vehicles, frame_width=frame_width, frame_height=frame_height)

    def _summarize_track(
        self,
        track_id: int,
        frames: list["TrackedFrame"],
        corridor_id: int,
        cohesion: float,
        fps: float | None,
        min_displacement_pixels: float,
    ) -> VehicleResult:
        frames_sorted = sorted(frames, key=lambda f: f.frame_index)
        centroids = [f.centroid for f in frames_sorted]
        bboxes = [f.bbox for f in frames_sorted]
        bearing_result = resolve_bearing(centroids, bboxes, min_displacement_pixels=min_displacement_pixels)
        bearing = bearing_result[0] if bearing_result else None
        bearing_source = bearing_result[1] if bearing_result else None
        track_midpoint_ms = compute_track_midpoint_ms(
            frames_sorted[0].frame_index, frames_sorted[-1].frame_index, fps
        )

        displacement = compute_displacement_pixels(centroids, bboxes, min_displacement_pixels=min_displacement_pixels)

        trend, growth_fraction = scale_trend(bboxes)

        vehicle_type = frames_sorted[0].vehicle_type
        detection_confidence = max(f.confidence for f in frames_sorted)

        representative_frame = max(frames_sorted, key=_bbox_area)
        x1, y1, x2, y2 = representative_frame.bbox
        bounding_box = BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2)

        # A track below MIN_OBSERVATIONS frames has bearing_degrees == None (resolve_bearing
        # requires >= MIN_OBSERVATIONS observations; scale_trend does too) and can therefore
        # never qualify as a FlowVehicle on the server - ClipFlowAnalyzer.qualifyVehicles's
        # first line requires a non-null bearing. It also can't be an approach-path grower
        # (that path additionally requires trackFrameCount >= approachMinFrames(30), well
        # above MIN_OBSERVATIONS). Such a track can never be the server's `best` candidate,
        # so its plate and frame are never read - skip the two most expensive per-track
        # operations rather than compute and discard them. See the 2026-09-05
        # video-analysis-cost-reduction design.
        if len(frames_sorted) >= MIN_OBSERVATIONS:
            plate_text, plate_confidence = self._read_best_plate(frames_sorted)
            frame_jpeg_base64 = encode_frame_to_base64_jpeg(representative_frame.frame)
        else:
            plate_text, plate_confidence = None, None
            frame_jpeg_base64 = None

        return VehicleResult(
            track_id=track_id,
            vehicle_type=vehicle_type,
            detection_confidence=detection_confidence,
            bearing_degrees=bearing,
            bearing_source=bearing_source,
            plate_text=plate_text,
            plate_confidence=plate_confidence,
            bounding_box=bounding_box,
            frame_jpeg_base64=frame_jpeg_base64,
            corridor_id=corridor_id,
            corridor_cohesion=cohesion,
            track_frame_count=len(frames_sorted),
            displacement_pixels=displacement,
            track_midpoint_ms=track_midpoint_ms,
            scale_trend=trend,
            scale_growth_fraction=growth_fraction,
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
