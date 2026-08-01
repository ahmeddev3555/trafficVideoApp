# Motorcycle Detection Resolution Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix zero-detection failures (motorcycles and other vehicles) caused by recording at 4K, by capping recording resolution to 1080p and giving YOLO a modestly higher inference resolution as extra margin.

**Architecture:** Two small, independent changes: an Android `CameraX` quality-selector change (1080p instead of device-max), and a Python config value threaded into the existing YOLO detection call (`imgsz`). Neither touches tracking, OCR, corridor clustering, or the Kotlin server.

**Tech Stack:** Android CameraX (`androidx.camera.video`), Python/Ultralytics YOLOv8, pydantic-settings.

## Global Constraints

- Recording quality: `Quality.FHD` (CameraX's 1080p tier) — not a custom resolution, not `Quality.HD` or `Quality.HIGHEST`.
- New Python setting name: `detection_imgsz`, type `int`, default `960`.
- No automated tests for either change (per the approved spec's "Testing" section — no fixture-based detection-accuracy suite exists in this project; verification is manual, matching how this whole feature area has been tested throughout). Do not introduce mocked YOLO/OpenCV tests as a substitute — they would test mock behavior, not real detection behavior.
- No changes to `frame_stride`, OCR, tracking, corridor-clustering logic, or any Kotlin server code.
- No camera zoom feature (explicitly out of scope, deferred).
- No YOLO model-size change (yolov8n stays; explicitly deferred per the spec).

---

### Task 1: Cap Android recording resolution to 1080p

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt:85-87`

**Interfaces:**
- None — this is a self-contained change inside `bindCamera`'s local `Recorder.Builder()` call. No other file references `Quality.HIGHEST` or this recorder configuration.

- [ ] **Step 1: Change the quality selector**

In `CameraController.kt`, replace:
```kotlin
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
```
with:
```kotlin
            // Quality.HIGHEST previously recorded at the device's native max (4K on some
            // phones), which made vehicles proportionally tiny once YOLO downscales the
            // frame for inference - motorcycles in particular went completely undetected.
            // FHD (1080p) is far more resolution than vehicle detection or plate OCR need
            // at realistic bystander-filming distances.
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()
```

- [ ] **Step 2: Build to verify it compiles**

Run (from repo root): `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification**

Install on the connected device (`./gradlew.bat :app:installDebug`), record a short clip, and confirm (via `adb shell dumpsys media.camera` output, or simply checking the resulting file's properties - e.g. pull it and run the same `cv2.VideoCapture` inspection used earlier this session: `cap.get(cv2.CAP_PROP_FRAME_WIDTH)` / `cap.get(cv2.CAP_PROP_FRAME_HEIGHT)`) that the recorded video is now 1920x1080, not 3840x2160.
Expected: frame dimensions are `1920 x 1080`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/camera/CameraController.kt
git commit -m "fix(app): cap recording resolution to 1080p instead of device-max"
```

---

### Task 2: Raise YOLO's inference resolution server-side

**Files:**
- Modify: `video-analysis/app/config.py`
- Modify: `video-analysis/app/detection.py:64-65`

**Interfaces:**
- Produces: `Settings.detection_imgsz: int` (default `960`) - a new config value, consumed only within `VehicleDetector._detect_frame` in this plan. Overridable via the `DETECTION_IMGSZ` environment variable / `.env` entry, same mechanism as every other `Settings` field (pydantic-settings' `env_file=".env"`).

- [ ] **Step 1: Add the setting**

In `video-analysis/app/config.py`, add to the `Settings` class, after `corridor_cluster_threshold_fraction`:
```python
    # YOLO's own default inference size (640px) shrinks a 4K-cropped-to-1080p frame's
    # vehicles more than necessary; this modest bump gives extra margin for small/distant
    # objects (e.g. motorcycles) without the steeper per-frame CPU cost of jumping straight
    # to 1280+. Retune here alone if real-world testing shows it still needs adjustment -
    # no app rebuild required.
    detection_imgsz: int = 960
```

The full class becomes:
```python
class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Shared-secret auth for POST /v1/analyze - no default, mirrors the Kotlin server's
    # "secrets have no code default" convention (see app.jwt.secret / app.video-analysis.api-key).
    api_key: str

    yolo_model_path: str = "yolov8n.pt"

    # Process every Nth frame - the main knob for bounding CPU runtime on hardware with no
    # GPU assumed. Needs empirical tuning against real sample clips.
    frame_stride: int = 3

    min_detection_confidence: float = 0.4

    # Floor below which an OCR read is discarded rather than returned as a guess.
    plate_confidence_floor: float = 0.3

    # Corridor clustering: two tracks share a corridor when their paths run within
    # this fraction of the frame diagonal of each other (see app/corridors.py).
    corridor_cluster_threshold_fraction: float = 0.05

    # YOLO's own default inference size (640px) shrinks a 4K-cropped-to-1080p frame's
    # vehicles more than necessary; this modest bump gives extra margin for small/distant
    # objects (e.g. motorcycles) without the steeper per-frame CPU cost of jumping straight
    # to 1280+. Retune here alone if real-world testing shows it still needs adjustment -
    # no app rebuild required.
    detection_imgsz: int = 960
```

- [ ] **Step 2: Thread it into the detection call**

In `video-analysis/app/detection.py`, in `_detect_frame`, replace:
```python
    def _detect_frame(self, frame: np.ndarray, frame_index: int) -> Iterator[TrackedFrame]:
        result = self._model(frame, verbose=False)[0]
```
with:
```python
    def _detect_frame(self, frame: np.ndarray, frame_index: int) -> Iterator[TrackedFrame]:
        result = self._model(frame, verbose=False, imgsz=self._settings.detection_imgsz)[0]
```

- [ ] **Step 3: Run the existing Python suite to confirm nothing broke**

Run (from `video-analysis/`): `.venv/Scripts/python.exe -m pytest -v`
Expected: all existing tests still PASS (none of them exercise `_detect_frame`'s real model call, so none should be affected - this just confirms the module still imports and nothing else regressed).

- [ ] **Step 4: Manual verification**

Rebuild and redeploy the `video-analysis` Docker image on the production server (`docker compose -f docker-compose.prod.yml up -d --build video-analysis`), then record a fresh clip (now at 1080p, from Task 1) containing a motorcycle at a realistic bystander distance and submit it. Check the raw detection output directly - the same technique used to root-cause this issue: copy the report's stored video into a container with `curl` and POST it straight to `http://video-analysis:8000/v1/analyze` with the `X-API-Key` header, then inspect the JSON response's `vehicles` array.
Expected: the response includes at least one entry with `"vehicle_type": "motorcycle"` (or, if the test clip's vehicle is a different type, that vehicle's entry is present) - i.e., `vehicles` is no longer empty for a clip that visibly contains a vehicle. Also spot-check that a clip containing only a car still detects the car (no regression).

- [ ] **Step 5: Commit**

```bash
git add video-analysis/app/config.py video-analysis/app/detection.py
git commit -m "fix(video-analysis): raise YOLO inference resolution (imgsz 640 -> 960) for small/distant vehicles"
```
