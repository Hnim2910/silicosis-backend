package com.sillicosis.backend.prediction;

import lombok.Getter;

/** Gọi service dự đoán không thành công; {@code code} được lưu vào predictions.error_code. */
@Getter
class PredictException extends RuntimeException {

	static final String UNAVAILABLE = "PREDICT_UNAVAILABLE";

	static final String TIMEOUT = "PREDICT_TIMEOUT";

	static final String BAD_RESPONSE = "PREDICT_BAD_RESPONSE";

	static final String IMAGE_REJECTED = "PREDICT_IMAGE_REJECTED";

	static final String IMAGE_UNREADABLE = "PREDICT_IMAGE_UNREADABLE";

	private final String code;

	PredictException(String code) {
		super(code, null, false, false);
		this.code = code;
	}

}
