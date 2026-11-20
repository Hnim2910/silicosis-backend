"""Nạp mô hình PG (ml/pg, chép nguyên PAG/xuat_app_pg) và kiểm tra đầu vào lâm sàng cho PG.

Không sửa file trong gói: MoHinhPG tự kiểm sha256 các file trọng số theo mo_hinh_PG_web/cau_hinh_chung.json.
Danh sách cột, kiểu và mức nhận ra đọc thẳng từ fold*/ma_hoa_lam_sang.json (hợp 5 fold).

Xung đột tên module: du_doan_pg.py làm `sys.path.insert(0, DIR)` rồi `from nghe import ...`, còn ml/new_way/du_doan.py
cũng `from nghe import ...` (một file nghe.py KHÁC) ngay trong hàm. Nếu để chung `sys.modules["nghe"]`, mô hình nạp sau
sẽ dùng nhầm bản của mô hình nạp trước. Vì vậy PG được nạp trong một "phòng kín": tạm gỡ các khoá nghe / mo_hinh_pg /
xu_ly_anh khỏi sys.modules, nạp, rồi trả sys.modules và sys.path về như cũ. Module PG giữ tham chiếu riêng tới hàm
của nó, nên sau đó không còn phụ thuộc tên chung nào.
"""
from __future__ import annotations

import hashlib
import importlib.util
import math
import sys
import threading
from pathlib import Path

from PIL import Image

from .new_way import MAX_TEXT, resolve_dir

MODEL_NAME = "PG ketqua: Graph Transformer 6 vùng ảnh + 6 nhóm lâm sàng + tỉnh, khoá 07/11/2026"
WEIGHTS = "mo_hinh_PG_web"
JOB_COLS = ("cviec", "pxuong", "cviec1", "cviec2")  # chữ nghề tự do -> mô hình tự tính *_lv
_ISOLATE = ("nghe", "mo_hinh_pg", "xu_ly_anh")


