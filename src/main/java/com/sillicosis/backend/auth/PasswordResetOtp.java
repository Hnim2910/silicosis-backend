package com.sillicosis.backend.auth;

import java.time.Instant;
import java.util.UUID;

import com.sillicosis.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần yêu cầu quên mật khẩu. OTP được băm bằng BCrypt; reset token (sau khi xác minh OTP) băm SHA-256.
 */
@Entity
@Table(name = "password_reset_otp")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetOtp {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "otp_hash", nullable = false)
	private String otpHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(nullable = false)
	private int attempts;

	@Column(nullable = false)
	private boolean used;

	@Column(name = "reset_token_hash", length = 64)
	private String resetTokenHash;

	@Column(name = "reset_token_expires_at")
	private Instant resetTokenExpiresAt;

	/** Gán từ Clock của ứng dụng để tính khoảng gửi lại OTP. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

}
