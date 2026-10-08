package com.sillicosis.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
		@NotBlank(message = "Vui lòng nhập email.") @Email(message = "Email không hợp lệ.") String email,
		@NotBlank(message = "Vui lòng nhập mã OTP.") @Pattern(regexp = "^\\d{6}$", message = "Mã OTP gồm 6 chữ số.") String otp) {

	@Override
	public String toString() {
		return "VerifyOtpRequest[***]";
	}

}
