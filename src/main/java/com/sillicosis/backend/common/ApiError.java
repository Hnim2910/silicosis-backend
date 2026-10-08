package com.sillicosis.backend.common;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Định dạng lỗi thống nhất cho mọi API. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
		Instant timestamp,
		int status,
		String code,
		String message,
		String path,
		Map<String, String> fieldErrors) {

	public static ApiError of(int status, String code, String message, String path) {
		return new ApiError(Instant.now(), status, code, message, path, Map.of());
	}

}
