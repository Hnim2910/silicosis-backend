from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Cấu hình đọc từ biến môi trường hoặc predict/.env."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore",
                                      protected_namespaces=())

    # Thư mục gói New Way (chép nguyên New Way/xuat_app). Đường dẫn tương đối tính từ thư mục predict/.
    new_way_dir: str = "ml/new_way"
    # Nạp luôn 3 mạng ảnh lúc khởi động (~1.5 GB RAM). false = nạp ở lần gửi ảnh đầu tiên.
    preload_image_model: bool = True
    device: str = "auto"  # auto | cpu | cuda
    # Mô hình PG (chép nguyên PAG/xuat_app_pg). Trọng số trong <pg_dir>/mo_hinh_PG_web (không có trong git).
    pg_dir: str = "ml/pg"
    # File BioViL-T gốc (chỉ cần khi PG nhận ảnh). Tương đối tính từ predict/.
    biovil_path: str = "ml/pg/biovil_t_image_model_proj_size_128.pt"
    # true: nạp phần xử lý ảnh PG (PSPNet + BioViL-T + OCR) lúc khởi động; false: nạp ở lần gửi ảnh đầu tiên.
    preload_pg_image: bool = True
    max_upload_mb: int = 10
    max_image_pixels: int = 50_000_000
    max_clinical_kb: int = 64

    # Model cũ (BioViL-T Set A, nhãn bnn) — đã ngắt khỏi API, giữ biến để .env cũ không lỗi.
    model_path: str = ""
    threshold: float = 0.403
