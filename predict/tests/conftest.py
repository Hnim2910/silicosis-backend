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

MODEL_PATH = os.environ.get("MODEL_PATH") or Settings().model_path
HAS_MODEL = bool(MODEL_PATH) and Path(MODEL_PATH).is_file()
needs_model = pytest.mark.skipif(not HAS_MODEL, reason="không có checkpoint (MODEL_PATH)")


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
        pytest.skip("không có checkpoint (MODEL_PATH)")
    app = create_app(Settings(model_path=MODEL_PATH, device="cpu"))
    with TestClient(app) as c:
        yield c


@pytest.fixture(scope="session")
def client_no_model():
    """Không nạp model: dùng cho các lỗi đầu vào (kiểm tra trước khi cần model)."""
    app = create_app(Settings(model_path="", max_upload_mb=1))
    with TestClient(app) as c:
        yield c
