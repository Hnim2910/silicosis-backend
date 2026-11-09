"""Mo-dun du doan New Way cho app (nhan = ketqua: ket qua doc phim nghi bui phoi silic).

Hai luong:
  1. Chua co phim : p_ls  = diem_lam_sang(bang_lam_sang)           -> so voi nguong lam sang
  2. Co phim       : p_anh = diem_anh(danh_sach_anh)
                     p     = ghep(p_anh, p_ls)                         -> so voi nguong ghep
Moi gia tri, he so va nguong lay tu cau_hinh_chot.json (chot tren dev, da cham test khoa 1 lan o buoc 7).
Khong sua cau hinh sau khi da xem test.

Dung:
    from du_doan import MoHinh
    mh = MoHinh()                     # mac dinh doc file trong cung thu muc
    p_ls = mh.diem_lam_sang(df)       # df: pandas DataFrame, moi dong mot luot kham, cot theo ten trong MO_TA_API.md
    p_anh = mh.diem_anh([mh.doc_anh("phim.png")])
    p = mh.ghep(p_anh, p_ls)
    mh.ket_luan(p, "ghep")            # True = bao nguy co (can xem tiep)
"""
from __future__ import annotations

import hashlib, json, math, re, unicodedata
from pathlib import Path

import numpy as np
import pandas as pd

DIR = Path(__file__).resolve().parent
NEG_TEXT = {"", "khong", "khong co", "k", "0", "binh thuong", "nan"}  # giong buoc2_lam_sang.py
EPS = 1e-6


def norm_text(s):
    if s is None or (not isinstance(s, str) and pd.isna(s)):
        return ""
    s = unicodedata.normalize("NFD", str(s).replace("đ", "d").replace("Đ", "d"))
    s = "".join(c for c in s if unicodedata.category(c) != "Mn").lower()
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", s)).strip()


def logit(p):
    p = np.clip(np.asarray(p, dtype=np.float64), EPS, 1 - EPS)
    return np.log(p / (1 - p))


def _muc(v, muc_giu: set) -> str:
    """Gia tri tho -> muc (chuoi) giong pandas astype(str) luc hoc tren dev; muc la/hiem -> '__hiem__'."""
    if v is None or (not isinstance(v, str) and pd.isna(v)):
        cand = ["__NA__"]
    else:
        cand = [str(v)]
        if isinstance(v, (int, float, np.integer, np.floating)) and not isinstance(v, bool):
            f = float(v); cand += [str(f)] + ([str(int(f))] if f.is_integer() else [])
        elif isinstance(v, str):
            try:
                f = float(v.strip().replace(",", ".")); cand += [str(f)] + ([str(int(f))] if f.is_integer() else [])
            except ValueError:
                pass
    for c in cand:
        if c in muc_giu:
            return c
    return "__hiem__"


def sha256(p) -> str:
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


