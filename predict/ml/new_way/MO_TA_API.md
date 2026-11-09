# Gói mô hình New Way cho app — mô tả đầu vào và đầu ra (dành cho backend)

## Nhãn và luồng
**Nhãn** là `ketqua`: phim X-quang được đọc là nghi bụi phổi silic. Mô hình không chẩn đoán. Nó chỉ cho biết **có nên khám hoặc đọc phim tiếp hay không** (sàng lọc).

**Luồng trong app:**
1. **Nhập lâm sàng.** Gọi `MoHinh.du_doan(lam_sang)` và nhận `p_lam_sang`. Nếu `p_lam_sang ≥ 0,051546` thì báo *có nguy cơ, nên chụp hoặc khám tiếp*.
2. **Nếu có phim X-quang ngực thẳng.** Gọi `MoHinh.du_doan(lam_sang, anh)` và nhận thêm `p_anh` và `p_ghep`. Nếu `p_ghep ≥ 0,207134` thì báo *có nguy cơ*. Khi đã có ảnh, **kết luận cuối dùng `p_ghep`**.

## Cách gọi (Python)
```python
import sys; sys.path.insert(0, "duong/den/xuat_app")
from du_doan import MoHinh
mh = MoHinh()                         # nạp 1 lần lúc khởi động server; tự kiểm tra sha256 các file mô hình
kq = mh.du_doan({"gioitinh": "Nam", "namsinh": 1975, "cviec": "cong nhan nghien da", "ho": "Co"})
kq = mh.du_doan(ban_ghi, "phim.png")  # hoặc PIL.Image (ví dụ Image.open(io.BytesIO(bytes_upload)))
```
Ngoài ra có các hàm theo lô (nhiều lượt khám một lần):
- `diem_lam_sang(DataFrame)`
- `doc_anh(...)` rồi `diem_anh([...])`
- `ghep(p_anh, p_ls)`
- `ket_luan(p, "lam_sang" | "ghep")`

## Đầu ra của `du_doan()` (dict, JSON-hóa được)
| Khóa | Kiểu | Ý nghĩa |
|---|---|---|
| `nhan` | str | luôn là `"ketqua"` |
| `p_lam_sang` | float 0–1 | xác suất theo lâm sàng (đã hiệu chuẩn trên dev) |
| `nguong_lam_sang` | float | 0,051546: ngưỡng chọn để độ nhạy ≥ 0,80 kể cả trong trường hợp xấu |
| `bao_nguy_co_lam_sang` | bool | `p_lam_sang ≥ nguong_lam_sang` |
| `co_anh` | bool | có gửi ảnh hay không |
| `p_anh` | float | *(chỉ khi có ảnh)* xác suất theo ảnh, trung bình 3 mô hình BioViL-T |
| `p_ghep` | float | *(chỉ khi có ảnh)* `sigmoid(0,901645·logit(p_anh) + 0,249384·logit(p_lam_sang) + 0,016646)` |
| `nguong_ghep` | float | 0,207134 |
| `bao_nguy_co_ghep` | bool | `p_ghep ≥ nguong_ghep` |
| `ket_luan_cuoi` | bool | dùng `bao_nguy_co_ghep` nếu có ảnh, ngược lại dùng `bao_nguy_co_lam_sang` |

**Gợi ý câu hiển thị:**
- Khi `True`: “Có nguy cơ, nên khám / đọc phim chuyên khoa”.
- Khi `False`: “Nguy cơ thấp theo mô hình”.
- Không ghi “âm tính” hay “dương tính”.

## Đầu vào lâm sàng (dict một lượt khám)
- **Tên khóa** trùng tên cột trong file Excel gốc.
- **Cột thiếu hoặc để `None` / `""`** được coi là không có dữ liệu. Mô hình vẫn chạy được.
- **Giá trị danh mục phải đúng chuỗi trong bảng** (không dấu, đúng hoa thường). Giá trị khác hoặc gõ sai sẽ bị coi là mức hiếm `__hiem__` mà **không báo lỗi**, nên app nên dùng ô chọn sẵn.
- **Cấp nghề** (`cviec_cap`, `pxuong_cap`, `cviec1_cap`, `cviec2_cap`) được tính tự động từ chữ nghề bằng `nghe.py`. Backend không cần gửi.

