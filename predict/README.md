# Service dự đoán (thử nghiệm) — model New Way, nhãn `ketqua`

Service FastAPI nội bộ. Backend Spring gọi nó để sàng lọc bụi phổi silic từ **thông tin lâm sàng**, có thể kèm **phim X-quang ngực thẳng**.

> **Công cụ hỗ trợ sàng lọc, không phải chẩn đoán.** Kết quả chỉ trả lời câu hỏi "có nên khám / đọc phim chuyên khoa tiếp hay không".

- **Nhãn `ketqua`:** phim được bác sĩ đọc là nghi bụi phổi silic.
- **Phần lâm sàng:** XGBoost. Ngưỡng `0.051546`, chọn để độ nhạy ≥ 0,80.
- **Phần ảnh:** BioViL-T 448 px, gồm 3 mô hình (3 seed), lấy trung bình.
- **Ghép:** `p_ghep = sigmoid(0.901645·logit(p_anh) + 0.249384·logit(p_lam_sang) + 0.016646)`, ngưỡng `0.207134`.
- **Mô tả đầy đủ đầu vào và đầu ra:** [ml/new_way/MO_TA_API.md](ml/new_way/MO_TA_API.md).

## Đặt mô hình ở đâu

Gói `ml/new_way/` là bản chép nguyên `ĐÒ ÁN\Data DGCNN\New Way\xuat_app`. **Không sửa** `du_doan.py`, `nghe.py` hay `cau_hinh_chot.json` trong gói này. `MoHinh()` kiểm tra sha256 các file mô hình theo `cau_hinh_chot.json` và báo lỗi nếu có file bị thay.

```
predict/ml/new_way/
├── du_doan.py, nghe.py, cau_hinh_chot.json, run_config.json, MO_TA_API.md   (có trong git)
└── mo_hinh/                                                                (KHÔNG có trong git — .gitignore)
    ├── biovil_t_448_seed0.pt   ~110 MB
    ├── biovil_t_448_seed1.pt   ~110 MB
    ├── biovil_t_448_seed2.pt   ~110 MB
    └── lam_sang_app_xgb.json
```

Máy mới: chép thư mục `mo_hinh/` từ `New Way\xuat_app\mo_hinh\` vào `predict/ml/new_way/mo_hinh/`. Cách khác là để gói ở chỗ khác rồi trỏ `NEW_WAY_DIR` tới thư mục đó.

## Cài đặt & chạy

```powershell
cd predict
# Kế thừa torch đã cài sẵn trên máy (tránh tải lại ~2.5 GB).
python -m venv --system-site-packages .venv
# Máy mới chưa có torch thì bỏ --system-site-packages; requirements.txt sẽ cài torch cu124.
.\.venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

- Sau khi chạy, mở `http://127.0.0.1:8001/docs` để thử API.
- Service chỉ bind `127.0.0.1`, không bật CORS và không xác thực, vì chỉ backend Spring gọi tới nó.
- `MoHinh()` được nạp **một lần** lúc khởi động. Nếu nạp lỗi, service vẫn chạy nhưng các endpoint dự đoán trả 503.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `NEW_WAY_DIR` | `ml/new_way` | Thư mục gói New Way: tương đối tính từ `predict/`, hoặc tuyệt đối |
| `PRELOAD_IMAGE_MODEL` | `true` | `true`: nạp 3 mạng ảnh ngay lúc khởi động (~1,5 GB RAM). `false`: nạp ở lần gửi ảnh đầu tiên |
| `DEVICE` | `auto` | `auto` (cuda nếu có) / `cpu` / `cuda`. GPU chạy fp16; CPU chạy fp32, điểm có thể lệch khoảng 1e-3 |
| `MAX_UPLOAD_MB` | `10` | Dung lượng ảnh tối đa |
| `MAX_CLINICAL_KB` | `64` | Dung lượng JSON lâm sàng tối đa |

Biến cũ `MODEL_PATH` và `THRESHOLD` (model BioViL-T Set A, nhãn `bnn`) không còn được dùng.

## API

Mỗi response dự đoán thành công có thêm 2 header:
- `X-Model-Version`: 12 ký tự hex, hiện là `68b803152d39`;
- `X-Model-Name`: `New Way ketqua`.

Body của response là **nguyên dict** mà `MoHinh.du_doan()` trả về.

### `GET /health`

```json
{"status":"ok","model_loaded":true,"image_model_loaded":true,"device":"cuda",
 "model_version":"68b803152d39","model_name":"New Way ketqua: ..."}
```

### `GET /schema`

Trả danh sách cột và loại của từng cột (`muc` = danh mục, `so` = số, `chu` = chữ tự do), kèm các mức hợp lệ. Spring dùng endpoint này để kiểm tra `extra.lam_sang` trước khi lưu.

### `POST /predict/clinical` (chỉ lâm sàng)

Body là JSON `{tên_cột: giá_trị}`. Quy tắc cho giá trị:
- Cột thiếu, `null` hoặc `""` được coi là bỏ trống.
- Số có thể gửi dạng chuỗi có dấu phẩy thập phân, ví dụ `"3,5"`.
- Cột danh mục phải đúng mã **không dấu** trong bảng của MO_TA_API.md, ví dụ `"Co"`, `"Khong"`, `"Co hut thuoc"`. Với `mdkhotho`, gửi bản **không** có ngoặc kép.

