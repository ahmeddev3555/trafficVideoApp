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
