# Service dự đoán (thử nghiệm) — mô hình PG, nhãn `ketqua`

Service FastAPI nội bộ. Backend Spring gọi nó để sàng lọc bụi phổi silic từ **thông tin lâm sàng** (bắt buộc có tỉnh), có thể kèm **phim X-quang ngực thẳng**.

> **Công cụ hỗ trợ sàng lọc, không phải chẩn đoán.** Kết quả chỉ là xác suất để tham khảo, không có ngưỡng hay kết luận.

Mô hình New Way (`/predict/clinical`, `/predict/clinical-image`) **đã bỏ**: mọi lượt chẩn đoán chỉ dùng PG.

## Mô hình PG

- Nhãn `ketqua`: phim được đọc là nghi bụi phổi silic.
- Kiến trúc: Graph Transformer 14 nút, gồm 6 vùng phổi trên ảnh, 6 nhóm lâm sàng, 1 nút tỉnh và 1 nút tổng hợp. Bản khoá ngày 07/11/2026.
- Đầu ra là **xác suất đã hiệu chỉnh** (Platt: chỉnh để con số gần với tỉ lệ thật), lấy trung bình 5 mô hình fold × 3 seed.
- **Chưa có ngưỡng đã duyệt.** Service chỉ trả xác suất. Không trả kết luận dương/âm và không đặt ngưỡng mặc định.
- Chỉ nhận **5 tỉnh** có trong dữ liệu học. Trường `tinh` là bắt buộc.
- Mô tả đầy đủ cách gọi, kết quả và bảng mọi trường đầu vào: [ml/pg/MO_TA_API.md](ml/pg/MO_TA_API.md).

### Đặt mô hình ở đâu

Gói `ml/pg/` là bản chép nguyên `PAG\xuat_app_pg`. **Không sửa** `du_doan_pg.py`, `mo_hinh_pg.py`, `nghe.py` hay `xu_ly_anh.py`. `MoHinhPG()` tự kiểm sha256 từng file trọng số.

```
predict/ml/pg/
├── du_doan_pg.py, mo_hinh_pg.py, nghe.py, xu_ly_anh.py, requirements.txt, MO_TA_API.md   (có trong git)
├── biovil_t_image_model_proj_size_128.pt   ~110 MB, BioViL-T gốc, chỉ cần khi gửi ảnh   (KHÔNG có trong git)
└── mo_hinh_PG_web/                         ~23 MB, dữ liệu riêng tư                       (KHÔNG có trong git)
    ├── cau_hinh_chung.json
    └── fold0 … fold4/  (seed0-2.pt, ma_hoa_anh.npz, ma_hoa_lam_sang.json, platt.json, cau_hinh.json)
```

Trên máy mới, chép các file sau vào đúng chỗ trong sơ đồ trên:
- `mo_hinh_PG_web/` từ `PAG\cache_v3\mo_hinh_PG_web\`;
- file BioViL-T từ `Data DGCNN\Pre-train BioViL-T\`.

Phần tách phổi (PSPNet) do `torchxrayvision` tự tải về `~/.torchxrayvision` ở lần đầu. Máy không có mạng thì chép sẵn thư mục đó.

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PG_DIR` | `ml/pg` | Thư mục gói PG (tương đối tính từ `predict/`, hoặc tuyệt đối). Trọng số nằm ở `<PG_DIR>/mo_hinh_PG_web` |
| `BIOVIL_PATH` | `ml/pg/biovil_t_image_model_proj_size_128.pt` | File BioViL-T gốc |
| `PRELOAD_PG_IMAGE` | `true` | `true`: nạp phần xử lý ảnh (PSPNet + BioViL-T + OCR) lúc khởi động. `false`: nạp ở lần gửi ảnh đầu tiên. Nạp lỗi thì PG chỉ lâm sàng vẫn chạy |

| `DEVICE` | `auto` | `auto` (cuda nếu có) / `cpu` / `cuda` |
| `MAX_UPLOAD_MB` | `10` | Dung lượng ảnh tối đa |
| `MAX_CLINICAL_KB` | `64` | Dung lượng JSON lâm sàng tối đa |

Biến cũ `MODEL_PATH`, `THRESHOLD`, `NEW_WAY_DIR`, `PRELOAD_IMAGE_MODEL` không còn được dùng; `.env` cũ còn các biến đó vẫn chạy (bị bỏ qua).

`app/pg.py` nạp gói PG tách riêng (không thêm `ml/pg` vào `sys.path` chung), để file `nghe.py` của gói không đụng module khác. Test `test_pg_isolated_import` kiểm chuyện này.

### API PG

**`GET /health`** trả `{status, model_loaded, image_model_loaded, device, model_version, model_name}` của PG. Kết quả đúng phải có `"model_loaded": true`; `image_model_loaded` = phần xử lý ảnh đã nạp chưa.