```json
{"gioitinh":"Nam","namsinh":1975,"cviec":"cong nhan nghien da","ho":"Co","fvc":"3,2"}
```

Response:

```json
{"nhan":"ketqua","p_lam_sang":0.21,"nguong_lam_sang":0.051546,"bao_nguy_co_lam_sang":true,
 "co_anh":false,"p_anh":null,"p_ghep":null,"nguong_ghep":null,"bao_nguy_co_ghep":null,"ket_luan_cuoi":true}
```

### `POST /predict/clinical-image` (lâm sàng + phim)

Request là multipart, gồm:
- phần `lam_sang`: chuỗi JSON như trên, có thể là `{}`;
- phần `file`: ảnh PNG hoặc JPEG.

Service đọc ảnh từ bytes trong bộ nhớ và **không** ghi xuống đĩa.

```json
{"nhan":"ketqua","p_lam_sang":0.21,"nguong_lam_sang":0.051546,"bao_nguy_co_lam_sang":true,
 "co_anh":true,"p_anh":0.64,"p_ghep":0.55,"nguong_ghep":0.207134,"bao_nguy_co_ghep":true,"ket_luan_cuoi":true}
```

Khi có ảnh, `ket_luan_cuoi` lấy theo `p_ghep`; khi không có ảnh, lấy theo `p_lam_sang`.

**Câu hiển thị cho người dùng:**
- `true` → "Có nguy cơ, nên khám / đọc phim chuyên khoa".
- `false` → "Nguy cơ thấp theo mô hình".
- Không dùng chữ "dương tính" hay "âm tính".

### Lỗi

Lỗi trả về dạng `{code, message[, errors]}`:

| HTTP | code | Khi nào |
|---|---|---|
| 422 | `INVALID_INPUT` | JSON lâm sàng hỏng; cột không có trong mô hình; mã danh mục sai (`errors[]` có `field` và `allowed`); số không đọc được; chữ dài quá 500 ký tự |
| 400 | `FILE_TOO_LARGE` | Ảnh vượt `MAX_UPLOAD_MB` |
| 400 | `UNSUPPORTED_FORMAT` | Mở được nhưng không phải JPEG/PNG (xét theo nội dung, không theo tên tệp) |
| 400 | `INVALID_IMAGE` | Không phải ảnh, ảnh hỏng, quá 50 triệu pixel, hoặc thiếu phần `file` |
| 503 | `MODEL_NOT_LOADED` | Không nạp được gói mô hình (thiếu file, sai sha256…) |
| 410 | `ENDPOINT_REMOVED` | Gọi `POST /predict` của model cũ |

Gói gốc âm thầm coi mã danh mục sai là mức hiếm. Service này thì trả **422**, để lỗi gõ sai không làm điểm bị lệch mà không ai biết.

## Model cũ đã ngắt

- Route `POST /predict` (BioViL-T Set A, nhãn `bnn`) hiện trả 410.
- File `app/model.py` vẫn giữ trong repo nhưng không được import nữa.
- Trong DB, các dòng `predictions` của model cũ vẫn còn, với `engine = 'LEGACY'`, nhưng không hiện ra trong app.

## Giới hạn cần biết

Số liệu đo trên tập test khóa, chấm một lần:

| Luồng | ROC-AUC (khả năng xếp ca có bệnh cao hơn ca không bệnh) | Độ nhạy (tỉ lệ ca có bệnh được báo) | Độ đặc hiệu (tỉ lệ ca không bệnh không bị báo) | Tỉ lệ bị báo |
|---|---|---|---|---|
| Chỉ lâm sàng (n=1.012) | 0,637 | 0,861 | 0,338 | 67,6 % |
| Ảnh + lâm sàng (n=323) | 0,816 | 0,815 | 0,623 | 50,2 % |

- Phần lâm sàng là **bộ lọc rộng**: nó báo nguy cơ cho khoảng 2/3 số người.
- Khi có phim, tín hiệu chủ yếu đến từ ảnh. Trên tập test, ghép không tốt hơn chỉ dùng ảnh.
- Mô hình học từ dữ liệu 5 tỉnh, 2 đợt. Ở nơi khác, ngưỡng cần được kiểm tra lại.
- Mô hình chỉ học trên phim ngực thẳng. Ảnh sai loại (phim nghiêng, ảnh chụp màn hình, ảnh không phải phim) cho điểm vô nghĩa. Vì vậy app bắt người dùng xác nhận "Đây là phim X-quang ngực thẳng" trước khi gửi ảnh.

## Riêng tư

- Service không lưu ảnh hay dữ liệu lâm sàng xuống đĩa.
- Log chỉ ghi tên endpoint, mã kết quả và thời gian xử lý. Log không ghi tên tệp, nội dung ảnh hay giá trị lâm sàng, vì ảnh có thể chứa tên hoặc ngày in trên phim.
- Thông báo lỗi 422 không lặp lại giá trị người dùng đã nhập.

## Test

```powershell
.\.venv\Scripts\python -m pytest -q
```

- Mọi test dùng dữ liệu giả và ảnh giả tạo bằng PIL, không dùng dữ liệu bệnh nhân thật.
- Các test cần file mô hình sẽ tự `skip` nếu không có `ml/new_way/mo_hinh/`.
