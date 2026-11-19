package com.sillicosis.backend.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiểu sử bệnh nhân lưu trong {@code users.tieu_su}: các trường ổn định qua nhiều lần khám, cùng mã cột và mã giá
 * trị với form lâm sàng của model (nhóm "Nghề nghiệp &amp; phơi nhiễm" và "Hút thuốc &amp; tiền sử"). Giới tính và
 * năm sinh không nằm ở đây mà lấy từ {@link User#getGender()} / {@link User#getBirthDate()}.
 * <p>
 * Danh sách cố định ở backend (không phụ thuộc service FastAPI) để hồ sơ luôn kiểm tra được.
 */
public final class TieuSu {

	public static final String KEY = "tieuSu";

	static final int MAX_TEXT = 500;

	private sealed interface Rule permits Text, Number, Choice {
	}

	private record Text() implements Rule {
	}

	/** Số trong [min, max], chấp nhận dấu phẩy thập phân. */
	private record Number(double min, double max) implements Rule {
	}

	private record Choice(List<String> codes) implements Rule {
	}

	private static final Rule TEXT = new Text();
	private static final Rule YEARS = new Number(0, 70);
	private static final Rule CO_KHONG = new Choice(List.of("Co", "Khong"));

	/** Theo thứ tự trên form. */
	private static final Map<String, Rule> RULES = new LinkedHashMap<>();

	static {
		RULES.put("cviec", TEXT);
		RULES.put("pxuong", TEXT);
		RULES.put("tuoinghe", YEARS);
		RULES.put("nampx", YEARS);
		RULES.put("cv5nam", CO_KHONG);
		RULES.put("cviec1", TEXT);
		RULES.put("tgian1", YEARS);
		RULES.put("cviec2", TEXT);
		RULES.put("tgian2", YEARS);
		RULES.put("hutthuoc", new Choice(List.of("Co hut thuoc", "Khong hut thuoc")));
		RULES.put("slthuoc", new Number(0, 1000));
		RULES.put("tiensuhh", CO_KHONG);
		RULES.put("benhhh", TEXT);
		RULES.put("tsnoi", TEXT);
		RULES.put("tsngoai", TEXT);
	}

	private TieuSu() {
	}

	public static boolean isField(String key) {
		return RULES.containsKey(key);
	}

	/** Lỗi theo khoá {@code tieuSu.<cột>}; thông báo không chứa giá trị đã nhập. */
	public static Map<String, String> validate(Map<String, Object> tieuSu) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (tieuSu == null) {
			return errors;
		}
		for (Map.Entry<String, Object> e : tieuSu.entrySet()) {
			String err = check(RULES.get(e.getKey()), e.getValue());
			if (err != null) {
				errors.put(KEY + "." + e.getKey(), err);
			}
		}
		return errors;
	}

	/**
	 * Bản đã chuẩn hoá để lưu: bỏ giá trị rỗng, cắt khoảng trắng, số thành {@code Double}/{@code Long}. Gọi sau
	 * {@link #validate}.
	 */
	public static Map<String, Object> normalize(Map<String, Object> tieuSu) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (tieuSu == null) {
			return out;
		}
		for (String key : RULES.keySet()) {
			Object v = tieuSu.get(key);
			if (v == null || String.valueOf(v).isBlank()) {
				continue;
			}
			String s = String.valueOf(v).strip();
			if (RULES.get(key) instanceof Number) {
				double f = parse(s);
				out.put(key, f == Math.rint(f) ? (Object) (long) f : (Object) f);
			}
			else {
				out.put(key, s);
			}
		}
		return out;
	}

	private static String check(Rule rule, Object v) {
		if (rule == null) {
			return "Trường không thuộc tiểu sử.";
		}
		if (v == null || v instanceof String s && s.isBlank()) {
			return null;
		}
		if (v instanceof Map<?, ?> || v instanceof List<?> || v instanceof Boolean) {
			return "Kiểu giá trị không hợp lệ.";
		}
		String s = String.valueOf(v).strip();
		return switch (rule) {
			case Text t -> s.codePointCount(0, s.length()) > MAX_TEXT ? "Tối đa " + MAX_TEXT + " ký tự." : null;
			case Number n -> {
				Double f = parse(s);
				if (f == null) {
					yield "Phải là số.";
				}
				yield f < n.min() || f > n.max() ? "Phải trong khoảng " + fmt(n.min()) + "–" + fmt(n.max()) + "." : null;
			}
			case Choice c -> c.codes().contains(s) ? null
					: "Giá trị không thuộc danh sách cho phép: " + String.join(" | ", c.codes());
		};
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

	private static String fmt(double d) {
		return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
	}

}
