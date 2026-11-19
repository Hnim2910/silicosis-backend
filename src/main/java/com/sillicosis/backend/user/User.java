package com.sillicosis.backend.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tài khoản người dùng. Cố ý không có toString() để tránh lộ thông tin cá nhân vào log.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/** Luôn lưu chữ thường. */
	@Column(nullable = false)
	private String email;

	/** Dạng chuẩn 0xxxxxxxxx, có thể null. */
	private String phone;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "birth_date")
	private LocalDate birthDate;

	@Enumerated(EnumType.STRING)
	private Gender gender;

	// ---------- Hồ sơ bệnh nhân (chỉ USER) ----------

	/** Mã bệnh nhân BN-000001, sinh từ user_code_seq khi tạo tài khoản USER; null với ADMIN. */
	@Column(updatable = false, length = 20)
	private String code;

	/** Tiểu sử: mã cột → giá trị như form lâm sàng (xem {@link TieuSu}); rỗng nếu chưa điền. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "tieu_su", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> tieuSu = new LinkedHashMap<>();

	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isLockedAt(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void clearLoginFailures() {
		failedLoginAttempts = 0;
		lockedUntil = null;
	}

}
