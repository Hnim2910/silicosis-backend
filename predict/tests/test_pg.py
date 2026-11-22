"""Test service PG (POST /predict/pg, /health, /schema). Chỉ dùng bản ghi giả FAKE_PG và ảnh giả tạo bằng PIL."""
import io
import json

import pytest
from PIL import Image

from conftest import FAKE_PG, fake_xray, needs_pg, needs_pg_image

KEYS = {"nhan", "xac_suat", "xac_suat_theo_fold", "co_anh", "tinh", "chu_y_vung", "qa_anh", "canh_bao",
        "canh_bao_dau_vao", "disclaimer", "experimental", "mo_hinh", "model_name", "model_version"}
REG = {"RU", "RM", "RL", "LU", "LM", "LL"}


def _pg(client, rec, data=None, name="x.png", ctype="image/png"):
    files = {"file": (name, data, ctype)} if data is not None else None
    return client.post("/predict/pg", data={"lam_sang": json.dumps(rec)}, files=files)


def _codes(r):
    return {(e["field"], e["error"]) for e in r.json().get("errors", [])}


@needs_pg
def test_health(client_pg_only):
    b = client_pg_only.get("/health").json()
    assert b["model_loaded"] is True and len(b["model_version"]) == 12 and b["model_name"].startswith("PG ketqua")


@needs_pg
def test_pg_clinical_only(client_pg_only):
    r = _pg(client_pg_only, FAKE_PG)
    assert r.status_code == 200, r.text
    b = r.json()
    assert set(b) == KEYS
    assert 0 <= b["xac_suat"] <= 1 and len(b["xac_suat_theo_fold"]) == 5
    assert b["co_anh"] is False and b["chu_y_vung"] is None and b["qa_anh"] is None
    assert b["tinh"] == "Thai Nguyen" and b["experimental"] is True and b["canh_bao_dau_vao"] == []
    assert b["disclaimer"] == "Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ."
    assert not any("nguong" in k or "ket_luan" in k or "bao_nguy_co" in k for k in b)  # không ngưỡng, không nhãn
    assert r.headers["x-model-name"] == "PG ketqua" and r.headers["x-model-version"] == b["model_version"]


@needs_pg
def test_pg_matches_direct_call(client_pg_only):
    """Wrapper không đổi điểm: so với gọi thẳng MoHinhPG.du_doan()."""
    m = client_pg_only.app.state.pg
    clean, errs, _ = m.schema.clean(FAKE_PG)
    assert not errs
    direct = m.mh.du_doan(clean)
    b = _pg(client_pg_only, FAKE_PG).json()
    assert b["xac_suat"] == direct["xac_suat"] and b["xac_suat_theo_fold"] == direct["xac_suat_theo_fold"]


@needs_pg
def test_pg_only_tinh(client_pg_only):
    r = _pg(client_pg_only, {"tinh": "Hai Duong"})
    assert r.status_code == 200 and 0 <= r.json()["xac_suat"] <= 1


@needs_pg
def test_pg_decimal_comma_equals_dot(client_pg_only):
    a = _pg(client_pg_only, {**FAKE_PG, "fvctt": "3,2", "can": "60,5"}).json()
    b = _pg(client_pg_only, {**FAKE_PG, "fvctt": 3.2, "can": 60.5}).json()
    assert a["xac_suat"] == b["xac_suat"]


@needs_pg
@pytest.mark.parametrize("rec,err", [({}, "REQUIRED"), ({"tinh": ""}, "REQUIRED"), ({"tinh": "Ha Noi"}, "INVALID_CATEGORY"),
                                     ({"tinh": "Thái Nguyên"}, "INVALID_CATEGORY"), ({"tinh": "thai nguyen"}, "INVALID_CATEGORY"),
                                     ({"tinh": 1}, "INVALID_CATEGORY")])
def test_pg_bad_tinh_422(client_pg_only, rec, err):
    r = _pg(client_pg_only, {**{k: v for k, v in FAKE_PG.items() if k != "tinh"}, **rec})
    assert r.status_code == 422 and r.json()["code"] == "INVALID_INPUT"
    e = r.json()["errors"][0]
    assert e["field"] == "tinh" and e["error"] == err and len(e["allowed"]) == 5


@needs_pg
@pytest.mark.parametrize("field,value,err", [("namsinh", "abc", "NOT_A_NUMBER"), ("cao", "nan", "NOT_A_NUMBER"),
                                             ("ho", ["Co"], "INVALID_TYPE"), ("bnn", True, "INVALID_TYPE"),
                                             ("cviec", "x" * 501, "TOO_LONG")])
def test_pg_other_422(client_pg_only, field, value, err):
    r = _pg(client_pg_only, {**FAKE_PG, field: value})
    assert r.status_code == 422 and (field, err) in _codes(r)


@needs_pg
def test_pg_unknown_and_derived_fields_ignored(client_pg_only):
    base = _pg(client_pg_only, FAKE_PG).json()
    r = _pg(client_pg_only, {**FAKE_PG, "cot_la": 1, "bmi": 99, "fev1_fvc": 0.1, "cviec_lv": "lv10"})
    assert r.status_code == 200, r.text
    b = r.json()
    w = {(e["field"], e["error"]) for e in b["canh_bao_dau_vao"]}
    assert w == {("cot_la", "UNKNOWN_FIELD_IGNORED"), ("bmi", "DERIVED_FIELD_IGNORED"),
                 ("fev1_fvc", "DERIVED_FIELD_IGNORED"), ("cviec_lv", "DERIVED_FIELD_IGNORED")}
    assert b["xac_suat"] == base["xac_suat"]


