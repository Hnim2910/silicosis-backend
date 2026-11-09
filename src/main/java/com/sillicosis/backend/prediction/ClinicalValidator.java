package com.sillicosis.backend.prediction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Kiểm tra {@code extra.lam_sang} trước khi lưu, cùng quy tắc với FastAPI ({@code predict/app/new_way.py}): cột phải
 * có trong cấu hình model, số chấp nhận dấu phẩy thập phân, chữ tự do tối đa 500 ký tự, cột phân loại phải đúng mã
 * không dấu. Rỗng/null = bỏ trống. Danh sách cột lấy từ {@code GET /schema} một lần rồi giữ lại; nếu service chưa
 * chạy thì bỏ qua bước này (lúc dự đoán FastAPI vẫn kiểm tra, sai thì dòng FAILED PREDICT_INVALID_INPUT).
 */
@Component
@RequiredArgsConstructor
public class ClinicalValidator {

	public static final String KEY = "lam_sang";

	static final int MAX_TEXT = 500;

	private final PredictClient predictClient;

	private final AtomicReference<PredictClient.Schema> cache = new AtomicReference<>();

	/** Lỗi theo khoá {@code extra.lam_sang.<cột>}; thông báo không chứa giá trị đã nhập. */
	public Map<String, String> validate(Object lamSang) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (lamSang == null) {
			return errors;
		}
		if (!(lamSang instanceof Map<?, ?> map)) {
			errors.put("extra." + KEY, "Thông tin lâm sàng phải là một đối tượng JSON {tên_cột: giá_trị}.");
			return errors;
		}
		Optional<PredictClient.Schema> schema = schema();
		if (schema.isEmpty()) {
			return errors;
		}
		Map<String, String> kind = schema.get().kind();
		Map<String, List<String>> levels = schema.get().levels();
		for (Map.Entry<?, ?> e : map.entrySet()) {
			String col = String.valueOf(e.getKey());
			String err = check(kind.get(col), levels.get(col), e.getValue());
			if (err != null) {
				errors.put("extra." + KEY + "." + col, err);
			}
		}
		return errors;
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
		return switch (kind) {
			case "so" -> parse(s) == null ? "Phải là số." : null;
			case "chu" -> s.length() > MAX_TEXT ? "Tối đa " + MAX_TEXT + " ký tự." : null;
			default -> allowed != null && matches(s, allowed) ? null
					: "Giá trị không thuộc danh sách cho phép: " + String.join(" | ", allowed == null ? List.of() : allowed);
		};
	}

	/** Như {@code _match_level} bên Python: 1 / "1" / "1.0" đều khớp mức "1". */
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
			.map(x -> new PredictClient.Schema(x.kind(), x.levels() == null ? Map.of() : x.levels()));
		fetched.ifPresent(cache::set);
		return fetched;
	}

}
