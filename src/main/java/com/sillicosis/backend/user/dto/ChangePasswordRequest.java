package com.sillicosis.backend.user.dto;

import com.sillicosis.backend.common.StrongPassword;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(@NotBlank(message = "Vui lòng nhập mật khẩu hiện tại.") String currentPassword,
		@StrongPassword String newPassword) {

	@Override
	public String toString() {
		return "ChangePasswordRequest[***]";
	}

}