def _load_isolated(pkg: Path):
    """Nạp du_doan_pg.py và xu_ly_anh.py của gói PG mà không để lại 'nghe'/'mo_hinh_pg'/'xu_ly_anh' trong sys.modules."""
    saved = {k: sys.modules.pop(k) for k in _ISOLATE if k in sys.modules}
    path_before = list(sys.path)
    try:
        spec = importlib.util.spec_from_file_location("pg_du_doan", pkg / "du_doan_pg.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        # du_doan_pg nạp xu_ly_anh "lười" bằng tên chung; nạp sẵn ở đây (trong phòng kín) để giữ đúng bản của gói.
        spec2 = importlib.util.spec_from_file_location("pg_xu_ly_anh", pkg / "xu_ly_anh.py")
        xla = importlib.util.module_from_spec(spec2)
        spec2.loader.exec_module(xla)
    finally:
        for k in _ISOLATE:
            sys.modules.pop(k, None)
        sys.modules.update(saved)
        sys.path[:] = path_before
    return mod, xla


def _match(v, allowed: set[str]) -> bool:
    """Giống du_doan_pg._muc: số 1 / "1" / "1.0" đều khớp mức "1"."""
    cand = [str(v)]
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        f = float(v)
        cand += [str(f)] + ([str(int(f))] if f.is_integer() else [])
    return any(c in allowed for c in cand)


class PGSchema:
    """Kiểu từng cột: 'so' | 'chu' (chỉ học có ghi / bỏ trống, hoặc chữ nghề) | 'muc' (kèm các mức nhận ra)."""

    def __init__(self, folds: list[dict], tinh_hop_le: list[str]):
        self.tinh = list(tinh_hop_le)
        self.kind: dict[str, str] = {}
        self.levels: dict[str, list[str]] = {}
        self.derived = derived = {"bmi", "fev1_fvc"} | {c + "_lv" for c in JOB_COLS}  # mô hình tự tính, ghi đè
        for col in folds[0]["spec"]:
            if col in derived or col == "tinh":
                continue
            specs = [f["spec"][col] for f in folds]
            if specs[0][0] == "num":
                self.kind[col] = "so"
                continue
            lv: list[str] = []
            for s in specs:  # hợp các mức qua 5 fold, giữ thứ tự gặp
                lv += [x for x in s[1] if not x.startswith("__") and x not in lv]
            if lv:
                self.kind[col], self.levels[col] = "muc", lv
            else:
                self.kind[col] = "chu"
        for col in JOB_COLS:
            self.kind[col] = "chu"
        self._sets = {c: set(v) for c, v in self.levels.items()}

    def clean(self, raw: dict) -> tuple[dict, list[dict], list[dict]]:
        """Trả (dict sạch cho du_doan, lỗi chặn -> 422, cảnh báo không chặn). Không đưa giá trị người dùng vào lỗi."""
        out, errs, warns = {}, [], []
        t = raw.get("tinh")
        t = t.strip() if isinstance(t, str) else t
        if t is None or t == "":
            errs.append({"field": "tinh", "error": "REQUIRED", "allowed": self.tinh})
        elif not isinstance(t, str) or t not in self.tinh:
            errs.append({"field": "tinh", "error": "INVALID_CATEGORY", "allowed": self.tinh})
        else:
            out["tinh"] = t
        for col, v in raw.items():
            if col == "tinh":
                continue
            if col in self.derived:
                warns.append({"field": col, "error": "DERIVED_FIELD_IGNORED"})
                continue
            kind = self.kind.get(col)
            if kind is None:
                warns.append({"field": col, "error": "UNKNOWN_FIELD_IGNORED"})
                continue
            if isinstance(v, str):
                v = v.strip()
            if v is None or v == "":
                continue  # bỏ trống
            if isinstance(v, (dict, list, bool)):
                errs.append({"field": col, "error": "INVALID_TYPE"})
                continue
            if kind == "so":
                try:
                    f = float(v.replace(",", ".")) if isinstance(v, str) else float(v)
                except ValueError:
                    f = math.nan
                if not math.isfinite(f):
                    errs.append({"field": col, "error": "NOT_A_NUMBER"})
                    continue
                out[col] = f
                continue
            if isinstance(v, str) and len(v) > MAX_TEXT:
                errs.append({"field": col, "error": "TOO_LONG", "max_length": MAX_TEXT})
                continue
            if kind == "muc" and not _match(v, self._sets[col]):
                # Mô hình coi là "có ghi nhưng không nhận ra" (không lỗi) -> vẫn gửi, kèm cảnh báo cho người nhập.
                warns.append({"field": col, "error": "UNRECOGNIZED_VALUE", "allowed": self.levels[col]})
            out[col] = v
        return out, errs, warns


class PG:
    def __init__(self, pg_dir: str, biovil_path: str, device: str = "auto"):
        self.dir = resolve_dir(pg_dir)
        cfg_path = self.dir / WEIGHTS / "cau_hinh_chung.json"
        self.model_version = hashlib.sha256(cfg_path.read_bytes()).hexdigest()[:12]
        self.mod, self._xla = _load_isolated(self.dir)  # self.mod: module du_doan_pg riêng của PG
        mod = self.mod
        self.disclaimer = mod.DISCLAIMER
        self.mh = mod.MoHinhPG(self.dir / WEIGHTS, thiet_bi=None if device == "auto" else device, kiem_tra_sha=True)
        self.schema = PGSchema(self.mh.folds, self.mh.tinh_hop_le)
        self.biovil = resolve_dir(biovil_path)
        self._lock = threading.Lock()

    @property
    def device(self) -> str:
        return str(self.mh.dev)

    @property
    def image_loaded(self) -> bool:
        return self.mh._xu_ly_anh is not None

    def _ensure_image(self):
        if self.mh._xu_ly_anh is None:
            if not self.biovil.is_file():
                raise FileNotFoundError("thiếu file BioViL-T")
            # Cùng lời gọi như MoHinhPG.xu_ly_anh(), nhưng dùng bản xu_ly_anh nạp trong phòng kín.
            self.mh._xu_ly_anh = self._xla.XuLyAnh(str(self.biovil), thiet_bi=str(self.mh.dev))

    def load_image_model(self):
        with self._lock:
            self._ensure_image()

    def predict(self, lam_sang: dict, anh: Image.Image | None = None, warns: list[dict] | None = None) -> dict:
        with self._lock:
            if anh is not None:
                self._ensure_image()
            r = self.mh.du_doan(lam_sang, anh=anh)
        return dict(
            nhan=r["nhan"], xac_suat=r["xac_suat"], xac_suat_theo_fold=r["xac_suat_theo_fold"], co_anh=r["co_anh"],
            tinh=r["tinh"], chu_y_vung=r["chu_y_vung"], qa_anh=r["qa_anh"], canh_bao=r["canh_bao"],
            canh_bao_dau_vao=warns or [], disclaimer=r["disclaimer"], experimental=r["experimental"],
            mo_hinh=r["mo_hinh"], model_name=MODEL_NAME, model_version=self.model_version)
