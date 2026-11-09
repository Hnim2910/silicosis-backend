"""Service dự đoán nội bộ cho backend Spring (model New Way, nhãn ketqua). Chạy:
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
from .new_way import MODEL_NAME, NewWay
from .schemas import ErrorResponse, HealthResponse, PredictResult

log = logging.getLogger("predict")
ERR = {400: {"model": ErrorResponse}, 422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}}


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    Image.MAX_IMAGE_PIXELS = settings.max_image_pixels
    max_bytes = settings.max_upload_mb * 1024 * 1024
    max_json = settings.max_clinical_kb * 1024

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.model = None
        start = time.perf_counter()
        try:
            app.state.model = NewWay(settings.new_way_dir, settings.device, settings.preload_image_model)
            log.info("New Way loaded on %s, version %s in %.0f ms", app.state.model.device,
                     app.state.model.model_version, (time.perf_counter() - start) * 1000)
        except Exception as e:  # service vẫn chạy, các endpoint dự đoán trả 503
            log.error("Model load failed: %s", type(e).__name__)
        yield

    app = FastAPI(title="Silicosis predict — New Way (thử nghiệm)", lifespan=lifespan)

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

    def model(request: Request) -> NewWay:
        m = request.app.state.model
        if m is None:
            raise PredictError(503, "MODEL_NOT_LOADED", "Model chưa được nạp.")
        return m

    def parse_clinical(raw: bytes | str, m: NewWay | None) -> dict:
        if len(raw) > max_json:
            raise invalid_input(message=f"Dữ liệu lâm sàng vượt quá {settings.max_clinical_kb} KB.")
        try:
            data = json.loads(raw) if raw else {}
        except (ValueError, UnicodeDecodeError):
            raise invalid_input(message="Dữ liệu lâm sàng không phải JSON.")
        if not isinstance(data, dict):
            raise invalid_input(message="Dữ liệu lâm sàng phải là một object JSON {tên_cột: giá_trị}.")
        if m is None:
            raise PredictError(503, "MODEL_NOT_LOADED", "Model chưa được nạp.")
        clean, errs = m.schema.clean(data)
        if errs:
            raise invalid_input(errs)
        return clean

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

    @app.get("/health", response_model=HealthResponse)
    def health(request: Request):
        m = request.app.state.model
        return HealthResponse(status="ok", model_loaded=m is not None,
                              image_model_loaded=bool(m and m.image_loaded),
                              device=m.device if m else None,
                              model_version=m.model_version if m else None,
                              model_name=MODEL_NAME if m else None)

    @app.get("/schema")
    def schema(request: Request):
        """Danh sách cột và mức hợp lệ (để đối chiếu; web có bảng riêng)."""
        m = model(request)
        return {"kind": m.schema.kind, "levels": m.schema.levels}

    def tag(response: Response, m: NewWay):
        # Body giữ nguyên dict du_doan(); phiên bản model đi kèm qua header.
        response.headers["X-Model-Version"] = m.model_version
        response.headers["X-Model-Name"] = "New Way ketqua"

    @app.post("/predict/clinical", response_model=PredictResult, responses=ERR)
    async def predict_clinical(request: Request, response: Response):
        """Chỉ lâm sàng. Body: JSON {tên_cột: giá_trị}, cột theo MO_TA_API.md; thiếu/rỗng/null = bỏ trống."""
        with timed("clinical"):
            raw = await request.body()
            m = request.app.state.model
            clean = parse_clinical(raw, m)
            tag(response, m)
            return await run_in_threadpool(m.predict, clean, None)

    @app.post("/predict/clinical-image", response_model=PredictResult, responses=ERR)
    def predict_clinical_image(request: Request, response: Response, lam_sang: str = Form("{}"),
                               file: UploadFile | None = File(None)):
        """Lâm sàng + phim. Multipart: phần `lam_sang` (chuỗi JSON) + phần `file` (PNG/JPEG)."""
        with timed("clinical-image"):
            tag(response, model(request))
            m = request.app.state.model
            clean = parse_clinical(lam_sang, m)
            if file is None:
                raise invalid_image()
            data = file.file.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise PredictError(400, "FILE_TOO_LARGE", f"Ảnh vượt quá {settings.max_upload_mb} MB.")
            if not data:
                raise invalid_image()
            img = preprocess.to_gray(preprocess.decode(data, settings.max_image_pixels))
            del data
            return model(request).predict(clean, img)

    @app.post("/predict", include_in_schema=False)
    def predict_removed():
        """Route model cũ (BioViL-T Set A, bnn) đã ngắt."""
        raise PredictError(410, "ENDPOINT_REMOVED",
                           "Model cũ đã ngắt. Dùng /predict/clinical hoặc /predict/clinical-image.")

    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
app = create_app()
