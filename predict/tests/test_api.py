import io
import json

import pytest
from PIL import Image

from conftest import FAKE_RECORD, fake_xray, needs_model

KEYS_NO_IMAGE = {"nhan", "p_lam_sang", "nguong_lam_sang", "bao_nguy_co_lam_sang", "co_anh", "ket_luan_cuoi"}
KEYS_IMAGE = KEYS_NO_IMAGE | {"p_anh", "p_ghep", "nguong_ghep", "bao_nguy_co_ghep"}


def _img(client, rec, data, name="x.png", ctype="image/png"):
    return client.post("/predict/clinical-image", data={"lam_sang": json.dumps(rec)},
                       files={"file": (name, data, ctype)})


@needs_model
def test_health(client):
    b = client.get("/health").json()
    assert b["model_loaded"] is True and b["model_version"] == "68b803152d39"


@needs_model
def test_clinical_ok(client):
    r = client.post("/predict/clinical", json=FAKE_RECORD)
    assert r.status_code == 200, r.text
    b = r.json()
    assert {k for k, v in b.items() if v is not None} == KEYS_NO_IMAGE
    assert b["nhan"] == "ketqua" and b["co_anh"] is False
    assert b["nguong_lam_sang"] == pytest.approx(0.051546)
    assert b["bao_nguy_co_lam_sang"] == (b["p_lam_sang"] >= b["nguong_lam_sang"])
    assert b["ket_luan_cuoi"] == b["bao_nguy_co_lam_sang"]
    assert r.headers["x-model-version"] == "68b803152d39"


@needs_model
def test_clinical_empty_record(client):
    r = client.post("/predict/clinical", json={})
    assert r.status_code == 200 and 0 <= r.json()["p_lam_sang"] <= 1


@needs_model
def test_decimal_comma_equals_dot(client):
    a = client.post("/predict/clinical", json={**FAKE_RECORD, "fvc": "3,2", "can": "60,5"}).json()
    b = client.post("/predict/clinical", json={**FAKE_RECORD, "fvc": 3.2, "can": 60.5}).json()
    assert a["p_lam_sang"] == b["p_lam_sang"]


@needs_model
def test_empty_string_equals_missing(client):
    a = client.post("/predict/clinical", json={"ho": "", "theluc": None}).json()
    b = client.post("/predict/clinical", json={}).json()
    assert a["p_lam_sang"] == b["p_lam_sang"]


@needs_model
@pytest.mark.parametrize("field,value", [("gioitinh", "nam"), ("ho", "Có"), ("theluc", 7),
                                         ("mdkhotho", "Kho tho"), ("tdho", "ban dem")])
def test_invalid_category_422(client, field, value):
    r = client.post("/predict/clinical", json={**FAKE_RECORD, field: value})
    assert r.status_code == 422
    b = r.json()
    assert b["code"] == "INVALID_INPUT"
    err = next(e for e in b["errors"] if e["field"] == field)
    assert err["error"] == "INVALID_CATEGORY" and err["allowed"]


@needs_model
@pytest.mark.parametrize("body,err", [({"cao": "abc"}, "NOT_A_NUMBER"), ({"tinh": "Ha Noi"}, "UNKNOWN_FIELD"),
                                      ({"ho": True}, "INVALID_TYPE"), ({"benhhh": "x" * 501}, "TOO_LONG")])
def test_other_422(client, body, err):
    r = client.post("/predict/clinical", json=body)
    assert r.status_code == 422 and r.json()["errors"][0]["error"] == err


@needs_model
@pytest.mark.parametrize("raw", [b"not json", b"[1,2]"])
def test_bad_json_422(client, raw):
    r = client.post("/predict/clinical", content=raw, headers={"content-type": "application/json"})
    assert r.status_code == 422 and r.json()["code"] == "INVALID_INPUT"


@needs_model
def test_clinical_image_fake(client):
    r = _img(client, FAKE_RECORD, fake_xray((512, 400), "JPEG"), "a.jpg", "image/jpeg")
    assert r.status_code == 200, r.text
    b = r.json()
    assert set(b) == KEYS_IMAGE and b["co_anh"] is True
    assert b["nguong_ghep"] == pytest.approx(0.207134)
    assert b["ket_luan_cuoi"] == b["bao_nguy_co_ghep"] == (b["p_ghep"] >= b["nguong_ghep"])


@needs_model
def test_clinical_image_invalid_category(client):
    r = _img(client, {"ho": "maybe"}, fake_xray())
    assert r.status_code == 422


@needs_model
def test_not_an_image(client):
    r = _img(client, {}, b"hello, not an image")
    assert r.status_code == 400 and r.json()["code"] == "INVALID_IMAGE"


@needs_model
@pytest.mark.parametrize("fmt", ["GIF", "BMP"])
def test_unsupported_format(client, fmt):
    buf = io.BytesIO()
    Image.new("L", (64, 64), 128).save(buf, format=fmt)
    r = _img(client, {}, buf.getvalue(), "fake.png")
    assert r.status_code == 400 and r.json()["code"] == "UNSUPPORTED_FORMAT"


@needs_model
def test_too_large(client):  # giới hạn 1 MB trong fixture
    r = _img(client, {}, b"\0" * (1024 * 1024 + 1))
    assert r.status_code == 400 and r.json()["code"] == "FILE_TOO_LARGE"


@needs_model
def test_missing_file(client):
    r = client.post("/predict/clinical-image", data={"lam_sang": "{}"})
    assert r.status_code == 400 and r.json()["code"] == "INVALID_IMAGE"


@needs_model
def test_old_route_removed(client):
    r = client.post("/predict", files={"file": ("x.png", fake_xray(), "image/png")})
    assert r.status_code == 410 and r.json()["code"] == "ENDPOINT_REMOVED"


def test_model_not_loaded(client_no_model):
    r = client_no_model.post("/predict/clinical", json=FAKE_RECORD)
    assert r.status_code == 503 and r.json()["code"] == "MODEL_NOT_LOADED"
    assert client_no_model.get("/health").json()["model_loaded"] is False
