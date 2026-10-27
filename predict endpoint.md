# Prompt: service dự đoán (FastAPI) + nối với backend

Hai bước, đưa lần lượt cho cùng một phiên Claude Code mở ở `E:\Hoc hanh\silicosis-backend`. Xong bước 1 và chạy ổn thì mới đưa bước 2.

Các thông tin về model dưới đây đã được kiểm tra trên máy ngày 2026-10-18:
- checkpoint nạp `strict=True` khớp toàn bộ key mà không cần file pretrain;
- `feature_size` = 512;
- sha256 của checkpoint bắt đầu bằng `c63f9782b7d1`;
- ảnh huấn luyện 256×256, chế độ `L`;
- phiên bản các gói lấy từ `pip show`.

---

## Bước 1 — Service FastAPI trong `predict/`

```text
Tạo một service FastAPI trong thư mục mới `E:\Hoc hanh\silicosis-backend\predict\`. Thư mục này nằm trong repo backend, không phải repo riêng. Ở bước này chưa sửa code Java và không đụng `sillicosis-web`. Trả lời tôi bằng tiếng Việt.

## Model
- Checkpoint: `e:\Hoc hanh\ĐÒ ÁN\Data DGCNN\Finetune 4\best_model_biovilt_finetuned.pth`, là state_dict của cả model, khoảng 110 MB. KHÔNG chép file vào repo; trỏ tới nó bằng `MODEL_PATH`.
- Code huấn luyện gốc: `e:\Hoc hanh\ĐÒ ÁN\Data DGCNN\Finetune 4\finetune_biovilt_setA.py`. Bước resize về 256 nằm ở `e:\Hoc hanh\ĐÒ ÁN\Data DGCNN\Finetune\resize_setA.py`. Đọc cả hai trước và tái tạo ĐÚNG kiến trúc và tiền xử lý từ đó, đừng tự đoán.
- Kiến trúc: `BioViLTClassifier` gồm:
  - backbone `health_multimodal.image.model.model.ImageModel(img_encoder_type=ImageEncoderType.RESNET50_MULTI_IMAGE, joint_feature_size=128)`;
  - head `Sequential(Dropout(0.3), Linear(backbone.feature_size, 2))`. `feature_size` = 512; comment "~2048-d" trong script gốc là sai;
  - forward: `backbone(x).img_embedding.flatten(1)` rồi đưa qua head.
  Lớp 1 = "Co" (nhãn `bnn`, tập Silicodata Set A).
- Dựng backbone KHÔNG cần file pretrain (`pretrained_model_path=None`). Encoder của hi-ml dựng với `pretrained=False`, nên không tải gì từ mạng.
- Nạp checkpoint: `torch.load(MODEL_PATH, map_location=device, weights_only=True)`, rồi `load_state_dict(..., strict=True)`.
  - Tôi đã thử: khớp toàn bộ key.
  - Nếu strict báo lệch key thì dừng và báo tôi, không được nới strict để chạy cho qua.
- Tiền xử lý PHẢI giống lúc huấn luyện. Chuỗi gốc: `resize_setA.py` → lưu JPEG → dataset `.convert("L")` → `eval_tf`. Các bước:
  1. Mở ảnh bằng PIL.
     - Nếu ảnh 16-bit (mode `I;16`/`I`), co giãn min–max về 0–255 rồi mới chuyển sang `L`. `convert("L")` trực tiếp trên ảnh 16-bit sẽ cắt giá trị, cho ảnh gần như trắng.
     - Các mode còn lại dùng `convert("L")`.
  2. `img.resize((256, 256), Image.LANCZOS)`: ép đúng 256×256, không giữ tỉ lệ, giống `resize_setA.py`. Ảnh gốc Set A là 1024×1024 nên lúc huấn luyện không bị méo. Ảnh không vuông vẫn ép như vậy; ghi điều này trong README là một giới hạn. Không cần mô phỏng bước lưu JPEG q95.
  3. `Resize(512)` (torchvision, nội suy mặc định).
  4. `CenterCrop(448)`.
  5. `ToTensor()` (giá trị 0–1, KHÔNG normalize ImageNet).
  6. `ExpandChannels()` lấy từ `health_multimodal.image.data.transforms`.
  7. `unsqueeze(0)` để có tensor [1,3,448,448].
- Xác suất = `softmax(logits, dim=1)[:, 1]`. Ngưỡng mặc định 0.403. Ghi trong README:
  - ngưỡng này chọn theo Youden trên chính tập test của lần fine-tune (339 ảnh, 60% là "Co"), nên kết quả lạc quan;
  - tỉ lệ dương tính ngoài thực tế thấp hơn nhiều, nên tỉ lệ đúng khi báo "Co" sẽ thấp hơn số báo cáo (AUC 0.902, Sens 0.906, Spec 0.781).
- Nạp model một lần lúc khởi động (lifespan), gọi `eval()`, dùng `torch.inference_mode()`.
  - Chọn thiết bị theo `DEVICE`: `auto` dùng cuda nếu có, không thì cpu.
  - Sau khi nạp, chạy warm-up một lần với tensor 0 để request đầu tiên không chậm.

## API
- `GET /health` → `{status, model_loaded, device, model_version}`.
- `POST /predict` (multipart, field `file`) → `{probability, label, threshold, model_version, model_name, experimental, disclaimer}`:
  - `label` là `"Co"` hoặc `"Khong"`, viết ASCII không dấu, giống nhãn lúc huấn luyện;
  - `model_name` = `"BioViL-T fine-tune Set A (bnn)"`;
  - `experimental` luôn là `true`;
  - `disclaimer` = `"Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ."`.
- Chỉ nhận JPEG và PNG, tối đa 10 MB.
  - Kiểm tra kích thước trước khi giải mã.
  - Định dạng xác định bằng `Image.open(...).format` thuộc {"JPEG", "PNG"}, không tin content-type hay tên tệp. Gọi `img.load()` để bắt ảnh hỏng.
  - Đặt `Image.MAX_IMAGE_PIXELS` ở mức hợp lý (ví dụ 50 triệu) để chặn ảnh bom giải nén.
- Lỗi trả 400 với `{code, message}`:
  - `FILE_TOO_LARGE`: vượt giới hạn dung lượng;
  - `UNSUPPORTED_FORMAT`: mở được nhưng không phải JPEG/PNG;
  - `INVALID_IMAGE`: không mở được hoặc ảnh hỏng;
  - thiếu field `file` cũng trả 400 `INVALID_IMAGE`.
  Model chưa nạp được → 503 `MODEL_NOT_LOADED`. DICOM ngoài phạm vi.
- `model_version` = 12 ký tự đầu sha256 của file checkpoint, tính lúc khởi động. Với file hiện tại, giá trị phải là `c63f9782b7d1`.
- Service chỉ dùng nội bộ để backend Spring gọi (`http://localhost:8001/predict`). Chỉ bind `127.0.0.1`, không bật CORS, không xác thực ở bước này.

