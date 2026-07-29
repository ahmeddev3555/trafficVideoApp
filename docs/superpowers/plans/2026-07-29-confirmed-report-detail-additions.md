# Confirmed Report Detail Additions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a location map pin, an annotated (red-boxed) image of the flagged wrong-way vehicle, and a real wrong-way confidence score to the `CONFIRMED` report detail screen.

**Architecture:** The Python video-analysis service returns a representative frame (base64 JPEG) + bounding box per tracked vehicle alongside its existing fields. The Kotlin server, once it picks the wrong-way vehicle (existing logic), draws a red box on that one vehicle's frame with plain Java AWT and stores it; a new confidence score is computed from data already available (detection confidence x how tight the bearing match is). A new `GET /reports/{id}/wrong-way-frame` endpoint serves the stored image. The Android app adds an osmdroid map composable and wires the new fields/image into `ReportDetailScreen`, `CONFIRMED`-only.

**Tech Stack:** FastAPI/OpenCV (Python service), Spring Boot/Kotlin + plain `java.awt`/`javax.imageio` (server, no new server dependency), osmdroid + Coil (Android, one new Gradle dependency).

## Global Constraints

- New reports only - no backfill/reprocessing of already-`CONFIRMED` reports (per spec).
- Map uses OpenStreetMap via `org.osmdroid:osmdroid-android:6.1.20` - no Google Maps, no API key (per spec; osmdroid is unmaintained since Nov 2024 but this is a low-surface-area, non-interactive use - confirmed acceptable with the user).
- The red box is drawn server-side (Kotlin), never by the Android app (per spec).
- `PENDING`/`REJECTED` report detail layouts are unchanged - all new UI is `CONFIRMED`-only.
- `wrongWayConfidence` is a new, separate field from the existing `confidence` (license-plate OCR confidence) - never conflate the two.

---

### Task 1: Python - pure frame-encoding helper

**Files:**
- Create: `video-analysis/app/frame_encoding.py`
- Test: `video-analysis/tests/test_frame_encoding.py`

**Interfaces:**
- Produces: `encode_frame_to_base64_jpeg(frame: np.ndarray) -> str` - used by Task 2.

- [ ] **Step 1: Write the failing test**

```python
# video-analysis/tests/test_frame_encoding.py
from __future__ import annotations

import base64

import numpy as np

from app.frame_encoding import encode_frame_to_base64_jpeg


def test_encode_frame_to_base64_jpeg_produces_decodable_jpeg_bytes():
    frame = np.zeros((10, 10, 3), dtype=np.uint8)

    encoded = encode_frame_to_base64_jpeg(frame)

    decoded_bytes = base64.b64decode(encoded)
    # JPEG file magic bytes.
    assert decoded_bytes[:3] == b"\xff\xd8\xff"
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `video-analysis/`): `pytest tests/test_frame_encoding.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.frame_encoding'`

- [ ] **Step 3: Write minimal implementation**

```python
# video-analysis/app/frame_encoding.py
from __future__ import annotations

import base64

import cv2
import numpy as np

JPEG_QUALITY = 85


def encode_frame_to_base64_jpeg(frame: np.ndarray) -> str:
    """Encodes `frame` (a BGR image array, as read by OpenCV) as a base64 JPEG string."""
    success, buffer = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
    if not success:
        raise ValueError("Failed to encode frame as JPEG")
    return base64.b64encode(buffer).decode("ascii")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_frame_encoding.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add video-analysis/app/frame_encoding.py video-analysis/tests/test_frame_encoding.py
git commit -m "feat(video-analysis): add pure frame-to-base64-JPEG encoding helper"
```

---

### Task 2: Python - attach bounding box + frame to each vehicle result

**Files:**
- Modify: `video-analysis/app/schemas.py`
- Modify: `video-analysis/app/pipeline.py`
- Test: `video-analysis/tests/test_pipeline.py` (new)

**Interfaces:**
- Consumes: `encode_frame_to_base64_jpeg` from Task 1; `TrackedFrame` (existing, has `.bbox: tuple[float,float,float,float]` and `.frame: np.ndarray`).
- Produces: `VehicleResult.bounding_box: BoundingBox | None`, `VehicleResult.frame_jpeg_base64: str | None` - consumed by the Kotlin server in Task 4/7.

- [ ] **Step 1: Write the failing test**

```python
# video-analysis/tests/test_pipeline.py
from __future__ import annotations

import numpy as np

from app.config import Settings
from app.detection import TrackedFrame
from app.pipeline import AnalysisPipeline


class FakeDetector:
    def __init__(self, frames: list[TrackedFrame]):
        self._frames = frames

    def track_video(self, video_path: str):
        yield from self._frames


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
    )


def test_summarize_track_attaches_bounding_box_and_frame_from_the_largest_bbox_frame():
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(10.0, 10.0, 20.0, 20.0)),  # area 100
        _make_frame(track_id=1, frame_index=1, bbox=(5.0, 5.0, 30.0, 30.0)),   # area 625 - largest
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    results = pipeline.analyze("unused.mp4")

    assert len(results) == 1
    vehicle = results[0]
    assert vehicle.bounding_box is not None
    assert vehicle.bounding_box.x1 == 5.0
    assert vehicle.bounding_box.y1 == 5.0
    assert vehicle.bounding_box.x2 == 30.0
    assert vehicle.bounding_box.y2 == 30.0
    assert vehicle.frame_jpeg_base64 is not None
    assert len(vehicle.frame_jpeg_base64) > 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_pipeline.py -v`
