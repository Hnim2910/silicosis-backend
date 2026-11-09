import io
import os
import sys
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.new_way import resolve_dir  # noqa: E402

MODEL_DIR = os.environ.get("NEW_WAY_DIR") or Settings().new_way_dir
HAS_MODEL = (resolve_dir(MODEL_DIR) / "mo_hinh" / "lam_sang_app_xgb.json").is_file()
needs_model = pytest.mark.skipif(not HAS_MODEL, reason="không có gói model New Way (ml/new_way/mo_hinh)")

# Bản ghi GIẢ, tự đặt — không phải bệnh nhân thật.
FAKE_RECORD = {
    "gioitinh": "Nam", "namsinh": 1975, "tuoinghe": "12", "cv5nam": "Co",
    "cviec": "cong nhan nghien da", "pxuong": "phan xuong nghien", "cviec1": "", "cviec2": None,
    "tgian1": "8,5", "cao": 165, "can": "60,5", "hatd": 80, "hatt": 125, "mach": 76, "theluc": 2,
    "hutthuoc": "Co hut thuoc", "slthuoc": 10, "tiensuhh": "Khong", "benhhh": "",
    "ho": "Co", "tdho": "Ban ngay", "tsho": "Tung con", "khacdom": "Khong",
    "khotho": "Co", "mdkhotho": "Kho tho khi gang suc", "tdkhotho": "Ban ngay",
    "daunguc": "Khong", "ran": "Khong", "longnguc": "Can doi",
    "fvc": "3,2", "fev1": 2.6, "gaenler": 81,
}


def fake_xray(size=(300, 300), fmt="PNG", mode="L") -> bytes:
    """Ảnh giả: gradient + nhiễu, không phải dữ liệu bệnh nhân."""
    rng = np.random.default_rng(0)
    h, w = size[1], size[0]
    base = np.linspace(0, 1, w)[None, :] * np.linspace(0.3, 1, h)[:, None]
    arr = np.clip(base + rng.normal(0, 0.05, (h, w)), 0, 1)
    if mode == "I;16":
        img = Image.fromarray((arr * 65535).astype(np.uint16))
    else:
        img = Image.fromarray((arr * 255).astype(np.uint8), mode="L").convert(mode)
    buf = io.BytesIO()
    img.save(buf, format=fmt)
    return buf.getvalue()


@pytest.fixture(scope="session")
def client():
    if not HAS_MODEL:
        pytest.skip("không có gói model New Way")
    app = create_app(Settings(new_way_dir=MODEL_DIR, device=os.environ.get("TEST_DEVICE", "auto"),
                              preload_image_model=False, max_upload_mb=1))
    with TestClient(app) as c:
        yield c


@pytest.fixture(scope="session")
def client_no_model(tmp_path_factory):
    """Không nạp được model: thư mục rỗng."""
    app = create_app(Settings(new_way_dir=str(tmp_path_factory.mktemp("empty")), max_upload_mb=1))
    with TestClient(app) as c:
        yield c
