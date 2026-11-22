package com.sillicosis.backend.prediction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Kiểm tra {@code lamSang} của lượt chẩn đoán trước khi lưu, theo danh sách cột của mô hình PG ({@code GET /schema}
 * của predict/, xem predict/ml/pg/MO_TA_API.md):
 * <ul>
 * <li>{@code tinh} bắt buộc, đúng một trong {@link #TINH} (mã không dấu). Luôn kiểm, kể cả khi service chưa chạy.</li>
 * <li>Cột không có trong mô hình, hoặc cột mô hình tự tính ({@code bmi}, {@code fev1_fvc}, {@code *_lv}) → lỗi.</li>
 * <li>Số chấp nhận dấu phẩy thập phân; chữ tự do tối đa 500 ký tự; cột danh mục phải đúng một mức mô hình nhận ra.</li>
 * </ul>
 * Rỗng/null = bỏ trống. Danh sách cột lấy từ {@code GET /schema} một lần rồi giữ lại; nếu service chưa chạy thì chỉ
 * kiểm {@code tinh} (lúc dự đoán FastAPI vẫn kiểm, sai thì dòng FAILED PREDICT_INVALID_INPUT).
 */
@Component
@RequiredArgsConstructor
public class ClinicalValidator {

	public static final String KEY = "lamSang";

	public static final String TINH_KEY = "tinh";

	/** 5 tỉnh có trong dữ liệu học của PG. */
	public static final List<String> TINH = List.of("Binh Dinh", "Dong Nai", "Hai Duong", "Phu Yen", "Thai Nguyen");

	/** Cột mô hình tự tính (dùng khi /schema không trả {@code derived}). */
	static final Set<String> DERIVED = Set.of("bmi", "fev1_fvc", "cviec_lv", "pxuong_lv", "cviec1_lv", "cviec2_lv");

	static final int MAX_TEXT = 500;

	private final PredictClient predictClient;

	private final AtomicReference<PredictClient.Schema> cache = new AtomicReference<>();

	/** Lỗi theo khoá {@code lamSang.<cột>}; thông báo không chứa giá trị đã nhập. {@code null} = không gửi gì. */
	public Map<String, String> validate(Object lamSang) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (lamSang != null && !(lamSang instanceof Map<?, ?>)) {
			errors.put(KEY, "Thông tin lâm sàng phải là một đối tượng JSON {tên_cột: giá_trị}.");
			return errors;
		}
		Map<?, ?> map = lamSang == null ? Map.of() : (Map<?, ?>) lamSang;
		String tinhError = checkTinh(map.get(TINH_KEY));
		if (tinhError != null) {
			errors.put(KEY + "." + TINH_KEY, tinhError);
		}
		Optional<PredictClient.Schema> schema = schema();
		if (schema.isEmpty()) {
			return errors;
		}
		Map<String, String> kind = schema.get().kind();
		Map<String, List<String>> levels = schema.get().levels();
		List<String> derived = schema.get().derived();
		for (Map.Entry<?, ?> e : map.entrySet()) {
			String col = String.valueOf(e.getKey());
			if (TINH_KEY.equals(col)) {
				continue;
			}
			String err = derived.contains(col) ? "Trường do mô hình tự tính, không gửi lên."
					: check(kind.get(col), levels.get(col), e.getValue());
			if (err != null) {
				errors.put(KEY + "." + col, err);
			}
		}
		return errors;
	}

	private static String checkTinh(Object v) {
		if (v == null || v instanceof String s && s.isBlank()) {
			return "Vui lòng chọn tỉnh.";
		}
		return v instanceof String s && TINH.contains(s.strip()) ? null
				: "Tỉnh không thuộc danh sách cho phép: " + String.join(" | ", TINH);
	}

	private static String check(String kind, List<String> allowed, Object v) {
		if (kind == null) {
			return "Trường không có trong mô hình.";
		}
		if (v == null || v instanceof String s && s.isBlank()) {
			return null;
		}
		if (v instanceof Map<?, ?> || v instanceof List<?> || v instanceof Boolean) {
			return "Kiểu giá trị không hợp lệ.";
		}
		String s = String.valueOf(v).strip();
		if (!"so".equals(kind) && s.length() > MAX_TEXT) {
			return "Tối đa " + MAX_TEXT + " ký tự.";
		}
		return switch (kind) {
			case "so" -> parse(s) == null ? "Phải là số." : null;
			case "chu" -> null;
			default -> allowed != null && matches(s, allowed) ? null
					: "Giá trị không thuộc danh sách cho phép: " + String.join(" | ", allowed == null ? List.of() : allowed);
		};
	}

	/** Như {@code _match} bên Python (predict/app/pg.py): 1 / "1" / "1.0" đều khớp mức "1". */
	private static boolean matches(String s, List<String> allowed) {
		if (allowed.contains(s)) {
			return true;
		}
		Double f = parse(s);
		if (f == null) {
			return false;
		}
		if (allowed.contains(String.valueOf(f))) {
			return true;
		}
		return f == Math.rint(f) && Math.abs(f) < 1e15 && allowed.contains(String.valueOf(f.longValue()));
	}

	private static Double parse(String s) {
		try {
			double f = Double.parseDouble(s.replace(',', '.'));
			return Double.isFinite(f) ? f : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Optional<PredictClient.Schema> schema() {
		PredictClient.Schema s = cache.get();
		if (s != null) {
			return Optional.of(s);
		}
		Optional<PredictClient.Schema> fetched = predictClient.schema()
			.filter(x -> x.kind() != null && !x.kind().isEmpty())
			.map(x -> new PredictClient.Schema(x.kind(), x.levels() == null ? Map.of() : x.levels(),
					x.tinh() == null ? TINH : x.tinh(),
					x.derived() == null || x.derived().isEmpty() ? List.copyOf(DERIVED) : x.derived()));
		fetched.ifPresent(cache::set);
		return fetched;
	}

}