## Riêng tư
- Không lưu ảnh xuống đĩa. Không log tên tệp hay nội dung ảnh, vì ảnh có thể chứa tên/ngày in trên phim. Chỉ log thời gian xử lý và mã lỗi.

## Cấu hình & cấu trúc
- Biến môi trường:
  - `MODEL_PATH`;
  - `THRESHOLD` (mặc định 0.403);
  - `DEVICE` (`auto`/`cpu`/`cuda`);
  - `MAX_UPLOAD_MB` (mặc định 10).
  Có `predict/.env.example`; không commit `predict/.env`.
- Cấu trúc gợi ý:
  - `predict/app/main.py`;
  - `predict/app/model.py` (dựng, nạp và predict);
  - `predict/app/preprocess.py`;
  - `predict/app/schemas.py`;
  - `predict/tests/`, `predict/requirements.txt`, `predict/README.md`.
- Thêm vào `.gitignore` gốc của repo: `predict/**/*.pth`, `predict/**/*.pt`, `predict/.venv/`, `predict/**/__pycache__/`, `predict/.pytest_cache/`. File `.gitignore` đã có sẵn `.env` và `.env.*` (trừ `.env.example`), nên `predict/.env` đã được bỏ qua; chỉ cần kiểm tra lại.
- Service có venv Python riêng trong `predict/.venv`, không ảnh hưởng `./mvnw verify`.
- requirements.txt ghim theo môi trường đang có. Đã kiểm tra bằng `pip show`, chạy lại `pip show` để chắc chắn trước khi ghi:
  - Python 3.13.2;
  - torch 2.6.0+cu124, torchvision 0.21.0+cu124: ghi kèm `--extra-index-url https://download.pytorch.org/whl/cu124`;
  - hi-ml-multimodal 0.2.2;
  - fastapi 0.121.3, uvicorn 0.38.0, python-multipart 0.0.27, pillow 11.1.0, pydantic-settings 2.14.0;
  - pytest 8.3.4, httpx 0.28.1.
  Khi import hi-ml có thể hiện cảnh báo FutureWarning của timm; bỏ qua được.

