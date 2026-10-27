import io

import pytest
from PIL import Image

from conftest import fake_xray, needs_model

EXPECTED_VERSION = "c63f9782b7d1"


def _post(client, data: bytes, name="x.png", ctype="image/png"):
    return client.post("/predict", files={"file": (name, data, ctype)})


@needs_model
def test_predict_ok(client):
    r = _post(client, fake_xray((512, 400), "JPEG"), "a.jpg", "image/jpeg")
    assert r.status_code == 200
    body = r.json()
    assert 0.0 <= body["probability"] <= 1.0
    assert body["label"] in ("Co", "Khong")
    assert body["label"] == ("Co" if body["probability"] >= body["threshold"] else "Khong")
    assert body["threshold"] == pytest.approx(0.403)
    assert body["experimental"] is True
    assert body["model_name"] == "BioViL-T fine-tune Set A (bnn)"
    assert body["disclaimer"]


@needs_model
def test_health_and_version(client):
    body = client.get("/health").json()
    assert body["model_loaded"] is True
    assert body["model_version"] == EXPECTED_VERSION
    assert _post(client, fake_xray()).json()["model_version"] == EXPECTED_VERSION


def test_not_an_image(client_no_model):
    r = _post(client_no_model, b"hello, not an image", "a.png")
    assert r.status_code == 400 and r.json()["code"] == "INVALID_IMAGE"


@pytest.mark.parametrize("fmt", ["GIF", "BMP"])
def test_unsupported_format(client_no_model, fmt):
    buf = io.BytesIO()
    Image.new("L", (64, 64), 128).save(buf, format=fmt)
    # tên tệp và content-type giả là PNG: định dạng phải xác định theo nội dung
    r = _post(client_no_model, buf.getvalue(), "fake.png", "image/png")
    assert r.status_code == 400 and r.json()["code"] == "UNSUPPORTED_FORMAT"


def test_too_large(client_no_model):  # giới hạn 1 MB trong fixture
    r = _post(client_no_model, b"\0" * (1024 * 1024 + 1))
    assert r.status_code == 400 and r.json()["code"] == "FILE_TOO_LARGE"


def test_missing_file(client_no_model):
    r = client_no_model.post("/predict", data={"other": "x"})
    assert r.status_code == 400 and r.json()["code"] == "INVALID_IMAGE"


def test_model_not_loaded(client_no_model):
    r = _post(client_no_model, fake_xray())
    assert r.status_code == 503 and r.json()["code"] == "MODEL_NOT_LOADED"
    assert client_no_model.get("/health").json()["model_loaded"] is False
