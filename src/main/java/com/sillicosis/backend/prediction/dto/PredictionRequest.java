package com.sillicosis.backend.prediction.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.PastOrPresent;

/**
 * Phần {@code data} (JSON) của {@code POST /api/predictions}; ảnh gửi ở phần {@code file}. {@code examDate} trống =
 * ngày gửi. {@code lamSang} = {tên_cột: giá_trị} (mã không dấu, xem predict/ml/pg/MO_TA_API.md; bắt buộc có
 * {@code tinh}); khai báo là Object để báo lỗi rõ khi client gửi mảng hoặc giá trị đơn. Giới tính và năm sinh lấy từ hồ sơ, không cần gửi.
 */
public record PredictionRequest(@PastOrPresent(message = "Ngày khám không được ở tương lai.") LocalDate examDate,
		Object lamSang) {

	@Override
	public String toString() {
		return "PredictionRequest[***]";
	}

}
