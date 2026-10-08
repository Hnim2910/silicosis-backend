package com.sillicosis.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.sillicosis.backend.TestProps;
import com.sillicosis.backend.auth.dto.VerifyOtpResponse;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.TokenHasher;
import com.sillicosis.backend.mail.OtpMailRequested;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

class PasswordResetServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private static final String EMAIL = "benhnhan@example.com";

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

	private UserRepository userRepository;

	private PasswordResetOtpRepository otpRepository;

	private TokenService tokenService;

	private ApplicationEventPublisher events;

	private User user;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		otpRepository = mock(PasswordResetOtpRepository.class);
		tokenService = mock(TokenService.class);
		events = mock(ApplicationEventPublisher.class);

		user = new User();
		user.setId(UUID.randomUUID());
		user.setEmail(EMAIL);
		user.setFullName("Người Thử");
		user.setRole(Role.USER);
		user.setPasswordHash(encoder.encode("matkhaucu1"));
		when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
	}

	private PasswordResetService serviceAt(Instant now) {
		return new PasswordResetService(userRepository, otpRepository, tokenService, encoder, events,
				TestProps.defaults(), Clock.fixed(now, ZoneOffset.UTC));
	}

	private PasswordResetOtp latestOtp(String rawOtp, Instant createdAt) {
		PasswordResetOtp otp = new PasswordResetOtp();
		otp.setUser(user);
		otp.setOtpHash(encoder.encode(rawOtp));
		otp.setCreatedAt(createdAt);
		otp.setExpiresAt(createdAt.plusSeconds(300));
		when(otpRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(otp));
		return otp;
	}

	// ---------- forgot-password ----------

	@Test
	void unknownEmailIsSilentlyIgnored() {
		serviceAt(NOW).requestOtp("khong-co@example.com");

		verify(otpRepository, never()).save(any());
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void disabledUserGetsNoOtp() {
		user.setEnabled(false);

		serviceAt(NOW).requestOtp(EMAIL);

		verify(otpRepository, never()).save(any());
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void otpIsStoredHashedAndMailedAfterNormalizingEmail() {
		serviceAt(NOW).requestOtp("  BenhNhan@Example.com ");

		ArgumentCaptor<PasswordResetOtp> saved = ArgumentCaptor.forClass(PasswordResetOtp.class);
		verify(otpRepository).save(saved.capture());
		ArgumentCaptor<OtpMailRequested> mail = ArgumentCaptor.forClass(OtpMailRequested.class);
		verify(events).publishEvent(mail.capture());

		String rawOtp = mail.getValue().otp();
		assertThat(rawOtp).matches("\\d{6}");
		assertThat(saved.getValue().getOtpHash()).isNotEqualTo(rawOtp).doesNotContain(rawOtp);
		assertThat(encoder.matches(rawOtp, saved.getValue().getOtpHash())).isTrue();
		assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
		assertThat(mail.getValue().email()).isEqualTo(EMAIL);
		verify(otpRepository).invalidateAllForUser(user.getId());
	}

	@Test
	void resendWithinSixtySecondsIsIgnored() {
		latestOtp("123456", NOW.minusSeconds(30));

		serviceAt(NOW).requestOtp(EMAIL);

		verify(otpRepository, never()).save(any());
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void resendAfterSixtySecondsIsAllowed() {
		latestOtp("123456", NOW.minusSeconds(61));

		serviceAt(NOW).requestOtp(EMAIL);

		verify(otpRepository).save(any());
		verify(events).publishEvent(any(OtpMailRequested.class));
	}

	// ---------- verify-otp ----------

	@Test
	void correctOtpReturnsOneTimeResetToken() {
		PasswordResetOtp otp = latestOtp("123456", NOW.minusSeconds(10));

		VerifyOtpResponse res = serviceAt(NOW).verifyOtp(EMAIL, "123456");

		assertThat(otp.isUsed()).isTrue();
		assertThat(otp.getResetTokenHash()).isEqualTo(TokenHasher.sha256(res.resetToken()));
		assertThat(otp.getResetTokenExpiresAt()).isEqualTo(NOW.plusSeconds(900));
		// Dùng lại cùng OTP lần hai bị từ chối
		assertThatThrownBy(() -> serviceAt(NOW).verifyOtp(EMAIL, "123456"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_OTP"));
	}

	@Test
	void fiveWrongAttemptsBurnTheOtp() {
		PasswordResetOtp otp = latestOtp("123456", NOW.minusSeconds(10));
		PasswordResetService service = serviceAt(NOW);

		for (int i = 1; i <= 4; i++) {
			assertThatThrownBy(() -> service.verifyOtp(EMAIL, "000000"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_OTP"));
		}
		assertThatThrownBy(() -> service.verifyOtp(EMAIL, "000000"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("OTP_ATTEMPTS_EXCEEDED"));
		assertThat(otp.getAttempts()).isEqualTo(5);
		assertThat(otp.isUsed()).isTrue();

		// OTP đúng cũng không dùng được nữa
		assertThatThrownBy(() -> service.verifyOtp(EMAIL, "123456")).isInstanceOf(ApiException.class);
		assertThat(otp.getResetTokenHash()).isNull();
	}

	@Test
	void expiredOtpIsRejected() {
		latestOtp("123456", NOW.minusSeconds(301));

		assertThatThrownBy(() -> serviceAt(NOW).verifyOtp(EMAIL, "123456"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_OTP"));
	}

	@Test
	void unknownEmailOnVerifyGivesGenericError() {
		assertThatThrownBy(() -> serviceAt(NOW).verifyOtp("khong-co@example.com", "123456"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_OTP"));
	}

	// ---------- reset-password ----------

	@Test
	void resetPasswordChangesHashAndRevokesSessions() {
		PasswordResetOtp otp = latestOtp("123456", NOW.minusSeconds(10));
		otp.setResetTokenHash(TokenHasher.sha256("reset-token"));
		otp.setResetTokenExpiresAt(NOW.plusSeconds(60));
		when(otpRepository.findByResetTokenHash(TokenHasher.sha256("reset-token"))).thenReturn(Optional.of(otp));
		user.setFailedLoginAttempts(4);

		serviceAt(NOW).resetPassword("reset-token", "matkhaumoi1");

		assertThat(encoder.matches("matkhaumoi1", user.getPasswordHash())).isTrue();
		assertThat(user.getFailedLoginAttempts()).isZero();
		assertThat(otp.getResetTokenHash()).isNull();
		verify(otpRepository).invalidateAllForUser(user.getId());
		verify(tokenService).revokeAll(user.getId());
	}

	@Test
	void expiredResetTokenIsRejected() {
		PasswordResetOtp otp = latestOtp("123456", NOW.minusSeconds(1000));
		otp.setResetTokenHash(TokenHasher.sha256("reset-token"));
		otp.setResetTokenExpiresAt(NOW.minusSeconds(1));
		when(otpRepository.findByResetTokenHash(TokenHasher.sha256("reset-token"))).thenReturn(Optional.of(otp));

		assertThatThrownBy(() -> serviceAt(NOW).resetPassword("reset-token", "matkhaumoi1"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_RESET_TOKEN"));
		verify(tokenService, never()).revokeAll(any());
	}

}