class MoHinh:
    def __init__(self, cau_hinh=DIR / "cau_hinh_chot.json", thu_muc_mo_hinh=DIR / "mo_hinh", kiem_tra_sha=True, thiet_bi=None):
        self.cfg = json.loads(Path(cau_hinh).read_text(encoding="utf-8"))
        self.dir = Path(thu_muc_mo_hinh); self.kiem_tra_sha = kiem_tra_sha
        self.ls_cfg, self.anh_cfg, self.ghep_cfg = self.cfg["lam_sang_app"], self.cfg["anh_bioviL_t_448"], self.cfg["ghep_gon"]
        self.nguong = {"lam_sang": self.ls_cfg["nguong_tho"]["bien_an_toan_0.80"], "ghep": self.ghep_cfg["nguong_tho"]["bien_an_toan_0.80"]}
        self._xgb = None; self._nets = None; self._dev = thiet_bi

    # ---------------- lam sang ----------------
    def ma_hoa_lam_sang(self, df: pd.DataFrame) -> pd.DataFrame:
        if not any(c + "_cap" in df.columns for c in ("cviec", "pxuong", "cviec1", "cviec2")):
            import sys; sys.path.insert(0, str(DIR)); from nghe import them_cap_nghe
            df = them_cap_nghe(df)  # chu nghe tu do -> cap 1..10 (giong buoc1)
        ma = self.ls_cfg["dac_trung"]["ma_hoa"]; out = {}
        for c, m in ma.items():
            s = df[c] if c in df.columns else pd.Series([np.nan] * len(df), index=df.index, dtype=object)
            k = m["kieu"]
            if k.startswith("co_ghi"):
                out[m["dac_trung"][0]] = (~s.map(norm_text).isin(NEG_TEXT)).astype(float)
            elif k.startswith("so"):
                x = pd.to_numeric(s, errors="coerce").astype(float); out[c] = x
                if c + "__thieu" in m["dac_trung"]:
                    out[c + "__thieu"] = x.isna().astype(float)
            else:
                giu = set(m["muc_giu"]); v = s.map(lambda a: _muc(a, giu))
                for lv, n in zip(m["muc_one_hot"], m["dac_trung"]):
                    out[n] = (v == lv).astype(float)
        return pd.DataFrame(out, index=df.index)[self.ls_cfg["dac_trung"]["thu_tu_cot"]]

    def _nap_xgb(self):
        if self._xgb is None:
            import xgboost as xgb
            f = self.dir / Path(self.ls_cfg["file"]).name
            if self.kiem_tra_sha and sha256(f) != self.ls_cfg["sha256"]:
                raise RuntimeError("sha256 mo hinh lam sang khong khop cau_hinh_chot.json")
            self._xgb = xgb.XGBClassifier(); self._xgb.load_model(f)
        return self._xgb

    def diem_lam_sang(self, df: pd.DataFrame) -> np.ndarray:
        return self._nap_xgb().predict_proba(self.ma_hoa_lam_sang(df).values.astype(float))[:, 1]

    # ---------------- anh ----------------
    @staticmethod
    def doc_anh(nguon, S=512) -> np.ndarray:
        """Duong dan hoac PIL.Image -> uint8 512x512: xam; canh ngan->512 bilinear; cat giua; cat phan vi 0,5-99,5 (giong buoc4_cache_anh.py)."""
        from PIL import Image
        im = (nguon if hasattr(nguon, "convert") else Image.open(nguon)).convert("L"); w, h = im.size; r = S / min(w, h)
        im = im.resize((max(S, round(w * r)), max(S, round(h * r))), Image.BILINEAR)
        a = np.asarray(im, dtype=np.float32); h, w = a.shape; t, l = (h - S) // 2, (w - S) // 2; a = a[t:t + S, l:l + S]
        lo, hi = np.percentile(a, [0.5, 99.5]); a = np.clip((a - lo) / max(hi - lo, 1e-6), 0, 1)
        return (a * 255).round().astype(np.uint8)

    def _nap_anh(self):
        if self._nets is None:
            import torch, torch.nn as nn
            from health_multimodal.image.model.model import ImageModel
            from health_multimodal.image.model.types import ImageEncoderType

            class Net(nn.Module):
                def __init__(s):
                    super().__init__()
                    s.m = ImageModel(img_encoder_type=ImageEncoderType.RESNET50_MULTI_IMAGE, joint_feature_size=128)
                    s.head = nn.Sequential(nn.Dropout(0.2), nn.Linear(512, 1))

                def forward(s, x):
                    return s.head(s.m(x.repeat(1, 3, 1, 1)).img_embedding).squeeze(1)

            self._dev = torch.device(self._dev or ("cuda" if torch.cuda.is_available() else "cpu")); self._nets = []
            for ten, h in self.anh_cfg["sha256"].items():
                f = self.dir / ten
                if self.kiem_tra_sha and sha256(f) != h:
                    raise RuntimeError(f"sha256 {ten} khong khop cau_hinh_chot.json")
                n = Net(); n.load_state_dict(torch.load(f, map_location="cpu")); self._nets.append(n.to(self._dev).eval())
        return self._nets

    def diem_anh(self, anh_uint8, bs=24, tung_seed=False):
        """anh_uint8: list/mang (N,512,512) uint8 tu doc_anh. Tra ve trung binh xac suat 3 seed (va tung seed neu can)."""
        import torch, torch.nn.functional as F
        nets = self._nap_anh(); A = np.asarray(anh_uint8); P = np.zeros((len(nets), len(A)))
        cuda = self._dev.type == "cuda"
        with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16, enabled=cuda):
            for i in range(0, len(A), bs):
                x = torch.from_numpy(A[i:i + bs]).to(self._dev).float().div(255).unsqueeze(1)
                x = F.interpolate(x, size=448, mode="bilinear", antialias=True, align_corners=False)
                for j, n in enumerate(nets):
                    P[j, i:i + bs] = torch.sigmoid(n(x).float()).cpu().numpy()
        return (P.mean(0), P) if tung_seed else P.mean(0)

    # ---------------- ghep + ket luan ----------------
    def ghep(self, p_anh, p_ls):
        h = self.ghep_cfg["he_so"]
        return 1 / (1 + np.exp(-(h["w_logit_anh"] * logit(p_anh) + h["w_logit_lam_sang"] * logit(p_ls) + h["b"])))

    def ket_luan(self, p, loai: str):
        """loai = 'lam_sang' hoac 'ghep'. True = diem >= nguong (bao nguy co, can kham/doc phim tiep)."""
        return np.asarray(p) >= self.nguong[loai]

    def du_doan(self, lam_sang: dict, anh=None) -> dict:
        """Mot luot kham. lam_sang: dict {ten_cot: gia_tri_tho} (thieu cot = de trong).
        anh: None, duong dan file, hoac PIL.Image. Tra ve dict JSON-hoa duoc (xem MO_TA_API.md)."""
        p_ls = float(self.diem_lam_sang(pd.DataFrame([lam_sang]))[0])
        r = dict(nhan="ketqua", p_lam_sang=round(p_ls, 6), nguong_lam_sang=self.nguong["lam_sang"],
                 bao_nguy_co_lam_sang=bool(p_ls >= self.nguong["lam_sang"]), co_anh=anh is not None)
        if anh is not None:
            p_anh = float(self.diem_anh([self.doc_anh(anh)])[0]); p = float(self.ghep(p_anh, p_ls))
            r.update(p_anh=round(p_anh, 6), p_ghep=round(p, 6), nguong_ghep=self.nguong["ghep"], bao_nguy_co_ghep=bool(p >= self.nguong["ghep"]))
        r["ket_luan_cuoi"] = r["bao_nguy_co_ghep"] if anh is not None else r["bao_nguy_co_lam_sang"]
        return r
