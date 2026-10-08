package com.sillicosis.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
		@NotBlank(message = "Vui lòng nhập email.") @Email(message = "Email không hợp lệ.") String email) {

	@Override
	public String toString() {
		return "ForgotPasswordRequest[***]";
	}

}
