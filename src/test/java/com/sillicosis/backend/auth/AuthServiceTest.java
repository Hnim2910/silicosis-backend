package com.sillicosis.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.sillicosis.backend.TestProps;
import com.sillicosis.backend.auth.dto.AuthResponse;
import com.sillicosis.backend.auth.dto.LoginRequest;
import com.sillicosis.backend.auth.dto.RegisterRequest;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.TokenHasher;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserAccounts;
import com.sillicosis.backend.user.UserRepository;

class AuthServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private static final String EMAIL = "benhnhan@example.com";

	private static final String PASSWORD = "matkhau123";

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

	private UserRepository userRepository;

	private UserAccounts userAccounts;

	private RefreshTokenRepository refreshTokenRepository;

	private TokenService tokenService;

	private AuthService authService;

	private User user;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		userAccounts = mock(UserAccounts.class);
		refreshTokenRepository = mock(RefreshTokenRepository.class);
		tokenService = mock(TokenService.class);
		authService = new AuthService(userRepository, userAccounts, refreshTokenRepository, tokenService, encoder,
				TestProps.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));

		user = new User();
		user.setId(UUID.randomUUID());
		user.setEmail(EMAIL);
		user.setFullName("Người Thử");
		user.setRole(Role.USER);
		user.setPasswordHash(encoder.encode(PASSWORD));
		when(userAccounts.findByIdentifier(EMAIL)).thenReturn(Optional.of(user));
		when(tokenService.issueTokens(any())).thenReturn(new AuthResponse("a", "r", "Bearer", 900, 604800, null));
	}

	// ---------- register ----------

	@Test
	void registerAlwaysCreatesUserWithNormalizedEmailAndPhone() {
		authService.register(new RegisterRequest(" Người Mới ", "  NguoiMoi@Example.COM ", "+84 912 345 678", PASSWORD));

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(saved.capture());
		User u = saved.getValue();
		assertThat(u.getRole()).isEqualTo(Role.USER);
		assertThat(u.getEmail()).isEqualTo("nguoimoi@example.com");
		assertThat(u.getPhone()).isEqualTo("0912345678");
		assertThat(u.getFullName()).isEqualTo("Người Mới");
		assertThat(u.getPasswordHash()).isNotEqualTo(PASSWORD);
		assertThat(encoder.matches(PASSWORD, u.getPasswordHash())).isTrue();
		verify(userAccounts).ensureUnique("nguoimoi@example.com", "0912345678", null);
		verify(tokenService).issueTokens(u);
	}

	@Test
	void registerRejectsDuplicateEmail() {
		doThrow(ApiException.conflict("EMAIL_TAKEN", "x")).when(userAccounts)
			.ensureUnique(eq("trung@example.com"), any(), any());

		assertThatThrownBy(() -> authService.register(new RegisterRequest("Ai Đó", "trung@example.com", null, PASSWORD)))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN");
			});
		verify(userRepository, never()).saveAndFlush(any());
	}

	// ---------- login ----------

	@Test
	void loginSucceedsAndClearsFailureCounter() {
		user.setFailedLoginAttempts(3);

		authService.login(new LoginRequest(EMAIL, PASSWORD));

		assertThat(user.getFailedLoginAttempts()).isZero();
		assertThat(user.getLockedUntil()).isNull();
		verify(tokenService).issueTokens(user);
	}

	@Test
	void unknownUserGetsSameErrorAsWrongPassword() {
		assertThatThrownBy(() -> authService.login(new LoginRequest("khong-co@example.com", PASSWORD)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS"));
		assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "saimatkhau1")))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS"));
	}

	@Test
	void fiveWrongPasswordsLockAccountForFifteenMinutes() {
		for (int i = 1; i <= 4; i++) {
			assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "saimatkhau1")))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
			assertThat(user.getFailedLoginAttempts()).isEqualTo(i);
		}

		assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "saimatkhau1")))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.LOCKED);
				assertThat(e.getCode()).isEqualTo("ACCOUNT_LOCKED");
			});
		assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));

		// Đang bị khoá thì mật khẩu đúng cũng bị từ chối
		assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_LOCKED"));
		verify(tokenService, never()).issueTokens(any());
	}

	@Test
	void expiredLockAllowsLogin() {
		user.setLockedUntil(NOW.minusSeconds(1));

		authService.login(new LoginRequest(EMAIL, PASSWORD));

		assertThat(user.getLockedUntil()).isNull();
		verify(tokenService).issueTokens(user);
	}

	@Test
	void disabledAccountCannotLogin() {
		user.setEnabled(false);

		assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
				assertThat(e.getCode()).isEqualTo("ACCOUNT_DISABLED");
			});
	}

	// ---------- refresh ----------

	private RefreshToken storedToken(String raw) {
		RefreshToken t = new RefreshToken();
		t.setUser(user);
		t.setTokenHash(TokenHasher.sha256(raw));
		t.setExpiresAt(NOW.plus(Duration.ofDays(1)));
		when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256(raw))).thenReturn(Optional.of(t));
		return t;
	}

	@Test
	void refreshRotatesToken() {
		RefreshToken t = storedToken("raw-token");

		authService.refresh("raw-token");

		assertThat(t.getRevokedAt()).isEqualTo(NOW);
		verify(tokenService).issueTokens(user);
	}

	@Test
	void reusingRevokedRefreshTokenRevokesAllSessions() {
		RefreshToken t = storedToken("raw-token");
		t.setRevokedAt(NOW.minusSeconds(10));

		assertThatThrownBy(() -> authService.refresh("raw-token"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REFRESH_TOKEN"));
		verify(refreshTokenRepository).revokeAllForUser(user.getId(), NOW);
		verify(tokenService, never()).issueTokens(any());
	}

	@Test
	void expiredOrUnknownRefreshTokenIsRejected() {
		storedToken("het-han").setExpiresAt(NOW.minusSeconds(1));

		assertThatThrownBy(() -> authService.refresh("het-han")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> authService.refresh("khong-ton-tai")).isInstanceOf(ApiException.class);
		verify(tokenService, never()).issueTokens(any());
	}

	@Test
	void logoutRevokesToken() {
		RefreshToken t = storedToken("raw-token");

		authService.logout("raw-token");
		authService.logout("khong-ton-tai");

		assertThat(t.getRevokedAt()).isEqualTo(NOW);
	}

}
