from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Cấu hình đọc từ biến môi trường hoặc predict/.env."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore",
                                      protected_namespaces=())

    model_path: str = ""
    threshold: float = 0.403
    device: str = "auto"  # auto | cpu | cuda
    max_upload_mb: int = 10
    max_image_pixels: int = 50_000_000
