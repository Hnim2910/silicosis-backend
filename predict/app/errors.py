class PredictError(Exception):
    """Lỗi trả về client dạng {code, message}."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(code)
        self.status = status
        self.code = code
        self.message = message


def invalid_image() -> PredictError:
    return PredictError(400, "INVALID_IMAGE", "Không đọc được ảnh hoặc ảnh bị hỏng.")
