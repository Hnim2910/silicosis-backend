package com.sillicosis.backend.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Cấu hình riêng của ứng dụng (tiền tố {@code app.}). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(@DefaultValue Jwt jwt, @DefaultValue Security security, @DefaultValue Otp otp,
		@DefaultValue Mail mail, @DefaultValue Admin admin, @DefaultValue Cors cors, @DefaultValue Storage storage,
		@DefaultValue Patient patient, @DefaultValue Visit visit, @DefaultValue Predict predict) {

	/**
	 * @param secret khoá HMAC-SHA256, tối thiểu 32 byte
	 */
	public record Jwt(String secret, @DefaultValue("silicocare") String issuer,
			@DefaultValue("15m") Duration accessTokenTtl, @DefaultValue("7d") Duration refreshTokenTtl) {
	}

	public record Security(@DefaultValue("5") int maxFailedLogins, @DefaultValue("15m") Duration lockDuration) {
	}

	public record Otp(@DefaultValue("5m") Duration ttl, @DefaultValue("5") int maxAttempts,
			@DefaultValue("60s") Duration resendInterval, @DefaultValue("15m") Duration resetTokenTtl) {
	}

	public record Mail(@DefaultValue("SilicoCare <no-reply@silicocare.local>") String from) {
	}

	/** Tài khoản admin seed lúc khởi động; bỏ qua nếu thiếu email hoặc mật khẩu. */
	public record Admin(String email, String password, @DefaultValue("Quản trị viên") String fullName) {
	}

	public record Cors(@DefaultValue({ "http://localhost:3000", "http://localhost:8081", "http://localhost:19006",
			"exp://*" }) List<String> allowedOriginPatterns) {
	}

	public record Storage(String imagesDir, @DefaultValue("10MB") DataSize maxUploadSize,
		@DefaultValue("256") int minShortSide, @DefaultValue("8000") int maxDimension) {
	}

	/** @param maxTextLength giới hạn ký tự cho exposureHistory, riskFactors */
	public record Patient(@DefaultValue("2000") int maxTextLength) {
	}

	/**
	 * @param extraMaxSize kích thước tối đa của {@code extra} khi viết thành JSON
	 * @param extraMaxDepth số cấp lồng tối đa, tính cả chính {@code extra}
	 */
	public record Visit(@DefaultValue("8KB") DataSize extraMaxSize, @DefaultValue("3") int extraMaxDepth) {
	}

	/**
	 * Service dự đoán FastAPI (thư mục predict/).
	 * @param enabled false: không tự dự đoán khi gắn ảnh; gọi lại thủ công trả 503 PREDICT_DISABLED
	 * @param timeout thời gian chờ phản hồi (CPU mất khoảng 4 giây một ảnh)
	 */
	public record Predict(@DefaultValue("true") boolean enabled,
			@DefaultValue("http://localhost:8001") String baseUrl, @DefaultValue("30s") Duration timeout,
			@DefaultValue("3s") Duration connectTimeout) {
	}

}
