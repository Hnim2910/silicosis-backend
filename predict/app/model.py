"""Dựng, nạp và chạy model BioViL-T fine-tune Set A (nhãn bnn).

Kiến trúc chép từ Finetune 4/finetune_biovilt_setA.py. Lớp 1 = "Co".
"""
import hashlib
import threading
from pathlib import Path

import torch
import torch.nn as nn
from health_multimodal.image.model.model import ImageModel
from health_multimodal.image.model.types import ImageEncoderType

MODEL_NAME = "BioViL-T fine-tune Set A (bnn)"


class BioViLTClassifier(nn.Module):
    def __init__(self, backbone: nn.Module, num_classes: int = 2, dropout: float = 0.3):
        super().__init__()
        self.backbone = backbone
        self.head = nn.Sequential(nn.Dropout(dropout),
                                  nn.Linear(backbone.feature_size, num_classes))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        feat = self.backbone(x).img_embedding.flatten(1)  # [B, 512]
        return self.head(feat)


def build_model() -> BioViLTClassifier:
    # Không cần file pretrain: encoder dựng với pretrained=False, trọng số lấy từ checkpoint.
    backbone = ImageModel(img_encoder_type=ImageEncoderType.RESNET50_MULTI_IMAGE,
                          joint_feature_size=128, pretrained_model_path=None)
    return BioViLTClassifier(backbone)


def file_version(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            sha.update(chunk)
    return sha.hexdigest()[:12]


def resolve_device(name: str) -> torch.device:
    if name == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(name)


class Predictor:
    def __init__(self, model_path: str, device: str, threshold: float):
        path = Path(model_path)
        if not path.is_file():
            raise FileNotFoundError("MODEL_PATH không trỏ tới file checkpoint")
        self.device = resolve_device(device)
        self.threshold = threshold
        self.model_version = file_version(path)
        self.model = build_model()
        state = torch.load(path, map_location=self.device, weights_only=True)
        self.model.load_state_dict(state, strict=True)
        self.model.to(self.device).eval()
        self._lock = threading.Lock()
        self.probability(torch.zeros(1, 3, 448, 448))  # warm-up

    def probability(self, x: torch.Tensor) -> float:
        with self._lock, torch.inference_mode():
            logits = self.model(x.to(self.device))
            return float(torch.softmax(logits, dim=1)[0, 1])