## Kiểm thử
- pytest:
  1. tiền xử lý ra tensor [1,3,448,448], giá trị trong [0,1]; ảnh không vuông và ảnh PNG 16-bit giả cũng ra đúng shape;
  2. `/predict` với ảnh giả tạo bằng PIL trả 200 và probability ∈ [0,1];
  3. file không phải ảnh → 400 `INVALID_IMAGE`;
  4. ảnh GIF/BMP hợp lệ → 400 `UNSUPPORTED_FORMAT`;
  5. file vượt giới hạn → 400 `FILE_TOO_LARGE`;
  6. `model_version` = `c63f9782b7d1`.
  Test nào cần checkpoint thật thì `skip` nếu không có file.
- Chạy thử: `uvicorn app.main:app --host 127.0.0.1 --port 8001`, gọi `/health` và `/predict` bằng một ảnh giả. KHÔNG dùng ảnh bệnh nhân thật trong test hay ví dụ README.
- Báo lại:
  - kết quả pytest;
  - thời gian dự đoán một ảnh trên cpu và cuda, không tính warm-up;
  - load_state_dict strict có khớp không.

Không chạy git add/commit/branch; tôi tự làm. Khi xong, liệt kê các file đã tạo/sửa.
```

---

## Bước 2 — Nối backend với service

```text
Trong `E:\Hoc hanh\silicosis-backend` (Spring Boot), nối với service `predict/`:
- endpoint `POST http://localhost:8001/predict`, multipart field `file`;
- trả `{probability, label ("Co"/"Khong"), threshold, model_version, model_name, experimental, disclaimer}`.
Trả lời tiếng Việt. Đọc `docs/api-visits.md` và code visits/images hiện có trước khi làm. Lưu ý code hiện tại:
- `VisitRequest` đã có `imageId` khi tạo, và lượt khám có thể chỉ có ảnh;
- gắn ảnh nằm ở `VisitService.attach`;
- file ảnh đọc bằng `ImageStorage.read(image.getStorageKey())`. Ảnh đã được mã hoá lại (PNG/JPEG, không còn metadata), gửi nguyên byte đó sang service.

1. Bảng mới `predictions`, tạo bằng migration Flyway mới `V2__predictions.sql` (KHÔNG sửa V1). Các cột:
   - id;
   - visit_id (FK, ON DELETE CASCADE), image_id (FK `images`);
   - probability numeric(5,4) null, label varchar(10) null, threshold numeric(5,4) null;
   - model_name, model_version (null được khi FAILED);
   - status (SUCCESS/FAILED, có CHECK), error_code (null được);
   - created_at.
   Thêm index `(visit_id, created_at desc)`. Một lượt khám có thể có nhiều dự đoán (chạy lại khi đổi model).
   - `latestPrediction` = dự đoán mới nhất của lượt khám có `image_id` trùng ảnh đang gắn.
   - Gỡ ảnh thì `latestPrediction` = null; dự đoán cũ vẫn giữ trong DB.