**`GET /schema`** trả các trường sau (Spring dùng để kiểm `lamSang` trước khi lưu; chưa nạp mô hình → 503):
- `required`: `["tinh"]`;
- `tinh`: 5 giá trị hợp lệ;
- `kind`: kiểu từng cột, `so` = số, `muc` = danh mục, `chu` = chữ;
- `levels`: các mức mô hình nhận ra cho cột danh mục;
- `derived`: các cột mô hình tự tính.

**`POST /predict/pg`** nhận multipart. Service đọc ảnh từ bytes trong bộ nhớ và **không** ghi xuống đĩa.
- phần `lam_sang`: chuỗi JSON `{tên_cột: giá_trị}`, **bắt buộc có `tinh`**;
- phần `file`: ảnh phim ngực thẳng PNG/JPEG. **Tuỳ chọn**: bỏ phần này thì mô hình chỉ dùng lâm sàng.

```json
{"tinh":"Thai Nguyen","gioitinh":"Nam","namsinh":1975,"cviec":"cong nhan nghien da","ho":"Co","fvctt":"3,2"}
```

Response (số ví dụ):

```json
{"nhan":"ketqua","xac_suat":0.31,"xac_suat_theo_fold":[0.29,0.33,0.30,0.32,0.31],"co_anh":true,"tinh":"Thai Nguyen",
 "chu_y_vung":{"RU":0.2,"RM":0.15,"RL":0.1,"LU":0.25,"LM":0.18,"LL":0.12},
 "qa_anh":{"dao_am":false,"du_phoi":true,"phoi_R":0.18,"phoi_L":0.16,"ti_le_LR":0.9,"cham_mep":false,"ocr":true,"vung_rong":[]},
 "canh_bao":[],"canh_bao_dau_vao":[],"disclaimer":"Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ.",
 "experimental":true,"mo_hinh":"PG (...), khoá 07/11/2026","model_name":"PG ketqua: ...","model_version":"454ecf8fd45b"}
```

Ý nghĩa các khoá:
- `xac_suat`: xác suất `ketqua` = 1, từ 0 đến 1.
- `xac_suat_theo_fold`: 5 xác suất của 5 mô hình fold. Độ chênh giữa chúng cho thấy các mô hình có đồng ý với nhau hay không.
- `chu_y_vung`: chỉ có khi gửi ảnh, còn không thì là `null`. Đây là tỉ lệ chú ý của mô hình lên 6 vùng, tổng bằng 1. **Chỉ để tham khảo**, không phải bản đồ tổn thương.
- `qa_anh`: kết quả kiểm tra ảnh. `null` khi không gửi ảnh.
- `canh_bao`: các câu tiếng Việt do mô hình sinh, nên hiện nguyên cho người dùng. Ví dụ: không thấy đủ hai phổi; ảnh bị đảo âm bản; có ảnh ở Đồng Nai/Phú Yên, hai tỉnh gần như không có phim lúc học.
- `canh_bao_dau_vao`: các trường bị bỏ qua hoặc không nhận ra (xem bảng dưới). Không chặn dự đoán. Spring không lưu trường này, vì Spring đã chặn các trường hợp đó từ trước (xem dưới).
- Header `X-Model-Name: PG ketqua` và `X-Model-Version`: 12 ký tự hex đầu của sha256 file `cau_hinh_chung.json`.

**Kiểm đầu vào.** Lỗi chặn trả 422 `INVALID_INPUT`, kèm `errors[]`. Cảnh báo thì không chặn và nằm trong `canh_bao_dau_vao[]`.

| Trường hợp | Kết quả | `error` |
|---|---|---|
| Thiếu `tinh` hoặc `tinh` rỗng | 422 (kèm `allowed` = 5 tỉnh) | `REQUIRED` |
| `tinh` ngoài 5 giá trị (kể cả viết có dấu, sai hoa thường) | 422 (kèm `allowed`) | `INVALID_CATEGORY` |
| Cột số không đọc được | 422 | `NOT_A_NUMBER` |
| Giá trị là object, mảng hoặc true/false | 422 | `INVALID_TYPE` |
| Chữ dài quá 500 ký tự | 422 | `TOO_LONG` |
| Cột không có trong mô hình | bỏ qua, cảnh báo | `UNKNOWN_FIELD_IGNORED` |
| Gửi `bmi`, `fev1_fvc`, `*_lv` | bỏ qua (mô hình tự tính), cảnh báo | `DERIVED_FIELD_IGNORED` |
| Cột danh mục có giá trị ngoài danh sách | **vẫn gửi** (mô hình coi là "có ghi nhưng không nhận ra"), cảnh báo kèm `allowed` | `UNRECOGNIZED_VALUE` |

Ở service này, giá trị danh mục lạ, cột lạ và cột tự tính chỉ cảnh báo chứ không trả 422, vì gói gốc định nghĩa cách xử lý cho chúng. **Backend Spring chặt hơn:** `ClinicalValidator` từ chối (400 `VALIDATION_FAILED`) cột không có trong mô hình, cột tự tính và giá trị danh mục ngoài `levels`, trước khi gọi service này.

