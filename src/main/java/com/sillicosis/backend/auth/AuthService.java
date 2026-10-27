package com.sillicosis.backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sillicosis.backend.auth.dto.AuthResponse;
import com.sillicosis.backend.auth.dto.LoginRequest;
import com.sillicosis.backend.auth.dto.RegisterRequest;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.PhoneNumbers;
import com.sillicosis.backend.common.TokenHasher;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.patient.PatientService;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserAccounts;
import com.sillicosis.backend.user.UserRepository;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final UserAccounts userAccounts;
	private final RefreshTokenRepository refreshTokenRepository;
	private final TokenService tokenService;
	private final PatientService patientService;
	private final PasswordEncoder passwordEncoder;
	private final AppProperties props;
	private final Clock clock;

	/** Hash giả để thời gian phản hồi khi tài khoản không tồn tại tương đương khi sai mật khẩu. */
	private final String dummyHash;

	public AuthService(UserRepository userRepository, UserAccounts userAccounts,
			RefreshTokenRepository refreshTokenRepository, TokenService tokenService, PatientService patientService,
			PasswordEncoder passwordEncoder, AppProperties props, Clock clock) {
		this.userRepository = userRepository;
		this.userAccounts = userAccounts;
		this.refreshTokenRepository = refreshTokenRepository;
		this.tokenService = tokenService;
		this.patientService = patientService;
		this.passwordEncoder = passwordEncoder;
		this.props = props;
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode(TokenHasher.newToken());
	}

	/**
	 * Tự đăng ký: luôn là USER (bệnh nhân), kèm hồ sơ bệnh nhân trong cùng transaction. Đăng ký xong trả luôn token
	 * (đăng nhập tự động).
	 */
	@Transactional
	public AuthResponse register(RegisterRequest req) {
		String email = UserAccounts.normalizeEmail(req.email());
		String phone = PhoneNumbers.normalize(req.phone());
		userAccounts.ensureUnique(email, phone, null);

		User user = new User();
		user.setEmail(email);
		user.setPhone(phone);
		user.setFullName(req.fullName().trim());
		user.setPasswordHash(passwordEncoder.encode(req.password()));
		user.setRole(Role.USER);
		userRepository.saveAndFlush(user);
		patientService.createForUser(user, user.getId());
		return tokenService.issueTokens(user);
	}

	/**
	 * Đăng nhập bằng email hoặc SĐT. Sai {@code maxFailedLogins} lần liên tiếp thì khoá {@code lockDuration}.
	 * Không rollback khi ném ApiException để bộ đếm sai được lưu lại.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public AuthResponse login(LoginRequest req) {
		Instant now = clock.instant();
		User user = userAccounts.findByIdentifier(req.identifier()).orElse(null);
		if (user == null) {
			passwordEncoder.matches(req.password(), dummyHash);
			throw invalidCredentials();
		}
		if (user.isLockedAt(now)) {
			throw locked(user.getLockedUntil(), now);
		}
		if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
			int failures = user.getFailedLoginAttempts() + 1;
			if (failures >= props.security().maxFailedLogins()) {
				user.setFailedLoginAttempts(0);
				user.setLockedUntil(now.plus(props.security().lockDuration()));
				throw locked(user.getLockedUntil(), now);
			}
			user.setFailedLoginAttempts(failures);
			throw invalidCredentials();
		}
		if (!user.isEnabled()) {
			throw ApiException.forbidden("ACCOUNT_DISABLED", "Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.");
		}
		user.clearLoginFailures();
		return tokenService.issueTokens(user);
	}

	/**
	 * Đổi refresh token cũ lấy cặp token mới (rotation). Nếu token đã bị thu hồi mà vẫn được dùng lại — dấu
	 * hiệu bị lộ — thu hồi toàn bộ phiên của user.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public AuthResponse refresh(String refreshToken) {
		Instant now = clock.instant();
		RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(refreshToken))
			.orElseThrow(AuthService::invalidRefreshToken);
		User user = stored.getUser();
		if (stored.getRevokedAt() != null) {
			refreshTokenRepository.revokeAllForUser(user.getId(), now);
			throw invalidRefreshToken();
		}
		if (!stored.isActiveAt(now)) {
			throw invalidRefreshToken();
		}
		stored.setRevokedAt(now);
		if (!user.isEnabled() || user.isDeleted()) {
			throw ApiException.forbidden("ACCOUNT_DISABLED", "Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.");
		}
		return tokenService.issueTokens(user);
	}

	/** Thu hồi refresh token; luôn thành công kể cả khi token không tồn tại. */
	@Transactional
	public void logout(String refreshToken) {
		refreshTokenRepository.findByTokenHash(TokenHasher.sha256(refreshToken))
			.filter(t -> t.getRevokedAt() == null)
			.ifPresent(t -> t.setRevokedAt(clock.instant()));
	}

	private static ApiException invalidCredentials() {
		return ApiException.unauthorized("INVALID_CREDENTIALS",
				"Email, số điện thoại hoặc mật khẩu không chính xác.");
	}

	private static ApiException invalidRefreshToken() {
		return ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.");
	}

	private static ApiException locked(Instant until, Instant now) {
		long minutes = Math.max(1, (Duration.between(now, until).toSeconds() + 59) / 60);
		return new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
				"Tài khoản tạm khoá do đăng nhập sai nhiều lần. Vui lòng thử lại sau " + minutes + " phút.");
	}

}
