"""Nạp gói model New Way (ml/new_way, chép nguyên từ New Way/xuat_app) và kiểm tra đầu vào lâm sàng.

Không sửa file trong gói: du_doan.py tự kiểm tra sha256 các file mô hình với cau_hinh_chot.json.
Danh sách cột và mức hợp lệ đọc thẳng từ cau_hinh_chot.json (lam_sang_app.dac_trung.ma_hoa).
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import math
import threading
from pathlib import Path

from PIL import Image

PREDICT_DIR = Path(__file__).resolve().parents[1]
JOB_COLS = ("cviec", "pxuong", "cviec1", "cviec2")  # chữ tự do -> du_doan tự tính cấp nghề
MAX_TEXT = 500
MODEL_NAME = "New Way ketqua: XGBoost lâm sàng + BioViL-T 448 x3 seed + ghép logit"


def resolve_dir(p: str) -> Path:
    d = Path(p)
    return d if d.is_absolute() else PREDICT_DIR / d


class Schema:
    """Kiểu từng cột lâm sàng: 'so' | 'chu' | 'muc' (kèm danh sách mức cho phép)."""

    def __init__(self, cfg: dict):
        self.kind: dict[str, str] = {}
        self.levels: dict[str, list[str]] = {}
        for col, m in cfg["lam_sang_app"]["dac_trung"]["ma_hoa"].items():
            if col.endswith("_cap"):
                continue
            k = m["kieu"]
            if k.startswith("so"):
                self.kind[col] = "so"
            elif k.startswith("co_ghi"):
                self.kind[col] = "chu"
            else:
                self.kind[col] = "muc"
                self.levels[col] = [v for v in m["muc_giu"] if not v.startswith("__")]
        for col in JOB_COLS:
            self.kind[col] = "chu"

    def clean(self, raw: dict) -> tuple[dict, list[dict]]:
        """Trả (dict sạch cho du_doan, danh sách lỗi). Rỗng/null = bỏ trống. Không đưa giá trị vào lỗi."""
        out, errs = {}, []
        for col, v in raw.items():
            kind = self.kind.get(col)
            if kind is None:
                errs.append({"field": col, "error": "UNKNOWN_FIELD"})
                continue
            if isinstance(v, str):
                v = v.strip()
            if v is None or v == "":
                continue  # để trống: model coi là thiếu
            if isinstance(v, (dict, list)) or isinstance(v, bool):
                errs.append({"field": col, "error": "INVALID_TYPE"})
                continue
            if kind == "so":
                try:
                    f = float(v.replace(",", ".")) if isinstance(v, str) else float(v)
                except ValueError:
                    errs.append({"field": col, "error": "NOT_A_NUMBER"})
                    continue
                if not math.isfinite(f):
                    errs.append({"field": col, "error": "NOT_A_NUMBER"})
                    continue
                out[col] = f
            elif kind == "chu":
                s = str(v)
                if len(s) > MAX_TEXT:
                    errs.append({"field": col, "error": "TOO_LONG", "max_length": MAX_TEXT})
                    continue
                out[col] = s
            else:
                allowed = self.levels[col]
                hit = _match_level(v, allowed)
                if hit is None:
                    errs.append({"field": col, "error": "INVALID_CATEGORY", "allowed": allowed})
                    continue
                out[col] = hit
        return out, errs


def _match_level(v, allowed: list[str]) -> str | None:
    """Khớp đúng mã (ASCII không dấu) như du_doan._muc; số 1 / "1" / "1.0" đều khớp mức "1"."""
    cand = [str(v)]
    try:
        f = float(str(v).replace(",", "."))
        cand += [str(f)] + ([str(int(f))] if f.is_integer() else [])
    except ValueError:
        pass
    for c in cand:
        if c in allowed:
            return c
    return None


class NewWay:
    def __init__(self, model_dir: str, device: str = "auto", preload_image: bool = True):
        self.dir = resolve_dir(model_dir)
        cfg_path = self.dir / "cau_hinh_chot.json"
        cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        self.schema = Schema(cfg)
        self.model_version = hashlib.sha256(cfg_path.read_bytes()).hexdigest()[:12]
        mod = _load_module(self.dir / "du_doan.py")
        dev = None if device == "auto" else device
        self.mh = mod.MoHinh(cau_hinh=cfg_path, thu_muc_mo_hinh=self.dir / "mo_hinh", kiem_tra_sha=True, thiet_bi=dev)
        self._lock = threading.Lock()
        self.mh._nap_xgb()  # kiểm tra sha256 + nạp ngay, lỗi sớm lúc khởi động
        self.image_loaded = False
        if preload_image:
            self.load_image_model()

    def load_image_model(self):
        with self._lock:
            self.mh._nap_anh()
            self.image_loaded = True

    @property
    def device(self) -> str | None:
        d = self.mh._dev
        return str(d) if d is not None and not isinstance(d, str) else d

    def predict(self, lam_sang: dict, anh: Image.Image | None = None) -> dict:
        with self._lock:
            r = self.mh.du_doan(lam_sang, anh)
            if anh is not None:
                self.image_loaded = True
            return r


def _load_module(path: Path):
    spec = importlib.util.spec_from_file_location("new_way_du_doan", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod
