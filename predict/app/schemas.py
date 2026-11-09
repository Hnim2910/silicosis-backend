from pydantic import BaseModel, ConfigDict

DISCLAIMER = "Công cụ hỗ trợ sàng lọc, không thay thế chẩn đoán của bác sĩ."


class PredictResult(BaseModel):
    """Đúng dict trả về từ MoHinh.du_doan() (xem ml/new_way/MO_TA_API.md)."""

    nhan: str
    p_lam_sang: float
    nguong_lam_sang: float
    bao_nguy_co_lam_sang: bool
    co_anh: bool
    p_anh: float | None = None
    p_ghep: float | None = None
    nguong_ghep: float | None = None
    bao_nguy_co_ghep: bool | None = None
    ket_luan_cuoi: bool


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
