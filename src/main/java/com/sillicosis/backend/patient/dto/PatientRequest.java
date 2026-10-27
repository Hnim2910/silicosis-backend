package com.sillicosis.backend.patient.dto;

import java.time.LocalDate;

import com.sillicosis.backend.common.VnPhone;
import com.sillicosis.backend.user.Gender;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

/**
 * Dùng cho cả tạo và sửa (ghi đè toàn bộ). Không có {@code code}, {@code userId}: hai trường này do server quản lý.
 * Độ dài {@code exposureHistory}/{@code riskFactors} kiểm tra ở service theo {@code app.patient.max-text-length}.
 */
public record PatientRequest(
		@NotBlank(message = "Vui lòng nhập họ tên.") @Size(min = 2, max = 150, message = "Họ tên dài 2–150 ký tự.") String fullName,
		@NotNull(message = "Vui lòng nhập ngày sinh.") @Past(message = "Ngày sinh phải ở quá khứ.") LocalDate birthDate,
		Gender gender,
		@VnPhone String phone,
		@NotBlank(message = "Vui lòng nhập nghề nghiệp.") @Size(max = 150, message = "Nghề nghiệp tối đa 150 ký tự.") String occupation,
		@Size(max = 255, message = "Nơi làm việc tối đa 255 ký tự.") String workplace,
		String exposureHistory,
		String riskFactors) {

	@Override
	public String toString() {
		return "PatientRequest[***]";
	}

}
