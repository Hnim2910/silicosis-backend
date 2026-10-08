package com.sillicosis.backend.common;

import java.util.regex.Pattern;

/** Chuẩn hoá và kiểm tra số điện thoại di động Việt Nam (dạng lưu: 0xxxxxxxxx). */
public final class PhoneNumbers {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-()]");
	private static final Pattern VN_MOBILE = Pattern.compile("^0[35789]\\d{8}$");

	private PhoneNumbers() {
	}

	/** Trả về dạng chuẩn 0xxxxxxxxx, hoặc null nếu đầu vào rỗng. Không kiểm tra hợp lệ. */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String s = SEPARATORS.matcher(raw.trim()).replaceAll("");
		if (s.isEmpty()) {
			return null;
		}
		if (s.startsWith("+84")) {
			s = "0" + s.substring(3);
		}
		else if (s.startsWith("84") && s.length() == 11) {
			s = "0" + s.substring(2);
		}
		return s;
	}

	public static boolean isValid(String raw) {
		String s = normalize(raw);
		return s != null && VN_MOBILE.matcher(s).matches();
	}

}
