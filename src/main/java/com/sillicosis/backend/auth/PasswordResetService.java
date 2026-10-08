package com.sillicosis.backend.auth;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sillicosis.backend.auth.dto.VerifyOtpResponse;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.TokenHasher;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.mail.OtpMailRequested;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserAccounts;
import com.sillicosis.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Quên mật khẩu: forgot-password → verify-otp → reset-password.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

	/** Trả cho mọi email (có hay không tồn tại) để tránh dò tài khoản. */
	public static final String GENERIC_MESSAGE = "Nếu email đã được đăng ký, mã OTP đã được gửi tới hộp thư. "
			+ "Mã có hiệu lực trong 5 phút.";

	private final UserRepository userRepository;
	private final PasswordResetOtpRepository otpRepository;
	private final TokenService tokenService;
	private final PasswordEncoder passwordEncoder;
	private final ApplicationEventPublisher events;
	private final AppProperties props;
	private final Clock clock;

	/**
	 * Sinh OTP và gửi mail (bất đồng bộ, sau khi commit). Im lặng bỏ qua nếu email không tồn tại, tài khoản bị khoá,
	 * hoặc yêu cầu trước chưa quá {@code resendInterval}.
	 */
	@Transactional
	public void requestOtp(String rawEmail) {
		Instant now = clock.instant();
		User user = userRepository.findByEmailAndDeletedAtIsNull(UserAccounts.normalizeEmail(rawEmail))
			.filter(User::isEnabled)
			.orElse(null);
		if (user == null) {
			return;
		}
		boolean tooSoon = otpRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
			.map(last -> last.getCreatedAt().plus(props.otp().resendInterval()).isAfter(now))
			.orElse(false);
		if (tooSoon) {
			return;
		}
		otpRepository.invalidateAllForUser(user.getId());

		String otp = TokenHasher.newOtp();
		PasswordResetOtp record = new PasswordResetOtp();
		record.setUser(user);
		record.setOtpHash(passwordEncoder.encode(otp));
		record.setExpiresAt(now.plus(props.otp().ttl()));
		record.setCreatedAt(now);
		otpRepository.save(record);

		events.publishEvent(new OtpMailRequested(user.getEmail(), user.getFullName(), otp,
				props.otp().ttl().toMinutes()));
	}

	/** Kiểm tra OTP mới nhất; tối đa {@code maxAttempts} lần thử. Thành công → reset token dùng một lần. */
	@Transactional(noRollbackFor = ApiException.class)
	public VerifyOtpResponse verifyOtp(String rawEmail, String otp) {
		Instant now = clock.instant();
		User user = userRepository.findByEmailAndDeletedAtIsNull(UserAccounts.normalizeEmail(rawEmail))
			.orElseThrow(PasswordResetService::invalidOtp);
		PasswordResetOtp record = otpRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
			.orElseThrow(PasswordResetService::invalidOtp);
		int maxAttempts = props.otp().maxAttempts();
		if (record.isUsed() || !record.getExpiresAt().isAfter(now) || record.getAttempts() >= maxAttempts) {
			throw invalidOtp();
		}
		record.setAttempts(record.getAttempts() + 1);
		if (!passwordEncoder.matches(otp, record.getOtpHash())) {
			if (record.getAttempts() >= maxAttempts) {
				record.setUsed(true);
				throw ApiException.badRequest("OTP_ATTEMPTS_EXCEEDED",
						"Bạn đã nhập sai quá số lần cho phép. Vui lòng yêu cầu mã OTP mới.");
			}
			throw invalidOtp();
		}
		record.setUsed(true);
		String resetToken = TokenHasher.newToken();
		record.setResetTokenHash(TokenHasher.sha256(resetToken));
		record.setResetTokenExpiresAt(now.plus(props.otp().resetTokenTtl()));
		return new VerifyOtpResponse(resetToken, props.otp().resetTokenTtl().toSeconds());
	}

	/** Đặt mật khẩu mới, huỷ reset token, mở khoá đăng nhập và thu hồi mọi refresh token. */
	@Transactional
	public void resetPassword(String resetToken, String newPassword) {
		Instant now = clock.instant();
		PasswordResetOtp record = otpRepository.findByResetTokenHash(TokenHasher.sha256(resetToken))
			.filter(r -> r.getResetTokenExpiresAt() != null && r.getResetTokenExpiresAt().isAfter(now))
			.orElseThrow(PasswordResetService::invalidResetToken);
		User user = record.getUser();
		if (user.isDeleted() || !user.isEnabled()) {
			throw invalidResetToken();
		}
		record.setResetTokenHash(null);
		record.setResetTokenExpiresAt(null);
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.clearLoginFailures();
		otpRepository.invalidateAllForUser(user.getId());
		tokenService.revokeAll(user.getId());
	}

	private static ApiException invalidOtp() {
		return ApiException.badRequest("INVALID_OTP", "Mã OTP không đúng hoặc đã hết hạn.");
	}

	private static ApiException invalidResetToken() {
		return ApiException.badRequest("INVALID_RESET_TOKEN",
				"Phiên đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng thực hiện lại.");
	}

}
