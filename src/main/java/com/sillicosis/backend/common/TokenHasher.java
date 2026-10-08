package com.sillicosis.backend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Sinh token/OTP ngẫu nhiên và băm SHA-256 để lưu DB (không bao giờ lưu token thô). */
public final class TokenHasher {

	private static final SecureRandom RANDOM = new SecureRandom();

	private TokenHasher() {
	}

	/** Token ngẫu nhiên 256 bit, mã hoá base64url không padding. */
	public static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Mã OTP 6 chữ số. */
	public static String newOtp() {
		return String.format("%06d", RANDOM.nextInt(1_000_000));
	}

	public static String sha256(String value) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