| Cột | Kiểu | Giá trị cho phép |
|---|---|---|
| `gioitinh` | danh mục | `Nam` · `Nu`; để trống nếu không có |
| `namsinh` | số | số thực; để trống nếu không có |
| `tuoinghe` | số | số thực; để trống nếu không có |
| `nampx` | số | số thực; để trống nếu không có |
| `cv5nam` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `tgian1` | số | số thực; để trống nếu không có |
| `tgian2` | số | số thực; để trống nếu không có |
| `cao` | số | số thực; để trống nếu không có |
| `can` | số | số thực; để trống nếu không có |
| `hatd` | số | số thực; để trống nếu không có |
| `hatt` | số | số thực; để trống nếu không có |
| `mach` | số | số thực; để trống nếu không có |
| `theluc` | danh mục | `1` · `2` · `3` · `4`; để trống nếu không có |
| `hutthuoc` | danh mục | `Co hut thuoc` · `Khong hut thuoc`; để trống nếu không có |
| `slthuoc` | số | số thực; để trống nếu không có |
| `tiensuhh` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `benhhh` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `tsnoi` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `tsngoai` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `ho` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `tdho` | danh mục | `Ban dem` · `Ban ngay` · `Ca ngay va dem`; để trống nếu không có |
| `tsho` | danh mục | `Khac` · `Lien tuc` · `Tung con`; để trống nếu không có |
| `n1` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `khacdom` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `loaidom` | danh mục | `Dom ba dau` · `Dom mu` · `Dom nhay`; để trống nếu không có |
| `tdkhacdo` | danh mục | `Ban dem` · `Ban ngay` · `Ca ngay va dem`; để trống nếu không có |
| `khotho` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `mdkhotho` | danh mục | `"Kho tho thuong xuyen, ca khi nghi ngoi"` · `Kho tho khi gang suc` · `Kho tho khi lam viec nhe` · `Kho tho thuong xuyen, ca khi nghi ngoi` · `Kho tho tung con`; để trống nếu không có |
| `tdkhotho` | danh mục | `Ban dem` · `Ban ngay` · `Ca ngay va dem`; để trống nếu không có |
| `daunguc` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `vitridau` | danh mục | `Dau sau xuong uc` · `Hai ben nguc` · `Nguc phai` · `Nguc trai`; để trống nếu không có |
| `daulan` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `tcdau` | danh mục | `De ep` · `Khac` · `Nhu dao dam` · `Xiet chat`; để trống nếu không có |
| `n2` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `tgdau` | số | số thực; để trống nếu không có |
| `ytodau` | danh mục | `Khac` · `Khi gang suc` · `Khi nghi ngoi`; để trống nếu không có |
| `n3` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `ytotang` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `chaymui` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `khan` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `khokhe` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `dhkhac` | chữ tự do | ghi bất cứ gì khác “không / bình thường / 0 / trống” thì tính là có |
| `metmoi` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `sutcan` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `socansut` | số | số thực; để trống nếu không có |
| `tgsut` | số | số thực; để trống nếu không có |
| `longnguc` | danh mục | `Can doi` · `Khong can doi`; để trống nếu không có |
| `khoangls` | danh mục | `Binh thuong` · `Gian rong`; để trống nếu không có |
| `rungthan` | danh mục | `Binh thuong` · `Giam`; để trống nếu không có |
| `go` | danh mục | `Binh thuong`; để trống nếu không có |
| `riraopn` | danh mục | `Binh thuong` · `Giam`; để trống nếu không có |
| `ran` | danh mục | `Co` · `Khong`; để trống nếu không có |
| `fvclt` | số | số thực; để trống nếu không có |
| `fvctt` | số | số thực; để trống nếu không có |
| `fev1lt` | số | số thực; để trống nếu không có |
| `fev1tt` | số | số thực; để trống nếu không có |
| `fvc` | số | số thực; để trống nếu không có |
| `fev1` | số | số thực; để trống nếu không có |
| `gaenler` | số | số thực; để trống nếu không có |
| `cviec` | chữ tự do | công việc / phân xưởng; mô-đun tự quy ra cấp nghề 1–10 |
| `pxuong` | chữ tự do | công việc / phân xưởng; mô-đun tự quy ra cấp nghề 1–10 |
| `cviec1` | chữ tự do | công việc / phân xưởng; mô-đun tự quy ra cấp nghề 1–10 |
| `cviec2` | chữ tự do | công việc / phân xưởng; mô-đun tự quy ra cấp nghề 1–10 |
**Ghi chú về mức danh mục:**
- `mdkhotho` có hai mức gần giống nhau: một mức có dấu ngoặc kép bao quanh, đúng như trong dữ liệu gốc. Nên gửi bản không có ngoặc kép.
- `theluc` gửi dạng số 1–4 hoặc chuỗi `"1"`… đều được.
- Các cột số dạng chuỗi có dấu phẩy thập phân (ví dụ `"3,5"`) được tự đọc thành số.