Lỗi ảnh:
- `400 INVALID_IMAGE`: ảnh hỏng, hoặc gửi phần `file` rỗng;
- `400 UNSUPPORTED_FORMAT`;
- `400 FILE_TOO_LARGE`.

Lỗi nạp mô hình:
- `503 MODEL_NOT_LOADED`: PG chưa nạp được;
- cũng trả `503 MODEL_NOT_LOADED` khi gửi ảnh mà phần xử lý ảnh chưa nạp được, ví dụ thiếu file BioViL-T.

Route đã bỏ trả `410 ENDPOINT_REMOVED`: `/predict` (model cũ BioViL-T Set A, nhãn `bnn`), `/predict/clinical`, `/predict/clinical-image` (New Way), `/health/pg`, `/schema/pg` (nay là `/health`, `/schema`).

### Hướng dẫn cho phía web (form PG)

1. **Tỉnh**: dùng ô chọn, bắt buộc, đúng 5 giá trị. Hiện tên có dấu, gửi chuỗi không dấu:

   | Hiển thị | Gửi |
   |---|---|
   | Bình Định | `Binh Dinh` |
   | Đồng Nai | `Dong Nai` |
   | Hải Dương | `Hai Duong` |
   | Phú Yên | `Phu Yen` |
   | Thái Nguyên | `Thai Nguyen` |

   Không cho chọn tỉnh khác. Mô hình chỉ học 5 tỉnh này.
2. **Trường danh mục**:
   - Dùng ô chọn, giá trị gửi lên phải đúng **từng chuỗi** trong bảng mục 4 của [ml/pg/MO_TA_API.md](ml/pg/MO_TA_API.md), hoặc trong `levels` của `GET /schema`. Chuỗi phân biệt hoa thường, và giữ cả dấu ngoặc kép nếu bảng có.
   - Nhãn hiển thị có thể viết có dấu, ví dụ "Có" → gửi `Co`.
   - Cột kiểu `chu` (chỉ học được "có ghi / bỏ trống") và 4 cột nghề `cviec`, `pxuong`, `cviec1`, `cviec2` dùng ô chữ tự do.
   - Bỏ trống thì không gửi, hoặc gửi `null`/`""`.
3. **Không gửi** `bmi`, `fev1_fvc` và `*_lv`. Mô hình tự tính chúng từ `cao`/`can`, `fev1tt`/`fvctt` và chữ nghề.
4. **Kết quả**:
   - Hiện `xac_suat` dạng **phần trăm**, ví dụ `0.312` → "31 %".
   - Luôn kèm `disclaimer`, và hiện mọi câu trong `canh_bao`.
   - **Không** hiện "dương tính/âm tính", "có nguy cơ/nguy cơ thấp" hay bất kỳ ngưỡng nào, cho đến khi ngưỡng PG được duyệt.
5. **`chu_y_vung`**: nếu hiển thị, ghi rõ nhãn **"tham khảo"** (mức chú ý của mô hình, không phải vị trí tổn thương). Tên vùng: `RU` phổi phải trên, `RM` phải giữa, `RL` phải dưới, `LU` trái trên, `LM` trái giữa, `LL` trái dưới.
6. **Ảnh**: là tuỳ chọn. Nếu gửi thì giữ bước xác nhận "Đây là phim X-quang ngực thẳng" như trước.

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
- `MoHinhPG()` được nạp **một lần** lúc khởi động. Nếu nạp lỗi, service vẫn chạy nhưng `/schema` và `/predict/pg` trả 503.

## Model cũ đã ngắt

- `app/model.py` (BioViL-T Set A, nhãn `bnn`) và `to_gray`/`to_tensor` trong `app/preprocess.py` vẫn còn trong repo nhưng không được dùng.
- Trọng số New Way cũ (`ml/new_way/mo_hinh/`) nếu còn trên máy thì không được nạp; đã nằm trong `.gitignore`.
- Migration V7 bên Spring đã xoá mọi lượt chẩn đoán cũ (dữ liệu thử) và các cột New Way.

## Riêng tư

- Service không lưu ảnh hay dữ liệu lâm sàng xuống đĩa.
- Log chỉ ghi tên endpoint, mã kết quả và thời gian xử lý. Log không ghi tên tệp, nội dung ảnh hay giá trị lâm sàng, vì ảnh có thể chứa tên hoặc ngày in trên phim.
- Thông báo lỗi 422 không lặp lại giá trị người dùng đã nhập.

## Test

```powershell
.\.venv\Scripts\python -m pytest -q
```

- Mọi test dùng dữ liệu giả và ảnh giả tạo bằng PIL, không dùng dữ liệu bệnh nhân thật.
- Các test cần file mô hình sẽ tự `skip` nếu không có `ml/pg/mo_hinh_PG_web/`; test có ảnh cần thêm file BioViL-T.
