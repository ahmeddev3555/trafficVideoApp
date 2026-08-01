# Motorcycle (and Small/Distant Vehicle) Detection Fix: Recording Resolution + Inference Size

## Context

A real end-to-end test against the deployed production server (a real recording containing a motorcycle) came back with the video-analysis service reporting **zero vehicles detected at all** - not just the motorcycle missing while other vehicles were found, but literally nothing, across the entire clip.

Root-caused by direct diagnosis: the video-analysis service's raw `/v1/analyze` response for this clip returned `{"vehicles": [], "frame_width": 0, "frame_height": 0}` (the pipeline's empty-tracks path). Running YOLOv8n directly against sampled frames from the same clip, at YOLO's own default (more permissive) confidence threshold, also found zero detections of any class, on any sampled frame. Confirmed via `cv2.VideoCapture` that the clip decodes correctly (150 real frames, 5s, 30fps) - so this is not a corrupted-file or decode problem.

The clip's actual resolution is **3840x2160 (4K)**. Ultralytics YOLO models downscale input to a fixed inference size (640px by default) before running detection. A motorcycle that occupies a modest fraction of a 4K frame becomes proportionally tiny once shrunk to 640px - likely too small for the smallest ("nano") model variant to register at all. This is a resolution/model-input-size interaction, not a class-filtering bug: `motorcycle` (COCO class 3) is already in `VEHICLE_CLASS_IDS` in `video-analysis/app/detection.py` and always has been.

## Scope

In scope: two small, independent, complementary changes that address the root cause without changing the model itself.

Explicitly out of scope (decided during brainstorming):
- **No camera zoom feature.** Considered as a complementary way to make distant vehicles appear larger in frame, but deferred - not part of this change.
- **No larger YOLO model variant** (e.g. yolov8s/m). More capacity would help, but at meaningfully higher per-frame CPU cost, working against the project's explicit no-GPU/CPU-bound design. Held in reserve if the two changes below prove insufficient in real testing.
- No changes to `frame_stride`, OCR, tracking, or corridor-clustering logic.

## Change 1: Cap recording resolution to 1080p (Android)

`CameraController.kt`'s `Recorder.Builder()` currently uses:
```kotlin
.setQualitySelector(QualitySelector.from(Quality.HIGHEST))
```
`Quality.HIGHEST` selects the device's maximum available recording quality - 4K on the test device. Change to:
```kotlin
.setQualitySelector(QualitySelector.from(Quality.FHD))
```
`Quality.FHD` is CameraX's standard "Full HD" (1080p) quality tier, supported by virtually every device, and far more resolution than vehicle detection or plate OCR needs at realistic bystander-filming distances. This is the root-cause fix: recording at a sane resolution means objects are proportionally larger in the frame YOLO actually processes, since less aggressive downscaling happens between capture and inference. It also shrinks upload size/time and server storage/decode cost, with no other part of the record -> trim -> upload pipeline affected (it is resolution-agnostic).

## Change 2: Raise YOLO's inference resolution (Python, server-side)

`detection.py`'s `_detect_frame` currently calls `self._model(frame, verbose=False)[0]`, letting Ultralytics use its implicit default `imgsz=640`. Add a new setting:

```python
# app/config.py
detection_imgsz: int = 960
```

and pass it through:
```python
# app/detection.py
result = self._model(frame, verbose=False, imgsz=self._settings.detection_imgsz)[0]
```

960 is a modest, CPU-affordable bump from the default 640 - extra safety margin for objects still small/distant even at 1080p, without the steeper per-frame cost of jumping straight to 1280+. Being a config value (not hardcoded) means it can be retuned server-side alone if real-world testing shows it needs adjustment, with no app rebuild/redeploy cycle needed.

## Testing

No new automated tests - there is no existing fixture-based detection-accuracy test suite (would require committed real video sample assets, which isn't how this project tests today); this stays manual, consistent with how this feature area has been verified throughout.

- Record a fresh clip (now at 1080p) containing a motorcycle at a realistic bystander distance; submit it; confirm the video-analysis service's raw response now includes a `motorcycle` entry (checkable via the same direct `/v1/analyze` diagnostic call used to root-cause this issue).
- Spot-check that car/bus/truck detection is not regressed by the resolution/imgsz change (existing detection behavior should only improve or stay the same).
- Confirm upload size/time drops noticeably (expected side effect, not a hard requirement).
