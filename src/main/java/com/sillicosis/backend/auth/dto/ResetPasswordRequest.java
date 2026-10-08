package com.sillicosis.backend.auth.dto;

import com.sillicosis.backend.common.StrongPassword;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(@NotBlank(message = "Thiếu reset token.") String resetToken,
		@StrongPassword String newPassword) {

	@Override
	public String toString() {
		return "ResetPasswordRequest[***]";
	}

}
