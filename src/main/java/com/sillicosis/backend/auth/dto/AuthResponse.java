package com.sillicosis.backend.auth.dto;

import com.sillicosis.backend.user.dto.UserResponse;

/**
 * @param expiresIn số giây access token còn hiệu lực
 * @param refreshExpiresIn số giây refresh token còn hiệu lực
 */
public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
		long refreshExpiresIn, UserResponse user) {

	@Override
	public String toString() {
		return "AuthResponse[***]";
	}

}
