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

	/** FastAPI trả 422: dữ liệu lâm sàng sai (mã phân loại lạ, không phải số…). */
	static final String INVALID_INPUT = "PREDICT_INVALID_INPUT";

	private final String code;

	PredictException(String code) {
		super(code, null, false, false);
		this.code = code;
	}

}
