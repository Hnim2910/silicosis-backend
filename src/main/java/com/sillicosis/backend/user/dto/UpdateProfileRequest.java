package com.sillicosis.backend.user.dto;

import java.time.LocalDate;

import com.sillicosis.backend.common.VnPhone;
import com.sillicosis.backend.user.Gender;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
		@NotBlank(message = "Vui lòng nhập họ tên.") @Size(min = 2, max = 150, message = "Họ tên dài 2–150 ký tự.") String fullName,
		@NotBlank(message = "Vui lòng nhập email.") @Email(message = "Email không hợp lệ.") @Size(max = 255) String email,
		@VnPhone String phone,
		@Past(message = "Ngày sinh phải ở quá khứ.") LocalDate birthDate,
		Gender gender,
		@Size(max = 150, message = "Nghề nghiệp tối đa 150 ký tự.") String occupation) {

	@Override
	public String toString() {
		return "UpdateProfileRequest[***]";
	}

}
