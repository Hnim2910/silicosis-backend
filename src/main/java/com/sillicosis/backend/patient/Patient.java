package com.sillicosis.backend.patient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sillicosis.backend.user.Gender;

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
 * Hồ sơ bệnh nhân. {@code userId} null khi ADMIN tạo hồ sơ không gắn tài khoản. Cố ý không có toString() để
 * tránh lộ thông tin cá nhân vào log.
 */
@Entity
@Table(name = "patients")
@Getter
@Setter
@NoArgsConstructor
public class Patient {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/** Mã hiển thị BN-000001, sinh từ patient_code_seq. */
	@Column(nullable = false, updatable = false, length = 20)
	private String code;

	@Column(name = "user_id", updatable = false)
	private UUID userId;

	@Column(name = "full_name", nullable = false, length = 150)
	private String fullName;

	@Column(name = "birth_date")
	private LocalDate birthDate;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private Gender gender;

	/** Dạng chuẩn 0xxxxxxxxx; không duy nhất. */
	@Column(length = 20)
	private String phone;

	@Column(length = 150)
	private String occupation;

	private String workplace;

	@Column(name = "exposure_history")
	private String exposureHistory;

	@Column(name = "risk_factors")
	private String riskFactors;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

}
