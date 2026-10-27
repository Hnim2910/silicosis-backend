"""Service dự đoán nội bộ cho backend Spring. Chạy:
uvicorn app.main:app --host 127.0.0.1 --port 8001
"""
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from PIL import Image

from . import preprocess
from .config import Settings
from .errors import PredictError, invalid_image
from .model import MODEL_NAME, Predictor
from .schemas import ErrorResponse, HealthResponse, PredictResponse

log = logging.getLogger("predict")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    Image.MAX_IMAGE_PIXELS = settings.max_image_pixels
    max_bytes = settings.max_upload_mb * 1024 * 1024

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.predictor = None
        try:
            app.state.predictor = Predictor(settings.model_path, settings.device, settings.threshold)
            log.info("Model loaded on %s, version %s",
                     app.state.predictor.device, app.state.predictor.model_version)
        except Exception as e:  # service vẫn chạy, /predict trả 503
            log.error("Model load failed: %s", type(e).__name__)
        yield

    app = FastAPI(title="Silicosis predict (experimental)", lifespan=lifespan)

    @app.exception_handler(PredictError)
    async def predict_error(_: Request, e: PredictError):
        return JSONResponse(status_code=e.status, content={"code": e.code, "message": e.message})

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, __: RequestValidationError):
        e = invalid_image()
        return JSONResponse(status_code=e.status, content={"code": e.code, "message": e.message})

    @app.get("/health", response_model=HealthResponse)
    def health(request: Request):
        p = request.app.state.predictor
        return HealthResponse(status="ok", model_loaded=p is not None,
                              device=str(p.device) if p else None,
                              model_version=p.model_version if p else None)

    @app.post("/predict", response_model=PredictResponse,
              responses={400: {"model": ErrorResponse}, 503: {"model": ErrorResponse}})
    def predict(request: Request, file: UploadFile | None = File(None)):
        start = time.perf_counter()
        code = "OK"
        try:
            if file is None:
                raise invalid_image()
            data = file.file.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise PredictError(400, "FILE_TOO_LARGE",
                                   f"Ảnh vượt quá {settings.max_upload_mb} MB.")
            if not data:
                raise invalid_image()
            img = preprocess.decode(data, settings.max_image_pixels)
            p = request.app.state.predictor
            if p is None:
                raise PredictError(503, "MODEL_NOT_LOADED", "Model chưa được nạp.")
            prob = p.probability(preprocess.to_tensor(img))
            return PredictResponse(probability=prob,
                                   label="Co" if prob >= p.threshold else "Khong",
                                   threshold=p.threshold, model_version=p.model_version,
                                   model_name=MODEL_NAME)
        except PredictError as e:
            code = e.code
            raise
        finally:
            log.info("predict %s in %.0f ms", code, (time.perf_counter() - start) * 1000)

    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
app = create_app()