2. Gọi service khi tạo lượt khám có `imageId` và khi gắn ảnh bằng `PUT /api/visits/{id}/image`.
   - Gọi đồng bộ, nhưng KHÔNG gọi bên trong transaction DB. Lưu lượt khám hoặc liên kết ảnh và commit trước, rồi mới gọi service. Kết quả lưu trong một transaction riêng, sau đó trả response có `latestPrediction`. Có thể gọi từ controller sau khi service trả về, hoặc dùng `TransactionTemplate`.
   - HTTP client: `RestClient` + `JdkClientHttpRequestFactory`, không thêm thư viện.
   - Cấu hình `predict.base-url` (mặc định `http://localhost:8001`), `predict.timeout` (mặc định 30s) và `predict.enabled` (mặc định true), đặt vào `AppProperties`/`application.yaml` và `.env.example` như các cấu hình hiện có.
   - Nếu service lỗi hoặc không chạy, lượt khám VẪN được lưu và không trả 5xx cho client. Ghi prediction status=FAILED kèm error_code:
     - `PREDICT_UNAVAILABLE`: không kết nối được, hoặc service trả 5xx/503;
     - `PREDICT_TIMEOUT`: quá thời gian chờ;
     - `PREDICT_BAD_RESPONSE`: service trả 4xx hoặc JSON sai, ví dụ thiếu probability hay probability ngoài [0,1].
   - `predict.enabled=false` thì không gọi service và không ghi gì.

3. Thêm endpoint `POST /api/visits/{id}/predictions` để chạy lại dự đoán.
   - Trả 201 `PredictionResponse`. Lỗi của service vẫn trả 201, kèm `status=FAILED`.
   - Lượt khám không có ảnh → 400 `VISIT_HAS_NO_IMAGE`.
   - Trả kèm dự đoán mới nhất trong `GET /api/visits/{id}` và `GET /api/patients/{patientId}/visits`, ở trường `latestPrediction` (null nếu chưa có). Danh sách lấy dự đoán của cả trang bằng một truy vấn, không truy vấn từng dòng.
   - Quyền như các endpoint visits hiện có.

4. Lượt khám không có ảnh: không gọi service, `latestPrediction = null`. Model hiện chỉ dùng ảnh, chưa dùng chỉ số lâm sàng.

5. `PredictionResponse`:
   - gồm `id`, `status`, `probability`, `label`, `threshold`, `modelName`, `modelVersion`, `errorCode`, `createdAt`;
   - luôn có `experimental: true` và `disclaimer`. Server tự gắn hai trường này, không lưu DB.

6. Riêng tư: không log probability, label hay byte ảnh ở mức INFO trở lên; log lỗi chỉ ghi error_code và visitId.

7. Test:
   - Giả lập service bằng `com.sun.net.httpserver.HttpServer` của JDK, không thêm WireMock hay OkHttp, cho ba trường hợp: thành công, timeout (đặt `predict.timeout` ngắn, ví dụ 500ms, trong test), và 500.
   - Thêm trường hợp: chạy lại dự đoán, gỡ ảnh thì `latestPrediction` = null, và lượt khám không ảnh → 400.
   - Test không phụ thuộc service thật. Các test tích hợp cũ (Visit/Image) phải trỏ `predict.base-url` vào cổng không có gì chạy, hoặc đặt `predict.enabled=false`, để không gọi nhầm service thật ở cổng 8001.
   - Chạy `./mvnw verify` và báo số test.

8. Cập nhật `docs/api-visits.md`: thêm phần dự đoán, `latestPrediction`, các mã lỗi, và cách chạy service (`predict/README.md`). Ghi rõ đây là kết quả thử nghiệm.

Không chạy git add/commit; liệt kê các file đã sửa khi xong.
```
