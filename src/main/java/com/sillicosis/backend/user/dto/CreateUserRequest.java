package com.sillicosis.backend.user.dto;

import com.sillicosis.backend.common.StrongPassword;
import com.sillicosis.backend.common.VnPhone;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
		@NotBlank(message = "Vui lòng nhập họ tên.") @Size(min = 2, max = 150, message = "Họ tên dài 2–150 ký tự.") String fullName,
		@NotBlank(message = "Vui lòng nhập email.") @Email(message = "Email không hợp lệ.") @Size(max = 255) String email,
		@VnPhone String phone,
		@StrongPassword String password) {

	@Override
	public String toString() {
		return "CreateUserRequest[***]";
	}

}