## Đầu vào ảnh
- File PNG/JPG (hoặc PIL.Image) của phim X-quang ngực thẳng.
- Mô-đun tự xử lý, giống hệt lúc học:
  - chuyển sang ảnh xám;
  - đưa cạnh ngắn về 512 px, cắt phần giữa 512×512;
  - cắt sáng theo phân vị 0,5–99,5 %;
  - đưa về 448 px.
- Không cần tiền xử lý trước.
- Mô hình chỉ học trên phim ngực thẳng của bộ dữ liệu này. Ảnh sai loại (nghiêng, chụp màn hình, ảnh không phải phim) sẽ cho điểm vô nghĩa. App nên có bước người dùng xác nhận loại ảnh.

## Tài nguyên và môi trường
- **Python và thư viện:** Python 3.13, xem `requirements.txt`. Mô hình ảnh tự nạp kiến trúc từ `hi-ml-multimodal`; checkpoint đã chứa toàn bộ trọng số, nên **không cần tải trọng số gốc từ mạng**.
- **Bộ nhớ:** 3 file `.pt` × 110 MB. Cần khoảng 1,5 GB RAM khi nạp.
- **GPU:** có GPU thì chạy fp16. Chỉ có CPU thì chạy fp32, điểm có thể lệch khoảng 1e-3, không đổi kết luận trừ khi điểm sát ngưỡng.
- **Nạp mô hình:** nạp `MoHinh()` **một lần** khi khởi động. Mô hình ảnh chỉ được nạp khi có ảnh đầu tiên.
- **Kiểm tra toàn vẹn:** `MoHinh()` kiểm tra sha256 của mô hình theo `cau_hinh_chot.json` và báo lỗi nếu file bị thay. Không sửa `cau_hinh_chot.json`.

## Hiệu năng trên tập test khóa (bước 7, chấm một lần)
| Luồng | ROC-AUC (khả năng xếp ca dương trên ca âm) | Độ nhạy (tỉ lệ ca dương được báo) | Độ đặc hiệu (tỉ lệ ca âm không bị báo) | Tỉ lệ bị báo |
|---|---|---|---|---|
| Lâm sàng (không ảnh, n=1.012) | 0,637 | 0,861 | 0,338 | 67,6 % |
| Ảnh + lâm sàng (n=323) | 0,816 | 0,815 | 0,623 | 50,2 % |

**Giới hạn cần hiển thị hoặc ghi rõ:**
- Phần lâm sàng là bộ lọc rộng: báo khoảng 2/3 số người.
- Trên test, ghép không tốt hơn chỉ dùng ảnh; tín hiệu chính đến từ ảnh.
- Mô hình học từ 5 tỉnh, 2 đợt dữ liệu. Ở nơi khác, ngưỡng cần được kiểm tra lại.

## Tệp trong gói
| Tệp | Vai trò |
|---|---|
| `du_doan.py` | lớp `MoHinh`: mã hóa, chấm điểm, ghép, ngưỡng |
| `nghe.py` | quy chữ nghề ra cấp 1–10 (sao nguyên văn quy tắc lúc học, đã tắt `unidecode` có chủ đích) |
| `cau_hinh_chot.json` | toàn bộ mã hóa, hệ số, ngưỡng, sha256 (chốt trên dev) |
| `mo_hinh/lam_sang_app_xgb.json` | XGBoost lâm sàng |
| `mo_hinh/biovil_t_448_seed{0,1,2}.pt` | 3 mô hình ảnh BioViL-T |
| `requirements.txt`, `run_config.json` | phiên bản thư viện, sha256 từng tệp, kết quả tự kiểm tra |

**Tự kiểm tra (`scripts/buoc8_xuat.py`):**
- Gói cho ra đúng điểm của 1.347 lượt test ở bước 7.
- Độ lệch: lâm sàng ≤ 1e-8, ảnh ≤ 1e-16, ghép ≤ 4e-9; gọi từng lượt kèm file ảnh gốc ≤ 1,3e-4.
- Cấp nghề trùng 8.030/8.030 dòng.
