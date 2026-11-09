class PredictError(Exception):
    """Lỗi trả về client dạng {code, message[, errors]}."""

    def __init__(self, status: int, code: str, message: str, errors: list | None = None):
        super().__init__(code)
        self.status = status
        self.code = code
        self.message = message
        self.errors = errors


def invalid_image() -> PredictError:
    return PredictError(400, "INVALID_IMAGE", "Không đọc được ảnh hoặc ảnh bị hỏng.")


def invalid_input(errors: list | None = None, message: str = "Dữ liệu lâm sàng không hợp lệ.") -> PredictError:
    return PredictError(422, "INVALID_INPUT", message, errors)
