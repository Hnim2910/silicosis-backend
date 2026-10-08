package com.sillicosis.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(@NotBlank(message = "Thiếu refresh token.") String refreshToken) {

	@Override
	public String toString() {
		return "RefreshTokenRequest[***]";
	}

}
