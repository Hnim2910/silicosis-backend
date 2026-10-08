package com.sillicosis.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code identifier} là email hoặc số điện thoại. */
public record LoginRequest(
		@NotBlank(message = "Vui lòng nhập email hoặc số điện thoại.") String identifier,
		@NotBlank(message = "Vui lòng nhập mật khẩu.") String password) {

	@Override
	public String toString() {
		return "LoginRequest[***]";
	}

}
