package com.sillicosis.backend.auth.dto;

/**
 * @param resetToken token dùng một lần cho {@code /reset-password}
 * @param expiresIn số giây token còn hiệu lực
 */
public record VerifyOtpResponse(String resetToken, long expiresIn) {

	@Override
	public String toString() {
		return "VerifyOtpResponse[***]";
	}

}