@needs_pg
def test_pg_unrecognized_category_warns(client_pg_only):
    r = _pg(client_pg_only, {**FAKE_PG, "gioitinh": "nam"})
    assert r.status_code == 200
    w = r.json()["canh_bao_dau_vao"]
    assert w[0]["field"] == "gioitinh" and w[0]["error"] == "UNRECOGNIZED_VALUE" and "Nam" in w[0]["allowed"]


@needs_pg
def test_schema(client_pg_only):
    b = client_pg_only.get("/schema").json()
    assert b["required"] == ["tinh"] and len(b["tinh"]) == 5
    assert b["kind"]["namsinh"] == "so" and b["kind"]["cviec"] == "chu" and b["levels"]["gioitinh"] == ["Nam", "Nu"]
    assert "bmi" in b["derived"] and "bmi" not in b["kind"] and "tinh" not in b["kind"]


@needs_pg_image
def test_pg_with_image(client_pg_only):
    r = _pg(client_pg_only, FAKE_PG, fake_xray((512, 400), "JPEG"), "a.jpg", "image/jpeg")
    assert r.status_code == 200, r.text
    b = r.json()
    assert set(b) == KEYS and b["co_anh"] is True and 0 <= b["xac_suat"] <= 1
    assert set(b["chu_y_vung"]) == REG
    assert {"dao_am", "du_phoi", "vung_rong"} <= set(b["qa_anh"])
    assert client_pg_only.get("/health").json()["image_model_loaded"] is True


@needs_pg_image
def test_pg_image_matches_direct_call(client_pg_only):
    m = client_pg_only.app.state.pg
    data = fake_xray((400, 400), "PNG", mode="I;16")
    clean, _, _ = m.schema.clean(FAKE_PG)
    direct = m.mh.du_doan(clean, anh=Image.open(io.BytesIO(data)))
    b = _pg(client_pg_only, FAKE_PG, data).json()
    assert b["xac_suat"] == direct["xac_suat"] and b["qa_anh"] == direct["qa_anh"]


@needs_pg_image
def test_pg_image_low_film_province_warns(client_pg_only):
    b = _pg(client_pg_only, {**FAKE_PG, "tinh": "Dong Nai"}, fake_xray()).json()
    assert any("Đồng Nai" in c for c in b["canh_bao"])


@needs_pg
def test_pg_broken_image_400(client_pg_only):
    r = _pg(client_pg_only, FAKE_PG, b"hello, not an image")
    assert r.status_code == 400 and r.json()["code"] == "INVALID_IMAGE"


@needs_pg
def test_pg_empty_image_400(client_pg_only):
    r = _pg(client_pg_only, FAKE_PG, b"")
    assert r.status_code == 400 and r.json()["code"] == "INVALID_IMAGE"


@needs_pg
@pytest.mark.parametrize("fmt", ["GIF", "BMP"])
def test_pg_unsupported_format(client_pg_only, fmt):
    buf = io.BytesIO()
    Image.new("L", (64, 64), 128).save(buf, format=fmt)
    r = _pg(client_pg_only, FAKE_PG, buf.getvalue(), "fake.png")
    assert r.status_code == 400 and r.json()["code"] == "UNSUPPORTED_FORMAT"


@needs_pg
def test_pg_too_large(client_pg_only):  # giới hạn 1 MB trong fixture
    r = _pg(client_pg_only, FAKE_PG, b"\0" * (1024 * 1024 + 1))
    assert r.status_code == 400 and r.json()["code"] == "FILE_TOO_LARGE"


@needs_pg
def test_pg_bad_json_422(client_pg_only):
    r = client_pg_only.post("/predict/pg", data={"lam_sang": "{not json"})
    assert r.status_code == 422 and r.json()["code"] == "INVALID_INPUT"


def test_pg_not_loaded_503(client_no_model):
    r = _pg(client_no_model, FAKE_PG)
    assert r.status_code == 503 and r.json()["code"] == "MODEL_NOT_LOADED"
    assert client_no_model.get("/health").json()["model_loaded"] is False


def test_schema_not_loaded_503(client_no_model):
    r = client_no_model.get("/schema")
    assert r.status_code == 503 and r.json()["code"] == "MODEL_NOT_LOADED"


@pytest.mark.parametrize("path", ["/predict", "/predict/clinical", "/predict/clinical-image", "/health/pg", "/schema/pg"])
def test_removed_routes_410(client_no_model, path):
    r = client_no_model.post(path, json={"tinh": "Thai Nguyen"})
    assert r.status_code == 410 and r.json()["code"] == "ENDPOINT_REMOVED"


def test_pg_isolated_import(client_pg_only):
    """Gói PG nạp trong phòng kín: không để lại tên module chung trong sys.modules."""
    import sys
    from pathlib import Path
    pg_mod = client_pg_only.app.state.pg.mod
    assert Path(pg_mod.them_cap_nghe.__code__.co_filename).parent.name == "pg"
    assert not {"nghe", "mo_hinh_pg", "xu_ly_anh"} & set(sys.modules)
