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
