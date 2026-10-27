"""Tiền xử lý ảnh, giống hệt lúc huấn luyện.

Chuỗi gốc: Finetune/resize_setA.py (convert("L") -> resize 256x256 LANCZOS)
-> dataset .convert("L") -> eval_tf của Finetune 4/finetune_biovilt_setA.py
(Resize(512) -> CenterCrop(448) -> ToTensor -> ExpandChannels, KHÔNG normalize ImageNet).
"""
import io

import numpy as np
import torch
from health_multimodal.image.data.transforms import ExpandChannels
from PIL import Image, UnidentifiedImageError
from torchvision.transforms import CenterCrop, Compose, Resize, ToTensor

from .errors import PredictError, invalid_image

ALLOWED_FORMATS = {"JPEG", "PNG"}
TRAIN_SIZE = 256

EVAL_TF = Compose([
    Resize(512),
    CenterCrop(448),
    ToTensor(),
    ExpandChannels(),
])


def decode(data: bytes, max_pixels: int) -> Image.Image:
    """Mở ảnh từ byte, chỉ nhận JPEG/PNG (xác định theo nội dung, không theo tên tệp)."""
    try:
        img = Image.open(io.BytesIO(data))
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise invalid_image()
    if img.format not in ALLOWED_FORMATS:
        raise PredictError(400, "UNSUPPORTED_FORMAT", "Chỉ nhận ảnh JPEG hoặc PNG.")
    width, height = img.size
    if width * height > max_pixels:
        raise invalid_image()
    try:
        img.load()
    except (OSError, ValueError, Image.DecompressionBombError):
        raise invalid_image()
    return img


def to_gray(img: Image.Image) -> Image.Image:
    """Đưa về ảnh xám 8-bit. Ảnh 16-bit/float co giãn min-max về 0-255,
    vì convert("L") trực tiếp sẽ cắt giá trị và ra ảnh gần như trắng."""
    if img.mode in ("I;16", "I;16L", "I;16B", "I", "F"):
        arr = np.asarray(img, dtype=np.float32)
        lo, hi = float(arr.min()), float(arr.max())
        arr = (arr - lo) / (hi - lo) * 255.0 if hi > lo else np.zeros_like(arr)
        return Image.fromarray(arr.round().astype(np.uint8), mode="L")
    return img.convert("L")


def to_tensor(img: Image.Image) -> torch.Tensor:
    """Ảnh PIL -> tensor [1, 3, 448, 448], giá trị trong [0, 1]."""
    gray = to_gray(img)
    # Ép đúng 256x256 như resize_setA.py (ảnh không vuông sẽ bị méo).
    gray = gray.resize((TRAIN_SIZE, TRAIN_SIZE), Image.LANCZOS)
    return EVAL_TF(gray).unsqueeze(0)
