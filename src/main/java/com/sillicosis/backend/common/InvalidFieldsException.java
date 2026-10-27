package com.sillicosis.backend.common;

import java.util.Map;

import lombok.Getter;

/** Lỗi kiểm tra dữ liệu phát hiện ở service (quy tắc phụ thuộc cấu hình); trả 400 {@code VALIDATION_FAILED}. */
@Getter
public class InvalidFieldsException extends RuntimeException {

	private final Map<String, String> fieldErrors;

	public InvalidFieldsException(Map<String, String> fieldErrors) {
		super("Dữ liệu không hợp lệ.");
		this.fieldErrors = Map.copyOf(fieldErrors);
	}

}