Expected: FAIL with `AttributeError: 'VehicleResult' object has no attribute 'bounding_box'` (or similar - `bounding_box` doesn't exist yet)

- [ ] **Step 3: Write minimal implementation**

Update `video-analysis/app/schemas.py` to the following full contents:

```python
from __future__ import annotations

from pydantic import BaseModel, Field


class BoundingBox(BaseModel):
    x1: float
    y1: float
    x2: float
    y2: float


class VehicleResult(BaseModel):
    track_id: int
    vehicle_type: str
    detection_confidence: float
    # Frame-relative bearing in degrees [0, 360), clockwise from "up" in the frame - null
    # if displacement was too small or the track too brief to trust. This service has no
    # map/compass awareness at all; converting this to a real-world bearing is the Kotlin
    # server's job (see StreetDirectionResolver / ReportAnalysisJob).
    bearing_degrees: float | None = None
    plate_text: str | None = None
    plate_confidence: float | None = None
    # The representative (largest-bounding-box) frame's box, and that same frame encoded
    # as a base64 JPEG - returned for every vehicle since this service can't know in
    # advance which one (if any) the Kotlin server will decide is wrong-way.
    bounding_box: BoundingBox | None = None
    frame_jpeg_base64: str | None = None


class AnalyzeResponse(BaseModel):
    vehicles: list[VehicleResult] = Field(default_factory=list)


class HealthResponse(BaseModel):
    status: str = "ok"
```

Update `video-analysis/app/pipeline.py` to the following full contents:

```python
from __future__ import annotations

from collections import defaultdict
from typing import TYPE_CHECKING

from app.config import Settings
from app.frame_encoding import encode_frame_to_base64_jpeg
from app.schemas import BoundingBox, VehicleResult
from app.tracking_bearing import compute_bearing_degrees

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

    def analyze(self, video_path: str) -> list[VehicleResult]:
        tracks: dict[int, list["TrackedFrame"]] = defaultdict(list)
        for tracked_frame in self._detector.track_video(video_path):
            tracks[tracked_frame.track_id].append(tracked_frame)

        return [self._summarize_track(track_id, frames) for track_id, frames in tracks.items()]

    def _summarize_track(self, track_id: int, frames: list["TrackedFrame"]) -> VehicleResult:
        frames_sorted = sorted(frames, key=lambda f: f.frame_index)
        centroids = [f.centroid for f in frames_sorted]
        bearing = compute_bearing_degrees(centroids)

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
```

(Note: `_bbox_area` was previously a closure nested inside `_read_best_plate`; it's now a module-level function shared with `_summarize_track`'s new representative-frame selection - same calculation, one definition.)

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_pipeline.py tests/test_frame_encoding.py -v`
Expected: PASS (both files)

- [ ] **Step 5: Run the full existing test suite to check nothing else broke**

Run: `pytest -v`
Expected: PASS (all tests, including pre-existing `test_bearing.py`/`test_api_health.py`)

- [ ] **Step 6: Commit**

```bash
git add video-analysis/app/schemas.py video-analysis/app/pipeline.py video-analysis/tests/test_pipeline.py
git commit -m "feat(video-analysis): attach bounding box + representative frame to each vehicle"
```

---

### Task 3: Server - migration + `Report` entity columns

**Files:**
- Create: `server/src/main/resources/db/migration/V5__add_wrong_way_frame_and_confidence.sql`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`

**Interfaces:**
- Produces: `Report.wrongWayFramePath: String?`, `Report.wrongWayConfidence: BigDecimal?` - consumed by Task 7 (write) and Task 8 (read, for the DTO).

- [ ] **Step 1: Write the migration**

```sql
-- server/src/main/resources/db/migration/V5__add_wrong_way_frame_and_confidence.sql
ALTER TABLE reports ADD COLUMN wrong_way_frame_path VARCHAR(255);
ALTER TABLE reports ADD COLUMN wrong_way_confidence NUMERIC(5,4);
```

- [ ] **Step 2: Add the two new columns to the `Report` entity**

In `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`, add these two properties to the constructor, directly after the existing `streetName` property (before `createdAt`):

```kotlin
    // Path to the annotated (red-boxed) frame image of the flagged wrong-way vehicle, in
    // the same storage convention as videoPath - see WrongWayFrameStorageService. Null
    // means "no frame available" (report predates this feature, or annotation/storage
    // failed even though a wrong-way vehicle was found) - the app treats both the same.
    @Column(name = "wrong_way_frame_path")
    var wrongWayFramePath: String? = null,

    // How confident the analysis is that the flagged vehicle was genuinely moving the
    // wrong way (0.0-1.0) - separate from `confidence`, which is the license-plate OCR
    // confidence. See ReportAnalysisJob for the formula.
    @Column(name = "wrong_way_confidence")
    var wrongWayConfidence: BigDecimal? = null,
```

- [ ] **Step 3: Run the server's existing test suite to confirm the migration applies cleanly**

Run (from `server/`): `./gradlew test`
Expected: PASS - Flyway runs `V5` against the test H2 database with no errors, no existing test asserts on the full column set of `reports` in a way this would break.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/resources/db/migration/V5__add_wrong_way_frame_and_confidence.sql server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt
git commit -m "feat(server): add wrong_way_frame_path and wrong_way_confidence columns"
```

---

### Task 4: Server - extend the video-analysis DTOs with bounding box + frame

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt`

**Interfaces:**
- Produces: `BoundingBox(x1, y1, x2, y2: Double)`, `VehicleAnalysisResult.boundingBox: BoundingBox?`, `VehicleAnalysisResult.frameJpegBase64: String?` - consumed by Task 5 (annotation) and Task 7 (job orchestration).

- [ ] **Step 1: Update the DTO file to its full new contents**

```kotlin
package com.trafficwatch.server.videoanalysis.dto

/**
 * Wire response shape from the Python video-analysis service's `POST /v1/analyze` - snake_case
 * JSON, matching the server's global Jackson SNAKE_CASE naming strategy (`application.yml`)
 * with zero extra config needed here, unlike the OSM DTOs which need their own ObjectMapper.
 */
data class VideoAnalysisResponse(
    val vehicles: List<VehicleAnalysisResult> = emptyList(),
)

data class BoundingBox(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
)

data class VehicleAnalysisResult(
    val trackId: Long,
    val vehicleType: String,
    val detectionConfidence: Double,
    val bearingDegrees: Double?,
    val plateText: String?,
    val plateConfidence: Double?,
    val boundingBox: BoundingBox? = null,
    val frameJpegBase64: String? = null,
)
```

- [ ] **Step 2: Run the server's existing test suite**

Run: `./gradlew test`
Expected: PASS - `boundingBox`/`frameJpegBase64` have defaults, so every existing `VehicleAnalysisResult(...)` construction site (test helpers) still compiles unchanged.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt
git commit -m "feat(server): add BoundingBox and frame data to VehicleAnalysisResult"
```

---

### Task 5: Server - frame annotator (draws the red box)

**Files:**
- Create: `server/src/main/kotlin/com/trafficwatch/server/reports/FrameAnnotator.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/FrameAnnotatorTest.kt`

**Interfaces:**
- Consumes: `com.trafficwatch.server.videoanalysis.dto.BoundingBox` (Task 4).
- Produces: `FrameAnnotator.annotate(jpegBytes: ByteArray, boundingBox: BoundingBox): ByteArray` - consumed by Task 7.

- [ ] **Step 1: Write the failing test**

```kotlin
// server/src/test/kotlin/com/trafficwatch/server/reports/FrameAnnotatorTest.kt
package com.trafficwatch.server.reports

import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class FrameAnnotatorTest {

    private fun whiteJpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    @Test
    fun `annotate draws a red border at the bounding box location`() {
        val original = whiteJpeg(100, 100)
        val boundingBox = BoundingBox(x1 = 20.0, y1 = 20.0, x2 = 60.0, y2 = 60.0)

        val annotated = FrameAnnotator.annotate(original, boundingBox)

        val annotatedImage = ImageIO.read(ByteArrayInputStream(annotated))
        assertThat(annotatedImage.width).isEqualTo(100)
        assertThat(annotatedImage.height).isEqualTo(100)

        // The top-left corner of the box should now be predominantly red, not white.
        val borderPixel = annotatedImage.getRGB(20, 20)
        val red = (borderPixel shr 16) and 0xFF
        val green = (borderPixel shr 8) and 0xFF
        val blue = borderPixel and 0xFF
        assertThat(red).isGreaterThan(150)
        assertThat(green).isLessThan(100)
        assertThat(blue).isLessThan(100)
    }

    @Test
    fun `annotate leaves a pixel far from the box unchanged (still white)`() {
        val original = whiteJpeg(100, 100)
        val boundingBox = BoundingBox(x1 = 20.0, y1 = 20.0, x2 = 60.0, y2 = 60.0)

        val annotated = FrameAnnotator.annotate(original, boundingBox)

        val annotatedImage = ImageIO.read(ByteArrayInputStream(annotated))
        val farPixel = annotatedImage.getRGB(5, 5)
        val red = (farPixel shr 16) and 0xFF
        val green = (farPixel shr 8) and 0xFF
        val blue = farPixel and 0xFF
        assertThat(red).isGreaterThan(200)
        assertThat(green).isGreaterThan(200)
        assertThat(blue).isGreaterThan(200)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.trafficwatch.server.reports.FrameAnnotatorTest"`
Expected: FAIL - compile error, `FrameAnnotator` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/reports/FrameAnnotator.kt
package com.trafficwatch.server.reports

import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import java.awt.BasicStroke
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Draws a red rectangle onto a JPEG frame at the given bounding box, using plain Java AWT -
 * no new dependency needed. Used by [ReportAnalysisJob] to highlight the flagged wrong-way
 * vehicle in its representative frame before it's stored.
 */
object FrameAnnotator {
    private const val STROKE_WIDTH = 4f

    /** [boundingBox] is in the same pixel space as the frame [jpegBytes] decodes to. */
    fun annotate(jpegBytes: ByteArray, boundingBox: BoundingBox): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(jpegBytes))
            ?: throw IllegalArgumentException("Could not decode frame JPEG bytes")

        val graphics = image.createGraphics()
        try {
            graphics.color = Color.RED
            graphics.stroke = BasicStroke(STROKE_WIDTH)
            val x = boundingBox.x1.toInt()
            val y = boundingBox.y1.toInt()
            val width = (boundingBox.x2 - boundingBox.x1).toInt()
            val height = (boundingBox.y2 - boundingBox.y1).toInt()
            graphics.drawRect(x, y, width, height)
        } finally {
            graphics.dispose()
        }

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.trafficwatch.server.reports.FrameAnnotatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/FrameAnnotator.kt server/src/test/kotlin/com/trafficwatch/server/reports/FrameAnnotatorTest.kt
git commit -m "feat(server): add FrameAnnotator to draw the wrong-way vehicle's red box"
```

---

### Task 6: Server - wrong-way frame storage service

**Files:**
- Create: `server/src/main/kotlin/com/trafficwatch/server/storage/WrongWayFrameStorageService.kt`
- Create: `server/src/main/kotlin/com/trafficwatch/server/storage/LocalDiskWrongWayFrameStorageService.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/storage/LocalDiskWrongWayFrameStorageServiceTest.kt`

**Interfaces:**
- Produces: `WrongWayFrameStorageService.store(reportId: UUID, jpegBytes: ByteArray): String`, `.resolve(path: String): Path`, `.delete(path: String)` - `store`/`resolve` consumed by Task 7 and Task 8 respectively.

- [ ] **Step 1: Write the failing test**

```kotlin
// server/src/test/kotlin/com/trafficwatch/server/storage/LocalDiskWrongWayFrameStorageServiceTest.kt
package com.trafficwatch.server.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalDiskWrongWayFrameStorageServiceTest {

    @Test
    fun `store writes jpegBytes to reportId-jpg under videoDirectory-frames and resolve finds it again`(
        @TempDir tempDir: Path,
    ) {
        val storageProperties = StorageProperties(videoDirectory = tempDir.resolve("videos").toString())
        val service = LocalDiskWrongWayFrameStorageService(storageProperties)
        service.init()

        val reportId = UUID.randomUUID()
        val jpegBytes = byteArrayOf(1, 2, 3, 4)

        val storedPath = service.store(reportId, jpegBytes)

        assertThat(storedPath).isEqualTo("$reportId.jpg")
        val resolved = service.resolve(storedPath)
        assertThat(resolved).isEqualTo(tempDir.resolve("videos").resolve("frames").resolve("$reportId.jpg"))
        assertThat(Files.readAllBytes(resolved)).isEqualTo(jpegBytes)
    }

    @Test
    fun `delete removes a stored frame and is a no-op if it does not exist`(@TempDir tempDir: Path) {
        val storageProperties = StorageProperties(videoDirectory = tempDir.resolve("videos").toString())
        val service = LocalDiskWrongWayFrameStorageService(storageProperties)
        service.init()

        val reportId = UUID.randomUUID()
        val storedPath = service.store(reportId, byteArrayOf(1))
        assertThat(Files.exists(service.resolve(storedPath))).isTrue()

        service.delete(storedPath)
        assertThat(Files.exists(service.resolve(storedPath))).isFalse()

        // Deleting again must not throw.
        service.delete(storedPath)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.trafficwatch.server.storage.LocalDiskWrongWayFrameStorageServiceTest"`
Expected: FAIL - compile error, the classes don't exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/storage/WrongWayFrameStorageService.kt
package com.trafficwatch.server.storage

import java.nio.file.Path
import java.util.UUID

interface WrongWayFrameStorageService {
    fun store(reportId: UUID, jpegBytes: ByteArray): String
    fun resolve(path: String): Path
    fun delete(path: String)
}
```

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/storage/LocalDiskWrongWayFrameStorageService.kt
package com.trafficwatch.server.storage

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Writes annotated wrong-way-vehicle frame images to a `frames/` subdirectory of the
 * configured video directory, one file per report named `<reportId>.jpg`. Mirrors
 * [LocalDiskVideoStorageService]'s exact pattern for a different media type.
 */
@Service
class LocalDiskWrongWayFrameStorageService(
    storageProperties: StorageProperties,
) : WrongWayFrameStorageService {

    private val frameDirectory: Path = Path.of(storageProperties.videoDirectory).resolve("frames")

    @PostConstruct
    fun init() {
        Files.createDirectories(frameDirectory)
    }

    override fun store(reportId: UUID, jpegBytes: ByteArray): String {
        val filename = "$reportId.jpg"
        Files.write(frameDirectory.resolve(filename), jpegBytes)
        return filename
    }

    override fun resolve(path: String): Path = frameDirectory.resolve(path)

    override fun delete(path: String) {
        Files.deleteIfExists(resolve(path))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.trafficwatch.server.storage.LocalDiskWrongWayFrameStorageServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/storage/WrongWayFrameStorageService.kt server/src/main/kotlin/com/trafficwatch/server/storage/LocalDiskWrongWayFrameStorageService.kt server/src/test/kotlin/com/trafficwatch/server/storage/LocalDiskWrongWayFrameStorageServiceTest.kt
git commit -m "feat(server): add WrongWayFrameStorageService for annotated frame images"
```

---

### Task 7: Server - `ReportAnalysisJob` computes wrong-way confidence and stores the annotated frame

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`
- Modify: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`

**Interfaces:**
- Consumes: `FrameAnnotator.annotate` (Task 5), `WrongWayFrameStorageService.store` (Task 6), `BoundingBox`/`VehicleAnalysisResult.frameJpegBase64` (Task 4).
- Produces: `Report.wrongWayConfidence`/`Report.wrongWayFramePath` now populated - consumed by Task 8.

- [ ] **Step 1: Update `ReportAnalysisJobTest.kt`'s fixtures for the new constructor param and vehicle fields**

Replace the class's field declarations and `job`/`vehicle()` definitions (near the top of the file) with:

```kotlin
    private val reportRepository = mockk<ReportRepository>()
    private val analysisProperties = AnalysisProperties(wrongWayToleranceDegrees = 60.0)
    private val streetDirectionResolver = mockk<StreetDirectionResolver>()
    private val videoAnalysisClient = mockk<VideoAnalysisClient>()
    private val videoStorageService = mockk<VideoStorageService>()
    private val wrongWayFrameStorageService = mockk<WrongWayFrameStorageService>()

    private val job = ReportAnalysisJob(
        reportRepository,
        analysisProperties,
        streetDirectionResolver,
        videoAnalysisClient,
        videoStorageService,
        wrongWayFrameStorageService,
    )
```

and add these two new imports at the top of the file:

```kotlin
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.Base64
```

and update the `vehicle()` helper to:

```kotlin
    private fun vehicle(
        trackId: Long = 1,
        bearingDegrees: Double? = 90.0,
        plateText: String? = "LEA-1234",
        plateConfidence: Double? = 0.9,
        boundingBox: BoundingBox? = null,
        frameJpegBase64: String? = null,
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = 0.8,
        bearingDegrees = bearingDegrees,
        plateText = plateText,
        plateConfidence = plateConfidence,
        boundingBox = boundingBox,
        frameJpegBase64 = frameJpegBase64,
    )
```

- [ ] **Step 2: Add the failing tests for the new behavior**

Add these test methods to the class:

```kotlin
    @Test
    fun `applyOutcome computes wrong-way confidence from detection confidence and bearing match tightness`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        // Illegal bearing is 180 (legal 0 + 180); a vehicle at exactly 180 has
        // angularDistance 0 -> bearingMatchScore 1.0 -> confidence == detectionConfidence (0.8).
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, plateConfidence = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.wrongWayConfidence).isEqualByComparingTo(BigDecimal("0.8"))
    }

    @Test
    fun `applyOutcome gives a borderline wrong-way vehicle a lower confidence than a dead-on one`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        // 150 is 30 degrees off the illegal bearing of 180 - within the 60-degree
        // tolerance, but not dead-on, so its confidence must be lower than 0.8.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 150.0, plateConfidence = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.wrongWayConfidence).isLessThan(BigDecimal("0.8"))
    }

    @Test
    fun `applyOutcome stores an annotated frame and records its path for a wrong-way vehicle`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        val boundingBox = BoundingBox(x1 = 10.0, y1 = 10.0, x2 = 50.0, y2 = 50.0)
        val fakeJpegBytes = byteArrayOf(1, 2, 3)
        val fakeFrameBase64 = Base64.getEncoder().encodeToString(fakeJpegBytes)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, boundingBox = boundingBox, frameJpegBase64 = fakeFrameBase64))
        every { reportRepository.save(any()) } answers { firstArg() }

        mockkObject(FrameAnnotator)
        try {
            every { FrameAnnotator.annotate(fakeJpegBytes, boundingBox) } returns byteArrayOf(9, 9, 9)
            every { wrongWayFrameStorageService.store(any(), byteArrayOf(9, 9, 9)) } returns "stored-frame.jpg"

            job.applyOutcome(report)

            assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
            assertThat(report.wrongWayFramePath).isEqualTo("stored-frame.jpg")
        } finally {
            unmockkObject(FrameAnnotator)
        }
    }

    @Test
    fun `applyOutcome still confirms but leaves wrongWayFramePath null when frame storage fails`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        val boundingBox = BoundingBox(x1 = 10.0, y1 = 10.0, x2 = 50.0, y2 = 50.0)
        val fakeFrameBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, boundingBox = boundingBox, frameJpegBase64 = fakeFrameBase64))
        every { reportRepository.save(any()) } answers { firstArg() }
        every { wrongWayFrameStorageService.store(any(), any()) } throws RuntimeException("disk full")

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.wrongWayFramePath).isNull()
    }

    @Test
    fun `applyOutcome leaves wrongWayFramePath null when the vehicle has no frame data`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, boundingBox = null, frameJpegBase64 = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.wrongWayFramePath).isNull()
        verify(exactly = 0) { wrongWayFrameStorageService.store(any(), any()) }
    }
```

- [ ] **Step 3: Run tests to verify the new ones fail**

Run: `./gradlew test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: FAIL - compile error (constructor arity mismatch, `report.wrongWayConfidence`/`wrongWayFramePath` don't exist as settable outcomes yet) or assertion failures once it compiles.

- [ ] **Step 4: Rewrite `ReportAnalysisJob.kt` to its full new contents**

```kotlin
package com.trafficwatch.server.reports

import com.trafficwatch.server.geo.BearingMath
import com.trafficwatch.server.geo.DirectionResolution
import com.trafficwatch.server.geo.StreetDirectionResolver
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import com.trafficwatch.server.videoanalysis.VideoAnalysisClient
import com.trafficwatch.server.videoanalysis.VideoAnalysisException
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Real analysis pipeline: identifies the street a report's video was taken on and its legal
 * traffic direction (via [StreetDirectionResolver]), detects vehicles and their direction of
 * movement (via [VideoAnalysisClient]), and flips the report to `CONFIRMED` only for a
 * genuine wrong-way detection - `REJECTED` (with a specific [AnalysisOutcome.message]) for
 * every "insufficient data" case. On a genuine detection, also computes a wrong-way
 * confidence score and stores an annotated (red-boxed) frame of the flagged vehicle.
 */
@Component
class ReportAnalysisJob(
    private val reportRepository: ReportRepository,
    private val analysisProperties: AnalysisProperties,
    private val streetDirectionResolver: StreetDirectionResolver,
    private val videoAnalysisClient: VideoAnalysisClient,
    private val videoStorageService: VideoStorageService,
    private val wrongWayFrameStorageService: WrongWayFrameStorageService,
) {
    private val logger = LoggerFactory.getLogger(ReportAnalysisJob::class.java)

    /**
     * Fire-and-forget entry point invoked by [ReportService.submit] after its transaction
     * commits (see [ReportService]'s `afterCommit` registration). Runs on
     * [com.trafficwatch.server.config.AsyncConfig.analysisExecutor], never on the calling
     * (HTTP request) thread.
     */
    @Async("analysisExecutor")
    fun analyze(reportId: UUID) {
        val report = reportRepository.findById(reportId).orElse(null)
        if (report == null) {
            logger.warn("ReportAnalysisJob: report {} no longer exists, skipping analysis", reportId)
            return
        }
        applyOutcome(report)
    }

    /**
     * The actual decision + persistence logic, split out from [analyze] so it can be
     * exercised deterministically in tests against a plain [Report] instance, with
     * [streetDirectionResolver] and [videoAnalysisClient] mocked - no real HTTP calls or
     * randomness involved.
     *
     * `internal` (not `private`) so `ReportAnalysisJobTest`, in the same Gradle module's
     * test source set, can call it directly.
     */
    internal fun applyOutcome(report: Report) {
        val outcome = determineOutcome(report)

        report.status = outcome.status
        report.licensePlate = outcome.licensePlate
        report.confidence = outcome.confidence
        report.analysisMessage = outcome.message
        report.streetName = outcome.streetName
        report.wrongWayConfidence = outcome.wrongWayConfidence
        report.wrongWayFramePath = outcome.wrongWayFramePath
        report.updatedAt = OffsetDateTime.now()

        reportRepository.save(report)
    }

    private fun determineOutcome(report: Report): AnalysisOutcome {
        val compassHeadingDegrees = report.compassHeadingDegrees
            ?: return AnalysisOutcome.rejected("Device compass heading unavailable for this report")

        val resolution = streetDirectionResolver.resolve(report.latitude, report.longitude)
        val (legalBearingDegrees, streetName) = when (resolution) {
            is DirectionResolution.NotFound ->
                return AnalysisOutcome.rejected("Could not identify a street at this location")
            is DirectionResolution.Unknown ->
                return AnalysisOutcome.rejected(
                    "Legal traffic direction unknown for this street",
                    resolution.streetName,
                )
            is DirectionResolution.TwoWay ->
                return AnalysisOutcome.rejected(
                    "Street is two-way; no wrong-way violation is possible here",
                    resolution.streetName,
                )
            is DirectionResolution.LookupFailed ->
                return AnalysisOutcome.rejected("Street lookup temporarily failed: ${resolution.reason}")
            is DirectionResolution.OneWay -> resolution.legalBearingDegrees to resolution.streetName
        }

        val vehicles = try {
            videoAnalysisClient.analyze(
                videoStorageService.resolve(report.videoPath),
                requireNotNull(report.id) { "Report must have a generated id before analysis" },
            )
        } catch (ex: VideoAnalysisException) {
            return AnalysisOutcome.rejected("Video analysis service unavailable: ${ex.message}", streetName)
        }

        val candidate = findBestWrongWayVehicle(vehicles, compassHeadingDegrees.toDouble(), legalBearingDegrees)

        return if (candidate == null) {
            AnalysisOutcome.rejected("No vehicles detected moving against the legal direction", streetName)
        } else {
            val bearingMatchScore =
                1.0 - (candidate.angularDistanceDegrees / analysisProperties.wrongWayToleranceDegrees)
            val wrongWayConfidence = candidate.vehicle.detectionConfidence * bearingMatchScore

            AnalysisOutcome(
                status = ReportStatus.CONFIRMED,
                licensePlate = candidate.vehicle.plateText,
                confidence = candidate.vehicle.plateConfidence?.let { BigDecimal.valueOf(it) },
                message = "Wrong-way vehicle detected on ${streetName ?: "this street"}",
                streetName = streetName,
                wrongWayConfidence = BigDecimal.valueOf(wrongWayConfidence),
                wrongWayFramePath = annotateAndStoreFrame(
                    candidate.vehicle,
                    requireNotNull(report.id) { "Report must have a generated id before analysis" },
                ),
            )
        }
    }

    /**
     * Draws a red box around the flagged vehicle in its representative frame and stores it,
     * for the report detail screen's "flagged vehicle" image. Never throws - a failure here
     * (missing frame data, a decode/encode error, a disk write failure) is logged and simply
     * leaves the report with no frame image, exactly like an old report predating this
     * feature; it must never block the CONFIRMED status the wrong-way detection itself
     * already earned.
     */
    private fun annotateAndStoreFrame(vehicle: VehicleAnalysisResult, reportId: UUID): String? {
        val boundingBox = vehicle.boundingBox ?: return null
        val frameJpegBase64 = vehicle.frameJpegBase64 ?: return null

        return try {
            val jpegBytes = Base64.getDecoder().decode(frameJpegBase64)
            val annotatedJpegBytes = FrameAnnotator.annotate(jpegBytes, boundingBox)
            wrongWayFrameStorageService.store(reportId, annotatedJpegBytes)
        } catch (ex: Exception) {
            logger.warn("ReportAnalysisJob: failed to annotate/store wrong-way frame for report {}", reportId, ex)
            null
        }
    }

    /**
     * Among [vehicles] whose absolute (compass-corrected) bearing falls within
     * [AnalysisProperties.wrongWayToleranceDegrees] of the illegal (opposite-of-legal)
     * direction, returns the one with the highest plate-read confidence (vehicles with no
     * plate read rank lowest, not excluded outright - a wrong-way detection with no
     * readable plate is still a real detection), paired with its angular distance from the
     * illegal bearing (used to compute the wrong-way confidence score).
     */
    private fun findBestWrongWayVehicle(
        vehicles: List<VehicleAnalysisResult>,
        compassHeadingDegrees: Double,
        legalBearingDegrees: Double,
    ): WrongWayCandidate? {
        val illegalBearingDegrees = (legalBearingDegrees + 180.0) % 360.0

        return vehicles
            .mapNotNull { vehicle ->
                val frameBearing = vehicle.bearingDegrees ?: return@mapNotNull null
                val absoluteBearing = (compassHeadingDegrees + frameBearing) % 360.0
                val angularDistance = BearingMath.angularDifferenceDegrees(absoluteBearing, illegalBearingDegrees)
                if (angularDistance <= analysisProperties.wrongWayToleranceDegrees) {
                    WrongWayCandidate(vehicle, angularDistance)
                } else {
                    null
                }
            }
            .maxByOrNull { it.vehicle.plateConfidence ?: -1.0 }
    }
}

internal data class WrongWayCandidate(
    val vehicle: VehicleAnalysisResult,
    val angularDistanceDegrees: Double,
)

internal data class AnalysisOutcome(
    val status: ReportStatus,
    val licensePlate: String?,
    val confidence: BigDecimal?,
    val message: String,
    val streetName: String?,
    val wrongWayConfidence: BigDecimal? = null,
    val wrongWayFramePath: String? = null,
) {
    companion object {
        fun rejected(message: String, streetName: String? = null) = AnalysisOutcome(
            status = ReportStatus.REJECTED,
            licensePlate = null,
            confidence = null,
            message = message,
            streetName = streetName,
        )
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: PASS (all tests, old and new)

- [ ] **Step 6: Run the full server test suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "feat(server): compute wrong-way confidence and store the annotated frame"
```

---

### Task 8: Server - expose the new fields via the API + a way to look up the stored frame path

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/dto/ReportDtos.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`
- Modify: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportControllerTest.kt` (fixture updates only - the new endpoint's own tests are Task 9)

**Interfaces:**
- Produces: `ReportStatusResponse.hasWrongWayFrame: Boolean`, `.wrongWayConfidence: BigDecimal?`; `ReportService.getWrongWayFramePath(reportId: UUID, currentUserId: UUID): Path` (throws `ReportNotFoundException` if not found/not owned/no frame) - consumed by Task 9's controller endpoint.

- [ ] **Step 1: Add the two new fields to `ReportStatusResponse`**

In `server/src/main/kotlin/com/trafficwatch/server/reports/dto/ReportDtos.kt`, update `ReportStatusResponse` to:

```kotlin
data class ReportStatusResponse(
    val reportId: UUID,
    val status: ReportStatus,
    val licensePlate: String?,
    val confidence: BigDecimal?,
    val message: String?,
    val updatedAt: OffsetDateTime,
    val streetName: String?,
    val hasWrongWayFrame: Boolean,
    val wrongWayConfidence: BigDecimal?,
)
```

- [ ] **Step 2: Update `ReportControllerTest.kt`'s two hand-constructed `ReportStatusResponse` fixtures**

In the `getReportStatus with a valid token...` test, change the `every { ... } returns ReportStatusResponse(...)` call to include the two new args:

```kotlin
        every { reportService.getStatus(reportId, any()) } returns ReportStatusResponse(
            reportId = reportId,
            status = ReportStatus.CONFIRMED,
            licensePlate = "LEA-1234",
            confidence = BigDecimal("0.95"),
            message = "Plate matched",
            updatedAt = updatedAt,
            streetName = null,
            hasWrongWayFrame = true,
            wrongWayConfidence = BigDecimal("0.87"),
        )
```

In the `getReportStatus passes the userId embedded in the bearer token...` test, similarly update its `ReportStatusResponse(...)`:

```kotlin
        every { reportService.getStatus(reportId, capture(userIdSlot)) } returns ReportStatusResponse(
            reportId = reportId,
            status = ReportStatus.PENDING,
            licensePlate = null,
            confidence = null,
            message = null,
            updatedAt = OffsetDateTime.now(),
            streetName = null,
            hasWrongWayFrame = false,
            wrongWayConfidence = null,
        )
```

- [ ] **Step 3: Run the controller test to verify it fails only on the missing service method (not on the DTO changes)**

Run: `./gradlew test --tests "com.trafficwatch.server.reports.ReportControllerTest"`
Expected: FAIL to compile - `ReportService.toStatusResponse()` (private, in `ReportService.kt`) doesn't pass the two new required constructor args yet.

- [ ] **Step 4: Update `ReportService.kt`'s `toStatusResponse()` extension and add `getWrongWayFramePath`**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`:

Add these two new imports at the top (`ReportService.kt` does not currently import `java.nio.file.Path` - the new method's return type needs it):
```kotlin
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import java.nio.file.Path
```

Add `WrongWayFrameStorageService` as a new constructor parameter:
```kotlin
@Service
class ReportService(
    private val reportRepository: ReportRepository,
    private val videoStorageService: VideoStorageService,
    private val reportAnalysisJob: ReportAnalysisJob,
    private val wrongWayFrameStorageService: WrongWayFrameStorageService,
) {
```

Add this new method (near `getStatus`):
```kotlin
    /**
     * Backs `GET /reports/{reportId}/wrong-way-frame`. Same per-user scoping as [getStatus]
     * - and the same [ReportNotFoundException] (mapped to a 404) when the report has no
     * stored frame at all (old report predating this feature, or annotation/storage failed)
     * - the caller has no way to distinguish "wrong owner", "doesn't exist", and "no frame
     * yet", by design.
     */
    fun getWrongWayFramePath(reportId: UUID, currentUserId: UUID): Path {
        val report = reportRepository.findByIdAndUserId(reportId, currentUserId)
            ?: throw ReportNotFoundException(reportId)
        val framePath = report.wrongWayFramePath ?: throw ReportNotFoundException(reportId)
        return wrongWayFrameStorageService.resolve(framePath)
    }
```

Update `toStatusResponse()`:
```kotlin
    private fun Report.toStatusResponse(): ReportStatusResponse {
        val reportId = requireNotNull(id) { "Report must have a generated id" }
        return ReportStatusResponse(
            reportId = reportId,
            status = status,
            licensePlate = licensePlate,
            confidence = confidence,
            message = analysisMessage,
            updatedAt = updatedAt,
            streetName = streetName,
            hasWrongWayFrame = wrongWayFramePath != null,
            wrongWayConfidence = wrongWayConfidence,
        )
    }
```

- [ ] **Step 5: Update `ReportServiceTest.kt`'s direct construction of `ReportService`**

`ReportServiceTest.kt` constructs a real `ReportService` directly (not through Spring), so it needs the new constructor argument too. Add this import:
```kotlin
import com.trafficwatch.server.storage.WrongWayFrameStorageService
```

Change:
```kotlin
    private val reportRepository = mockk<ReportRepository>()
    private val videoStorageService = mockk<VideoStorageService>()
    private val reportAnalysisJob = mockk<ReportAnalysisJob>()
    private val reportService = ReportService(reportRepository, videoStorageService, reportAnalysisJob)
```
to:
```kotlin
    private val reportRepository = mockk<ReportRepository>()
    private val videoStorageService = mockk<VideoStorageService>()
    private val reportAnalysisJob = mockk<ReportAnalysisJob>()
    private val wrongWayFrameStorageService = mockk<WrongWayFrameStorageService>()
    private val reportService = ReportService(
        reportRepository, videoStorageService, reportAnalysisJob, wrongWayFrameStorageService,
    )
```

Also update the `getStatus maps a report owned by the requester into a ReportStatusResponse` test - it doesn't assert on `hasWrongWayFrame`/`wrongWayConfidence` today, so it still compiles and passes unchanged, but add these two assertions to it for coverage (the `sampleReport` helper's `Report(...)` call already leaves the new `wrongWayFramePath`/`wrongWayConfidence` fields at their `null` defaults, so both should read as "false"/"null" here):
```kotlin
        assertThat(response.hasWrongWayFrame).isFalse()
        assertThat(response.wrongWayConfidence).isNull()
```

- [ ] **Step 6: Run the full server test suite**

Run: `./gradlew test`
Expected: PASS (all tests in `ReportServiceTest`, `ReportControllerTest`, and every other existing test file, green)

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/dto/ReportDtos.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportControllerTest.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportServiceTest.kt
git commit -m "feat(server): expose hasWrongWayFrame/wrongWayConfidence and a frame-path lookup"
```

---

### Task 9: Server - `GET /reports/{reportId}/wrong-way-frame` endpoint

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt`
- Modify: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportControllerTest.kt`

**Interfaces:**
- Consumes: `ReportService.getWrongWayFramePath` (Task 8).

- [ ] **Step 1: Write the failing tests**

Add these imports to `ReportControllerTest.kt`:
```kotlin
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import java.nio.file.Files
```

Add these test methods to the class:
```kotlin
    @Test
    fun `getWrongWayFrame with a valid token returns 200 with image bytes`() {
        val reportId = UUID.randomUUID()
        val tempFile = Files.createTempFile("wrong-way-frame-test", ".jpg")
        Files.write(tempFile, byteArrayOf(1, 2, 3, 4))
        every { reportService.getWrongWayFramePath(reportId, any()) } returns tempFile

        try {
            mockMvc.perform(
                get("/reports/$reportId/wrong-way-frame").header("Authorization", "Bearer ${bearerToken()}"),
            )
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `getWrongWayFrame for a report with no stored frame returns 404 with ApiError body`() {
        val reportId = UUID.randomUUID()
        every { reportService.getWrongWayFramePath(reportId, any()) } throws ReportNotFoundException(reportId)

        mockMvc.perform(
            get("/reports/$reportId/wrong-way-frame").header("Authorization", "Bearer ${bearerToken()}"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("REPORT_NOT_FOUND"))
    }

    @Test
    fun `getWrongWayFrame without an Authorization header is rejected with 401`() {
        mockMvc.perform(get("/reports/${UUID.randomUUID()}/wrong-way-frame"))
            .andExpect(status().isUnauthorized)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.trafficwatch.server.reports.ReportControllerTest"`
Expected: FAIL - 404 (no such route) instead of the expected statuses, since the endpoint doesn't exist yet.

- [ ] **Step 3: Add the endpoint to `ReportController.kt`**

Add these imports:
```kotlin
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
```

Add this method to the `ReportController` class:
```kotlin
    /**
     * `GET /reports/{reportId}/wrong-way-frame` - same per-user scoping/404 behavior as
     * `GET /reports/{reportId}/status` (see [ReportService.getWrongWayFramePath]). Returns
     * the annotated (red-boxed) frame image as raw JPEG bytes - the first endpoint in this
     * API to serve back binary media.
     */
    @GetMapping("/reports/{reportId}/wrong-way-frame", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun getWrongWayFrame(@PathVariable reportId: UUID): ResponseEntity<Resource> {
        val path = reportService.getWrongWayFramePath(reportId, CurrentUser.id())
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .body(FileSystemResource(path) as Resource)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.trafficwatch.server.reports.ReportControllerTest"`
Expected: PASS

- [ ] **Step 5: Run the full server test suite**

Run: `./gradlew test`
Expected: PASS (75+ tests, all green)

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportControllerTest.kt
git commit -m "feat(server): add GET /reports/{id}/wrong-way-frame endpoint"
```

---

### Task 10: Android - add osmdroid and the `LocationMapView` composable

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/trafficwatch/app/core/ui/components/LocationMapView.kt`

**Interfaces:**
- Produces: `@Composable fun LocationMapView(latitude: Double, longitude: Double, modifier: Modifier = Modifier)` - consumed by Task 14.

- [ ] **Step 1: Add the osmdroid dependency to the version catalog**

In `gradle/libs.versions.toml`, add to the `[versions]` block (after `gson`):
```toml
osmdroid = "6.1.20"
```

Add to the `[libraries]` block (after the `# Coil` section):
```toml
# Maps
osmdroid = { group = "org.osmdroid", name = "osmdroid-android", version.ref = "osmdroid" }
```

- [ ] **Step 2: Add the dependency to the app module**

In `app/build.gradle.kts`, add this line in the `dependencies { ... }` block, after `implementation(libs.coil.compose)`:
```kotlin
    // Maps
    implementation(libs.osmdroid)
```

- [ ] **Step 3: Verify the project syncs and builds**

Run (from repo root): `./gradlew.bat :app:assembleDebug` (Windows) - confirms the new dependency resolves and nothing else breaks.
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Create the map composable**

```kotlin
// app/src/main/java/com/trafficwatch/app/core/ui/components/LocationMapView.kt
package com.trafficwatch.app.core.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private const val DEFAULT_ZOOM = 17.0

/**
 * A small, non-interactive (no pan/zoom) OpenStreetMap view centered on [latitude]/[longitude]
 * with a single pin marker - just enough to make a report's location immediately visible,
 * not a navigable map. Uses osmdroid (see build.gradle.kts) rather than Google Maps - no API
 * key/billing needed.
 */
@Composable
fun LocationMapView(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(150.dp),
        factory = { context ->
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setMultiTouchControls(false)
                val point = GeoPoint(latitude, longitude)
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(point)
                overlays.add(Marker(this).apply { position = point })
            }
        },
    )
}
```

- [ ] **Step 5: Verify the project still builds**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/trafficwatch/app/core/ui/components/LocationMapView.kt
git commit -m "feat(app): add osmdroid and a small non-interactive LocationMapView"
```

---

### Task 11: Android - sync the new fields from the server into local data

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/core/domain/model/Report.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/local/entity/ReportEntity.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/ReportDtos.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/local/dao/ReportDao.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/repository/ReportRepository.kt`

**Interfaces:**
- Produces: `Report.hasWrongWayFrame: Boolean`, `Report.wrongWayConfidence: Float?` - consumed by Task 14.

- [ ] **Step 1: Add the two new fields to the `Report` domain model**

In `app/src/main/java/com/trafficwatch/app/core/domain/model/Report.kt`, add these two properties to the `data class Report`, directly after `analysisMessage`:

```kotlin
    val hasWrongWayFrame: Boolean = false,
    val wrongWayConfidence: Float? = null,
```

- [ ] **Step 2: Add matching columns to `ReportEntity` and update the mapping functions**

Update `app/src/main/java/com/trafficwatch/app/core/data/local/entity/ReportEntity.kt` to its full new contents:

```kotlin
package com.trafficwatch.app.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus

@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val videoPath: String,
    // LocationData flattened
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val bearing: Float,
    val speed: Float,
    val locationCapturedAt: Long,
    // Report metadata
    val recordingStartedAt: Long,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val status: String,            // ReportStatus.name()
    val licensePlate: String?,
    val confidence: Float?,
    val analysisMessage: String?,
    val hasWrongWayFrame: Boolean,
    val wrongWayConfidence: Float?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): Report = Report(
        id = id,
        serverId = serverId,
        videoPath = videoPath,
        location = LocationData(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            capturedAt = locationCapturedAt
        ),
        recordingStartedAt = recordingStartedAt,
        durationMs = durationMs,
        fileSizeBytes = fileSizeBytes,
        status = ReportStatus.valueOf(status),
        licensePlate = licensePlate,
        confidence = confidence,
        analysisMessage = analysisMessage,
        hasWrongWayFrame = hasWrongWayFrame,
        wrongWayConfidence = wrongWayConfidence,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(report: Report): ReportEntity = ReportEntity(
            id = report.id,
            serverId = report.serverId,
            videoPath = report.videoPath,
            latitude = report.location.latitude,
            longitude = report.location.longitude,
            accuracy = report.location.accuracy,
            altitude = report.location.altitude,
            bearing = report.location.bearing,
            speed = report.location.speed,
            locationCapturedAt = report.location.capturedAt,
            recordingStartedAt = report.recordingStartedAt,
            durationMs = report.durationMs,
            fileSizeBytes = report.fileSizeBytes,
            status = report.status.name,
            licensePlate = report.licensePlate,
            confidence = report.confidence,
            analysisMessage = report.analysisMessage,
            hasWrongWayFrame = report.hasWrongWayFrame,
            wrongWayConfidence = report.wrongWayConfidence,
            createdAt = report.createdAt,
            updatedAt = report.updatedAt
        )
    }
}
```

- [ ] **Step 3: Bump the Room database version**

In `app/src/main/java/com/trafficwatch/app/core/data/local/AppDatabase.kt`, change `version = 1` to `version = 2`. (`DatabaseModule.kt` already calls `.fallbackToDestructiveMigration()`, so this just wipes and recreates the local cache table on next launch - no explicit `Migration` needed. The server remains the source of truth; nothing of value is lost.)

- [ ] **Step 4: Add the two new fields to the Android `ReportStatusResponse` DTO**

Update `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/ReportDtos.kt`'s `ReportStatusResponse` to:

```kotlin
data class ReportStatusResponse(
    @SerializedName("report_id") val reportId: String,
    @SerializedName("status") val status: String,
    @SerializedName("license_plate") val licensePlate: String?,
    @SerializedName("confidence") val confidence: Float?,
    @SerializedName("message") val message: String?,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("has_wrong_way_frame") val hasWrongWayFrame: Boolean,
    @SerializedName("wrong_way_confidence") val wrongWayConfidence: Float?
)
```

- [ ] **Step 5: Extend `ReportDao.updateAnalysisResult` to also write the new columns**

Update `app/src/main/java/com/trafficwatch/app/core/data/local/dao/ReportDao.kt`'s query and method to:

```kotlin
    @Query("""
        UPDATE reports
        SET status = :status, licensePlate = :licensePlate, confidence = :confidence,
            analysisMessage = :message, hasWrongWayFrame = :hasWrongWayFrame,
            wrongWayConfidence = :wrongWayConfidence, updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateAnalysisResult(
        id: String,
        status: String,
        licensePlate: String?,
        confidence: Float?,
        message: String?,
        hasWrongWayFrame: Boolean,
        wrongWayConfidence: Float?,
        updatedAt: Long
    )
```

- [ ] **Step 6: Pass the new fields through in `ReportRepository.syncPendingReports()`**

In `app/src/main/java/com/trafficwatch/app/core/data/repository/ReportRepository.kt`, update the `reportDao.updateAnalysisResult(...)` call inside `syncPendingReports()` to:

```kotlin
                reportDao.updateAnalysisResult(
                    id = entity.id,
                    status = status.name,
                    licensePlate = response.licensePlate,
                    confidence = response.confidence,
                    message = response.message,
                    hasWrongWayFrame = response.hasWrongWayFrame,
                    wrongWayConfidence = response.wrongWayConfidence,
                    updatedAt = System.currentTimeMillis()
                )
```

- [ ] **Step 7: Build to verify everything compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/domain/model/Report.kt app/src/main/java/com/trafficwatch/app/core/data/local/entity/ReportEntity.kt app/src/main/java/com/trafficwatch/app/core/data/local/AppDatabase.kt app/src/main/java/com/trafficwatch/app/core/data/remote/dto/ReportDtos.kt app/src/main/java/com/trafficwatch/app/core/data/local/dao/ReportDao.kt app/src/main/java/com/trafficwatch/app/core/data/repository/ReportRepository.kt
git commit -m "feat(app): sync hasWrongWayFrame/wrongWayConfidence from the server"
```

---

### Task 12: Android - authenticated image loading for the wrong-way frame

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/TrafficWatchApplication.kt`

**Interfaces:**
- Produces: every `coil.compose.AsyncImage` in the app now automatically attaches the JWT (via the shared `OkHttpClient`) to its image requests - consumed by Task 14.

- [ ] **Step 1: Update `TrafficWatchApplication.kt` to its full new contents**

```kotlin
package com.trafficwatch.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration as OsmConfiguration
import javax.inject.Inject

@HiltAndroidApp
class TrafficWatchApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // osmdroid requires a user agent to be set before any MapView is created, or
        // OpenStreetMap's tile servers may reject requests.
        OsmConfiguration.getInstance().userAgentValue = packageName
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Every `AsyncImage`/Coil request in the app goes through this loader, built from the
     * same [OkHttpClient] Retrofit uses (see `NetworkModule`) - so requests for the
     * wrong-way-frame image (behind JWT auth, like every other endpoint) automatically
     * carry the same Authorization header `AuthInterceptor` already attaches, with no
     * per-call wiring needed.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .build()
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/TrafficWatchApplication.kt
git commit -m "feat(app): use the authenticated OkHttpClient for Coil's image loading"
```

---

### Task 13: Android - `ReportDetailScreen` additions

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt`

**Interfaces:**
- Consumes: `LocationMapView` (Task 10), `Report.hasWrongWayFrame`/`.wrongWayConfidence` (Task 11), the authenticated Coil `ImageLoader` (Task 12), `BuildConfig.BASE_URL` (existing).

- [ ] **Step 1: Update the file to its full new contents**

```kotlin
package com.trafficwatch.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trafficwatch.app.BuildConfig
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.ui.components.LocationMapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    reportId: String,
    onNavigateBack: () -> Unit,
    getReport: suspend (String) -> Report?
) {
    var report by remember { mutableStateOf<Report?>(null) }

    LaunchedEffect(reportId) {
        report = getReport(reportId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val r = report
        if (r == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status banner
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(r.status.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (r.analysisMessage != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(r.analysisMessage, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (r.licensePlate != null) {
                            Spacer(Modifier.height(8.dp))
                            DetailRow("License Plate", r.licensePlate)
                        }
                        if (r.confidence != null) {
                            DetailRow("Plate Read Confidence", "%.0f%%".format(r.confidence * 100))
                        }
                        if (r.wrongWayConfidence != null) {
                            DetailRow("Wrong-Way Confidence", "${(r.wrongWayConfidence * 100).roundToInt()}%")
                        }
                    }
                }

                // Metadata
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Metadata", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        DetailRow("Recorded At", formatTs(r.recordingStartedAt))
                        HorizontalDivider()
                        DetailRow("Duration", "${r.durationMs / 1000} sec")
                        HorizontalDivider()
                        DetailRow("Latitude", "%.6f°".format(r.location.latitude))
                        HorizontalDivider()
                        DetailRow("Longitude", "%.6f°".format(r.location.longitude))
                        HorizontalDivider()
                        DetailRow("GPS Accuracy", "±%.0f m".format(r.location.accuracy))
                        if (r.serverId != null) {
                            HorizontalDivider()
                            DetailRow("Report ID", r.serverId)
                        }
                    }
                }

                // The map pin and flagged-vehicle image only make sense once a violation has
                // actually been confirmed - PENDING/REJECTED reports keep the layout above
                // unchanged.
                if (r.status == ReportStatus.CONFIRMED) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Location", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            LocationMapView(latitude = r.location.latitude, longitude = r.location.longitude)
                        }
                    }

                    if (r.hasWrongWayFrame && r.serverId != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Flagged Vehicle", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                AsyncImage(
                                    model = "${BuildConfig.BASE_URL}reports/${r.serverId}/wrong-way-frame",
                                    contentDescription = "Flagged vehicle, wrong-way direction highlighted in red",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatTs(epochMs: Long) =
    SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt
git commit -m "feat(app): show location map, flagged vehicle image, and wrong-way confidence"
```

---

### Task 14: Manual end-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Start the full stack**

From `server/`: `docker compose up -d` (Postgres), then `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`.
From `video-analysis/`: `uvicorn app.main:app --reload`.

- [ ] **Step 2: Install the updated debug APK on the connected device and confirm it builds/launches**

From repo root: `./gradlew.bat :app:installDebug`, then launch the app, log in.

- [ ] **Step 3: Submit a report at a known one-way-street coordinate with a real wrong-way vehicle in the clip**

Record and submit via the app's normal camera flow.

- [ ] **Step 4: Poll until CONFIRMED and open the report detail screen**

Use the History screen's pull-to-refresh or wait for the background poll; tap into the report once it shows `CONFIRMED`.

Expected: the map card renders with a pin at the report's coordinates; a "Flagged Vehicle" card shows an image with a visible red box around a vehicle; the Status card shows both "Plate Read Confidence" and "Wrong-Way Confidence" rows.

- [ ] **Step 5: Verify graceful degradation for an old report**

Open a `CONFIRMED` report that existed before this feature shipped (e.g. one of the reports confirmed earlier in this project's testing).

Expected: the map card still renders (lat/long always existed); no "Flagged Vehicle" card appears; no "Wrong-Way Confidence" row appears; nothing crashes or shows a broken-image placeholder.

- [ ] **Step 6: Verify the new endpoint's auth/ownership scoping directly**

```bash
curl -i http://localhost:8080/v1/reports/<some-other-users-report-id>/wrong-way-frame -H "Authorization: Bearer <your-token>"
```
Expected: `404` with the standard `{"error":"REPORT_NOT_FOUND", ...}` body, not another user's image.
