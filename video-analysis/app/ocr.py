from __future__ import annotations

import numpy as np


class PlateReader:
    """Runs EasyOCR directly on a vehicle's bounding-box crop.

    No dedicated plate-detector model exists as a safely-licensable, verified open-source
    dependency, so this relies on EasyOCR's own text-region detector to localize
    plate-like text within the crop, rather than a separate plate-detection stage. Lower
    accuracy ceiling than a purpose-built detector, acceptable for v1 - see the plan.
    """

    def __init__(self, languages: list[str] | None = None):
        # Imported lazily inside __init__ (not at module import time) so importing this
        # module doesn't require easyocr/torch to be installed unless a PlateReader is
        # actually constructed - keeps process startup and /health cheap.
        import easyocr

        self._reader = easyocr.Reader(languages or ["en"], gpu=False)

    def read_plate(self, crop: np.ndarray) -> tuple[str | None, float]:
        """Returns the highest-confidence text read from `crop`, or (None, 0.0)."""
        results = self._reader.readtext(crop)
        if not results:
            return None, 0.0

        _, text, confidence = max(results, key=lambda r: r[2])
        return text, float(confidence)
