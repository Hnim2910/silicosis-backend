"""Service dự đoán nội bộ cho backend Spring (mô hình PG, nhãn ketqua). Chạy:
uvicorn app.main:app --host 127.0.0.1 --port 8001
"""
import json
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, Request, Response, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from PIL import Image

from . import preprocess
from .config import Settings
from .errors import PredictError, invalid_image, invalid_input
from .pg import MODEL_NAME, PG
from .schemas import ErrorResponse, HealthResponse, PGResult

log = logging.getLogger("predict")
ERR = {400: {"model": ErrorResponse}, 422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}}


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    Image.MAX_IMAGE_PIXELS = settings.max_image_pixels
    max_bytes = settings.max_upload_mb * 1024 * 1024
    max_json = settings.max_clinical_kb * 1024

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.pg = None
        start = time.perf_counter()
        try:
            app.state.pg = PG(settings.pg_dir, settings.biovil_path, settings.device)
            log.info("PG loaded on %s, version %s in %.0f ms", app.state.pg.device,
                     app.state.pg.model_version, (time.perf_counter() - start) * 1000)
        except Exception as e:
            log.error("PG load failed: %s", type(e).__name__)
        if app.state.pg is not None and settings.preload_pg_image:
            try:  # lỗi phần ảnh không chặn PG chỉ lâm sàng; lần gửi ảnh sau sẽ thử nạp lại
                await run_in_threadpool(app.state.pg.load_image_model)
            except Exception as e:
                log.error("PG image model load failed: %s", type(e).__name__)
        yield

    app = FastAPI(title="Silicosis predict — PG (thử nghiệm)", lifespan=lifespan)

    @app.exception_handler(PredictError)
    async def predict_error(_: Request, e: PredictError):
        body = {"code": e.code, "message": e.message}
        if e.errors:
            body["errors"] = e.errors
        return JSONResponse(status_code=e.status, content=body)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, __: RequestValidationError):
        e = invalid_input(message="Yêu cầu không đúng định dạng.")
        return JSONResponse(status_code=e.status, content={"code": e.code, "message": e.message})

    def json_object(raw: bytes | str) -> dict:
        if len(raw) > max_json:
            raise invalid_input(message=f"Dữ liệu lâm sàng vượt quá {settings.max_clinical_kb} KB.")
        try:
            data = json.loads(raw) if raw else {}
        except (ValueError, UnicodeDecodeError):
            raise invalid_input(message="Dữ liệu lâm sàng không phải JSON.")
        if not isinstance(data, dict):
            raise invalid_input(message="Dữ liệu lâm sàng phải là một object JSON {tên_cột: giá_trị}.")
        return data

    def timed(name: str):
        class _T:
            def __enter__(s):
                s.t, s.code = time.perf_counter(), "OK"
                return s

            def __exit__(s, et, ev, tb):
                if isinstance(ev, PredictError):
                    s.code = ev.code
                elif ev is not None:
                    s.code = "INTERNAL"
                log.info("%s %s in %.0f ms", name, s.code, (time.perf_counter() - s.t) * 1000)
                return False
        return _T()

    def pg_model(request: Request) -> PG:
        m = request.app.state.pg
        if m is None:
            raise PredictError(503, "MODEL_NOT_LOADED", "Mô hình PG chưa được nạp.")
        return m

    @app.get("/health", response_model=HealthResponse)
    def health(request: Request):
        m = request.app.state.pg
        return HealthResponse(status="ok", model_loaded=m is not None, image_model_loaded=bool(m and m.image_loaded),
                              device=m.device if m else None, model_version=m.model_version if m else None,
                              model_name=MODEL_NAME if m else None)

    @app.get("/schema")
    def schema(request: Request):
        """Cột, kiểu và mức mô hình PG nhận ra; `tinh` bắt buộc. `derived` = cột mô hình tự tính (gửi lên bị bỏ qua)."""
        m = pg_model(request)
        return {"required": ["tinh"], "tinh": m.schema.tinh, "kind": m.schema.kind, "levels": m.schema.levels,
                "derived": sorted(m.schema.derived)}

    @app.post("/predict/pg", response_model=PGResult, responses=ERR)
    def predict_pg(request: Request, response: Response, lam_sang: str = Form("{}"),
                   file: UploadFile | None = File(None)):
        """PG: lâm sàng (bắt buộc `tinh`) + phim tuỳ chọn. Multipart: `lam_sang` (chuỗi JSON) + `file` (PNG/JPEG, có thể bỏ).
        Trả xác suất đã hiệu chỉnh, KHÔNG có ngưỡng / kết luận dương-âm."""
        with timed("pg"):
            m = pg_model(request)
            response.headers["X-Model-Version"] = m.model_version
            response.headers["X-Model-Name"] = "PG ketqua"
            clean, errs, warns = m.schema.clean(json_object(lam_sang))
            if errs:
                raise invalid_input(errs)
            img = None
            if file is not None:
                data = file.file.read(max_bytes + 1)
                if len(data) > max_bytes:
                    raise PredictError(400, "FILE_TOO_LARGE", f"Ảnh vượt quá {settings.max_upload_mb} MB.")
                if not data:
                    raise invalid_image()
                img = preprocess.decode(data, settings.max_image_pixels)  # 16-bit: du_doan_pg tự co giãn như lúc học
                del data
                try:
                    m.load_image_model()
                except Exception as e:
                    log.error("PG image model load failed: %s", type(e).__name__)
                    raise PredictError(503, "MODEL_NOT_LOADED", "Phần xử lý ảnh của mô hình PG chưa được nạp.")
            return m.predict(clean, img, warns)

    def removed():
        raise PredictError(410, "ENDPOINT_REMOVED", "Mô hình cũ đã ngắt. Dùng /predict/pg.")

    # Model cũ (BioViL-T Set A, bnn) và New Way (/predict/clinical, /predict/clinical-image) đã bỏ.
    for path in ("/predict", "/predict/clinical", "/predict/clinical-image", "/health/pg", "/schema/pg"):
        app.add_api_route(path, removed, methods=["GET", "POST"], include_in_schema=False)

    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
app = create_app()
