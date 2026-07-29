import os

os.environ.setdefault("API_KEY", "test-only-api-key")

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_returns_ok():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_analyze_without_api_key_is_rejected():
    response = client.post("/v1/analyze", files={"video": ("clip.mp4", b"not-a-real-video", "video/mp4")})
    assert response.status_code in (401, 422)


def test_analyze_with_wrong_api_key_is_rejected():
    response = client.post(
        "/v1/analyze",
        headers={"X-API-Key": "wrong-key"},
        files={"video": ("clip.mp4", b"not-a-real-video", "video/mp4")},
    )
    assert response.status_code == 401
