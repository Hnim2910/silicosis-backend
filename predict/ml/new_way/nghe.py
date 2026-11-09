"""Cap nghe 10 muc cho 4 cot nghe (cviec, pxuong, cviec1, cviec2) -> cot <ten>_cap (1..10, chu trong = NaN).
Phan 1 duoi day SAO NGUYEN VAN tu Fusion/DO-AN-TOT-NGHIEP-E22-T02/scripts/patient_identity.py (dong 43-120),
giong scripts/buoc1_chuan_bi.py. Khong sua quy tac o day neu khong hoc lai mo hinh."""
import numpy as np
import re
import unicodedata
import pandas as pd

_unidecode = None  # CO Y tat: luc hoc mo hinh (buoc1) khong co unidecode -> dung nhanh NFKD de cap nghe trung khit

JOB_COLS = ["cviec", "pxuong", "cviec1", "cviec2"]
LV9_ID = 8  # lv9: Không đi làm / không xác định

level_to_id = {f"lv{i}": i - 1 for i in range(1, 10)}
level_to_id["lv10"] = 9

def _remove_vietnamese_accents(text: str) -> str:
    if _unidecode is not None:
        return _unidecode(text)
    normalized = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in normalized if not unicodedata.combining(ch))

def clean_job_text(text) -> str:
    if text is None or (isinstance(text, float) and pd.isna(text)):
        return ""
    s = str(text).strip().lower()
    if s in {"", "nan", "none", "<na>"}:
        return ""
    s = re.sub(r"\s+", " ", s)
    s = s.replace("đ", "d").replace("Đ", "d")
    s = _remove_vietnamese_accents(s)
    s = re.sub(r"\bsau chua\b", "sua chua", s)
    s = s.replace("x-ray", "x ray").replace("x ray", "x ray")
    s = re.sub(r"[^a-z\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s

def _has_phrase(text: str, phrase: str) -> bool:
    if not phrase:
        return False
    if " " in phrase:
        return phrase in text
    return re.search(rf"\b{re.escape(phrase)}\b", text) is not None

def _match_keywords(text: str, keywords, short_boundary=None) -> bool:
    short_boundary = short_boundary or set()
    for kw in sorted(keywords, key=len, reverse=True):
        if kw in short_boundary:
            if re.search(rf"\b{re.escape(kw)}\b", text):
                return True
        elif _has_phrase(text, kw):
            return True
    return False

def classify_job_10_levels(clean_text: str) -> str:
    t = clean_text or ""
    if t == "" or t in {"khong", "nan", "o nha", "tu do"}:
        return "lv9"
    lv1_keywords = ["luyen", "duc", "nau", "nan", "no min", "ren", "dot", "chung cat", "khai thac", "cat", "nung", "coc", "thep", "gang", "silic", "da", "than", "lo", "mo", "kim loai", "xi mang", "clinker", "nghien", "amiang"]
    if _match_keywords(t, lv1_keywords): return "lv1"
    lv2_keywords = ["gia cong", "xay", "boc", "boc vac", "khoan", "mai", "pha", "trang men", "trang tri", "tron", "cong truong", "phu ho", "xay dung", "dap", "gach", "gach men", "ceramic", "tho"]
    if _match_keywords(t, lv2_keywords): return "lv2"
    lv3_keywords = ["phoi lieu", "tao hinh", "sua", "sua chua", "bao tri", "bao duong", "dien", "han", "ren", "co khi", "phay"]
    if _match_keywords(t, lv3_keywords): return "lv3"
    lv4_keywords = ["can", "dong", "det", "phan loai", "xuat vo", "soi"]
    if _match_keywords(t, lv4_keywords): return "lv4"
    if "bao ve" not in t and _has_phrase(t, "bao"): return "lv4"
    lv5_short = {"cn", "lam", "px", "vs", "con", "sx"}
    lv5_keywords = ["cong nhan", "lao dong", "san xuat", "xuat", "nguyen lieu", "nhap lieu", "nap lieu", "san pham", "say", "tron", "ve sinh", "xa", "truc", "bao ve", "cong ty", "cty", "kcn", "cong nghiep", "lien doanh", "nha may", "xi nghiep", "lap rap", "day chuyen", *lv5_short]
    if _match_keywords(t, lv5_keywords, short_boundary=lv5_short): return "lv5"
    lv6_short = {"vh", "dieu", "in", "ky"}
    lv6_keywords = ["chay", "dieu khien", "dung", "ky thuat", "lai", "may", "xe", "truc", "xu ly", "van chuyen", "van tai", *lv6_short]
    if _match_keywords(t, lv6_keywords, short_boundary=lv6_short): return "lv6"
    lv7_short = {"xn"}
    lv7_keywords = ["thi nghiem", "xet nghiem", "dem", "kiem", "ktra", "lay mau", "lab", "qc", "kcs", "x ray", *lv7_short]
    if _match_keywords(t, lv7_keywords, short_boundary=lv7_short): return "lv7"
    lv8_keywords = ["giam sat", "ke hoach", "kinh doanh", "kho vat tu", "quan ly", "ql", "qly", "giam doc", "nhan vien", "nv", "pho", "phong", "quan", "quat", "quy cach", "so lieu", "truong", "chi huy", "giay to", "thong", "cung cap", "ban", "buon ban", "chup anh", "chuyen vien", "di hoc", "bo doi", "quan su", "du lich", "ks", "nuoi", "nong nghiep", "gia suc", "gia cam", "noi", "quang cao", "sales", "shop", "giay", "quan ao", "thuc pham", "tiep thi", "bep", "nau an", "tap vu", "y te", "thiet ke", "ke toan"]
    if _match_keywords(t, lv8_keywords, short_boundary={"ql", "nv", "ks"}): return "lv8"
    return "lv10"


def them_cap_nghe(df: pd.DataFrame) -> pd.DataFrame:
    """Tra ve ban sao df co them cviec_cap, pxuong_cap, cviec1_cap, cviec2_cap (giong buoc1_chuan_bi.py)."""
    df = df.copy()
    for c in JOB_COLS:
        s = df[c] if c in df.columns else pd.Series([np.nan] * len(df), index=df.index)
        txt = s.map(clean_job_text)
        lv = txt.map(lambda t: classify_job_10_levels(t) if t != "" else np.nan)
        df[f"{c}_cap"] = lv.map(lambda v: level_to_id[v] + 1 if isinstance(v, str) else np.nan)
    return df
