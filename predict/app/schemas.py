from pydantic import BaseModel, ConfigDict

class HealthResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    status: str
    model_loaded: bool
    image_model_loaded: bool
    device: str | None
    model_version: str | None
    model_name: str | None


class FieldError(BaseModel):
    field: str
    error: str
    allowed: list[str] | None = None
    max_length: int | None = None


class ErrorResponse(BaseModel):
    code: str
    message: str
    errors: list[FieldError] | None = None


class PGResult(BaseModel):
    """Kết quả POST /predict/pg (MoHinhPG.du_doan() + tên/phiên bản). KHÔNG có ngưỡng, không có nhãn dương/âm."""

    model_config = ConfigDict(protected_namespaces=())

    nhan: str
    xac_suat: float
    xac_suat_theo_fold: list[float]
    co_anh: bool
    tinh: str
    chu_y_vung: dict[str, float] | None = None
    qa_anh: dict | None = None
    canh_bao: list[str]
    canh_bao_dau_vao: list[FieldError]
    disclaimer: str
    experimental: bool
    mo_hinh: str
    model_name: str
    model_version: str

