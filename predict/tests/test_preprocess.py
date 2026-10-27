import io

import pytest
from PIL import Image

from app import preprocess
from app.errors import PredictError
from conftest import fake_xray


@pytest.mark.parametrize("size,fmt,mode", [
    ((300, 300), "PNG", "L"),
    ((1024, 768), "JPEG", "RGB"),   # không vuông, RGB
    ((200, 400), "PNG", "I;16"),    # PNG 16-bit
])
def test_tensor_shape_and_range(size, fmt, mode):
    img = preprocess.decode(fake_xray(size, fmt, mode), 50_000_000)
    x = preprocess.to_tensor(img)
    assert tuple(x.shape) == (1, 3, 448, 448)
    assert 0.0 <= float(x.min()) and float(x.max()) <= 1.0


def test_16bit_is_rescaled_not_clipped():
    img = preprocess.decode(fake_xray((200, 200), "PNG", "I;16"), 50_000_000)
    gray = preprocess.to_gray(img)
    lo, hi = gray.getextrema()
    assert lo < 50 and hi > 200  # convert("L") trực tiếp sẽ ra gần như toàn 255


def test_rejects_too_many_pixels():
    with pytest.raises(PredictError) as e:
        preprocess.decode(fake_xray((300, 300)), max_pixels=10_000)
    assert e.value.code == "INVALID_IMAGE"


def test_rejects_truncated_png():
    data = fake_xray((300, 300))
    with pytest.raises(PredictError) as e:
        preprocess.decode(data[: len(data) // 2], 50_000_000)
    assert e.value.code == "INVALID_IMAGE"


def test_channels_identical():
    buf = io.BytesIO()
    Image.new("RGB", (64, 64), (200, 10, 10)).save(buf, format="PNG")
    x = preprocess.to_tensor(preprocess.decode(buf.getvalue(), 50_000_000))
    assert bool((x[0, 0] == x[0, 1]).all() and (x[0, 1] == x[0, 2]).all())
