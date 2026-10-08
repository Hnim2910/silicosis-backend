package com.sillicosis.backend.mail;

/** Sự kiện yêu cầu gửi OTP; chỉ tồn tại trong bộ nhớ, toString() không lộ dữ liệu. */
public record OtpMailRequested(String email, String fullName, String otp, long ttlMinutes) {

	@Override
	public String toString() {
		return "OtpMailRequested[***]";
	}

}
