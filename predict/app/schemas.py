from typing import Literal

from pydantic import BaseModel, ConfigDict

DISCLAIMER = "Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ."


class PredictResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    probability: float
    label: Literal["Co", "Khong"]
    threshold: float
    model_version: str
    model_name: str
    experimental: bool = True
    disclaimer: str = DISCLAIMER


class HealthResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    status: str
    model_loaded: bool
    device: str | None
    model_version: str | None


class ErrorResponse(BaseModel):
    code: str
    message: str
