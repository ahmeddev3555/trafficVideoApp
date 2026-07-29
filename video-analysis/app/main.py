from __future__ import annotations

import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile, status

from app.config import Settings, get_settings
from app.schemas import AnalyzeResponse, HealthResponse

if TYPE_CHECKING:
    from app.pipeline import AnalysisPipeline

app = FastAPI(title="TrafficWatch Video Analysis Service")

# Constructed lazily on first use of /v1/analyze (see _get_pipeline) rather than at import
# or startup time, so importing this module - and running /health - never requires
# ultralytics/easyocr/opencv (and their large model weights) to be installed or loaded.
_pipeline: "AnalysisPipeline | None" = None


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse()


def _get_pipeline(settings: Settings) -> "AnalysisPipeline":
    global _pipeline
    if _pipeline is None:
        from app.detection import VehicleDetector
        from app.ocr import PlateReader
        from app.pipeline import AnalysisPipeline

        _pipeline = AnalysisPipeline(settings, VehicleDetector(settings), PlateReader())
    return _pipeline


def _verify_api_key(x_api_key: str = Header(...), settings: Settings = Depends(get_settings)) -> None:
    if x_api_key != settings.api_key:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid API key")


@app.post("/v1/analyze", response_model=AnalyzeResponse, dependencies=[Depends(_verify_api_key)])
async def analyze(
    video: UploadFile = File(...),
    # Echoed/logged only - never used to look anything up, so this service stays stateless.
    report_id: str | None = Form(default=None),
    settings: Settings = Depends(get_settings),
) -> AnalyzeResponse:
    pipeline = _get_pipeline(settings)

    suffix = Path(video.filename or "video.mp4").suffix or ".mp4"
    with tempfile.NamedTemporaryFile(suffix=suffix) as tmp_file:
        tmp_file.write(await video.read())
        tmp_file.flush()
        vehicles = pipeline.analyze(tmp_file.name)

    return AnalyzeResponse(vehicles=vehicles)
