# Service dự đoán (thử nghiệm) — model New Way và PG, nhãn `ketqua`

> Service chạy **hai mô hình song song**: New Way (các endpoint `/predict/clinical*`, mô tả ngay dưới) và PG (`/predict/pg`, xem mục [Mô hình PG](#mô-hình-pg)). Hai mô hình nạp độc lập: một bên nạp lỗi thì chỉ endpoint của bên đó trả 503.

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
- Các test cần file mô hình sẽ tự `skip` nếu không có `ml/new_way/mo_hinh/` (New Way) hoặc `ml/pg/mo_hinh_PG_web/` (PG; test có ảnh cần thêm file BioViL-T).

## Mô hình PG

PG là mô hình thứ hai, chạy **song song** với New Way và không thay New Way.
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

`DEVICE`, `MAX_UPLOAD_MB` và `MAX_CLINICAL_KB` dùng chung với New Way.

Về kỹ thuật, hai gói đều có một file `nghe.py` nhưng nội dung khác nhau. `app/pg.py` nạp PG tách riêng để mỗi mô hình dùng đúng bản của mình. Test `test_new_way_and_pg_side_by_side` kiểm chuyện này.

### API PG

**`GET /health/pg`** trả `{status, model_loaded, image_model_loaded, device, model_version, model_name}`. Endpoint `/health` cũ giữ nguyên và chỉ nói về New Way.

**`GET /schema/pg`** trả các trường sau:
- `required`: `["tinh"]`;
- `tinh`: 5 giá trị hợp lệ;
- `kind`: kiểu từng cột, `so` = số, `muc` = danh mục, `chu` = chữ;
- `levels`: các mức mô hình nhận ra cho cột danh mục;
- `derived`: các cột mô hình tự tính.

**`POST /predict/pg`** nhận multipart, cùng kiểu với `/predict/clinical-image`:
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
- `canh_bao_dau_vao`: các trường bị bỏ qua hoặc không nhận ra (xem bảng dưới). Không chặn dự đoán.
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

Khác New Way ở chỗ: với PG, giá trị danh mục lạ chỉ cảnh báo chứ không trả 422. Lý do là bảng PG có nhiều cột chữ ghi tự do (ví dụ `benhhh`, `tsnoi`), và gói gốc định nghĩa cách xử lý cho giá trị lạ. Muốn chặn thì phía web dùng ô chọn.

Lỗi ảnh giống `/predict/clinical-image`:
- `400 INVALID_IMAGE`: ảnh hỏng, hoặc gửi phần `file` rỗng;
- `400 UNSUPPORTED_FORMAT`;
- `400 FILE_TOO_LARGE`.

Lỗi nạp mô hình:
- `503 MODEL_NOT_LOADED`: PG chưa nạp được;
- cũng trả `503 MODEL_NOT_LOADED` khi gửi ảnh mà phần xử lý ảnh chưa nạp được, ví dụ thiếu file BioViL-T.

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
   - Dùng ô chọn, giá trị gửi lên phải đúng **từng chuỗi** trong bảng mục 4 của [ml/pg/MO_TA_API.md](ml/pg/MO_TA_API.md), hoặc trong `levels` của `GET /schema/pg`. Chuỗi phân biệt hoa thường, và giữ cả dấu ngoặc kép nếu bảng có.
   - Nhãn hiển thị có thể viết có dấu, ví dụ "Có" → gửi `Co`.
   - Cột kiểu `chu` (chỉ học được "có ghi / bỏ trống") và 4 cột nghề `cviec`, `pxuong`, `cviec1`, `cviec2` dùng ô chữ tự do.
   - Bỏ trống thì không gửi, hoặc gửi `null`/`""`.
3. **Không gửi** `bmi`, `fev1_fvc` và `*_lv`. Mô hình tự tính chúng từ `cao`/`can`, `fev1tt`/`fvctt` và chữ nghề.
4. **Kết quả**:
   - Hiện `xac_suat` dạng **phần trăm**, ví dụ `0.312` → "31 %".
   - Luôn kèm `disclaimer`, và hiện mọi câu trong `canh_bao`.
   - Nếu `canh_bao_dau_vao` có mục, báo người nhập kiểm lại trường đó.
   - **Không** hiện "dương tính/âm tính", "có nguy cơ/nguy cơ thấp" hay bất kỳ ngưỡng nào, cho đến khi ngưỡng PG được duyệt.
5. **`chu_y_vung`**: nếu hiển thị, ghi rõ nhãn **"tham khảo"** (mức chú ý của mô hình, không phải vị trí tổn thương). Tên vùng: `RU` phổi phải trên, `RM` phải giữa, `RL` phải dưới, `LU` trái trên, `LM` trái giữa, `LL` trái dưới.
6. **Ảnh**: là tuỳ chọn. Nếu gửi thì giữ bước xác nhận "Đây là phim X-quang ngực thẳng" như luồng New Way.
