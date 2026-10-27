# Service dự đoán (thử nghiệm)

Service FastAPI nội bộ. Backend Spring gọi nó để ước lượng xác suất bệnh bụi phổi silic từ một ảnh X-quang ngực.

> **Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ.**

- **Model:** BioViL-T (ResNet50 multi-image, projector 128) fine-tune trên Silicodata Set A, nhãn `bnn`. Lớp 1 = `"Co"`.
- **Checkpoint:** `best_model_biovilt_finetuned.pth`, khoảng 110 MB. File nằm ngoài repo; trỏ tới nó bằng `MODEL_PATH`.
- **Code huấn luyện:** `ĐÒ ÁN\Data DGCNN\Finetune 4\finetune_biovilt_setA.py`.

## Cài đặt & chạy

```powershell
cd predict
# Kế thừa torch đã cài sẵn trên máy (tránh tải lại ~2.5 GB).
python -m venv --system-site-packages .venv
# Máy mới chưa có torch thì bỏ --system-site-packages; requirements.txt sẽ cài torch cu124.
.\.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env      # rồi sửa MODEL_PATH
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

Sau khi chạy, mở `http://127.0.0.1:8001/docs` để thử API. Service chỉ bind `127.0.0.1`, không bật CORS và không xác thực, vì chỉ backend Spring gọi tới nó.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `MODEL_PATH` | (trống) | Đường dẫn checkpoint. Trống hoặc sai → `/predict` trả 503 |
| `THRESHOLD` | `0.403` | Ngưỡng xác suất để báo `"Co"` |
| `DEVICE` | `auto` | `auto` (cuda nếu có) / `cpu` / `cuda` |
| `MAX_UPLOAD_MB` | `10` | Dung lượng ảnh tối đa |

## API

### `GET /health`

```json
{"status": "ok", "model_loaded": true, "device": "cuda", "model_version": "c63f9782b7d1"}
```

`model_version` là 12 ký tự đầu của sha256 file checkpoint. Với checkpoint hiện tại, giá trị là `c63f9782b7d1`.

### `POST /predict`

Gửi multipart với field `file`, chứa ảnh JPEG hoặc PNG.

```json
{
  "probability": 0.18,
  "label": "Khong",
  "threshold": 0.403,
  "model_version": "c63f9782b7d1",
  "model_name": "BioViL-T fine-tune Set A (bnn)",
  "experimental": true,
  "disclaimer": "Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ."
}
```

- `probability`: xác suất lớp "Co", tính bằng `softmax(logits)[1]`.
- `label`: `"Co"` nếu `probability >= threshold`, ngược lại `"Khong"`. Nhãn viết ASCII không dấu.

Lỗi trả về dạng `{code, message}`:

| HTTP | code | Khi nào |
|---|---|---|
| 400 | `FILE_TOO_LARGE` | Ảnh vượt `MAX_UPLOAD_MB` |
| 400 | `UNSUPPORTED_FORMAT` | Mở được nhưng không phải JPEG/PNG (xét theo nội dung, không theo tên tệp) |
| 400 | `INVALID_IMAGE` | Không phải ảnh, ảnh hỏng, quá 50 triệu pixel, hoặc thiếu field `file` |
| 503 | `MODEL_NOT_LOADED` | Không nạp được checkpoint |

DICOM nằm ngoài phạm vi.

## Tiền xử lý (giống hệt lúc huấn luyện)

1. Chuyển ảnh về xám 8-bit (`L`). Ảnh 16-bit được co giãn min–max về 0–255.
2. `resize((256, 256), LANCZOS)`, giống `Finetune\resize_setA.py`.
3. `Resize(512)` → `CenterCrop(448)` → `ToTensor()` (giá trị 0–1, **không** normalize ImageNet) → `ExpandChannels()` (1 kênh → 3 kênh).

## Giới hạn cần biết

- **Ngưỡng 0.403 lạc quan.** Ngưỡng được chọn theo Youden trên chính tập test của lần fine-tune: 339 ảnh, 60% là "Co". Trên tập đó:
  - AUC = 0.902 (khả năng phân biệt hai lớp; 1 là hoàn hảo);
  - độ nhạy (tỉ lệ bắt đúng ca bệnh) = 0.906;
  - độ đặc hiệu (tỉ lệ nhận đúng ca không bệnh) = 0.781.

  Ngoài thực tế tỉ lệ dương tính thấp hơn nhiều. Vì vậy khi model báo "Co", tỉ lệ đúng sẽ thấp hơn những con số trên.
- **Ảnh không vuông bị méo.** Ảnh huấn luyện gốc là 1024×1024 nên lúc huấn luyện không bị méo. Ảnh không vuông vẫn bị ép về 256×256 như bước 2, và điều này có thể làm kết quả kém hơn.
- **Chỉ dùng ảnh.** Model chưa dùng chỉ số lâm sàng.
- **Thời gian dự đoán một ảnh** (RTX 3060 Laptop, không tính warm-up): khoảng 17 ms trên cuda, khoảng 3.9 s trên cpu.

## Riêng tư

- Service không lưu ảnh xuống đĩa.
- Log chỉ ghi mã kết quả và thời gian xử lý, không ghi tên tệp hay nội dung ảnh, vì ảnh có thể chứa tên/ngày in trên phim.

## Test

```powershell
.\.venv\Scripts\python -m pytest -q
```

- Mọi test dùng ảnh giả tạo bằng PIL, không dùng ảnh bệnh nhân thật.
- Các test cần checkpoint sẽ tự `skip` nếu không tìm thấy `MODEL_PATH`.
