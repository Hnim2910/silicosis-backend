package com.sillicosis.backend.visit;

import java.math.BigDecimal;
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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lượt khám (bản demo, bộ trường sẽ chỉnh theo từ điển chính thức). Tuổi và giới tính lấy từ hồ sơ, không lưu ở
 * đây. Cố ý không có toString() để tránh lộ chỉ số lâm sàng vào log.
 */
@Entity
@Table(name = "visits")
@Getter
@Setter
@NoArgsConstructor
public class Visit {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "patient_id", nullable = false, updatable = false)
	private UUID patientId;

	@Column(name = "visit_date", nullable = false)
	private LocalDate visitDate;

	@Column(length = 100)
	private String industry;

	@Column(name = "years_in_job", precision = 4, scale = 1)
	private BigDecimal yearsInJob;

	@Column(length = 50)
	private String province;

	@Column(name = "years_exposed", precision = 4, scale = 1)
	private BigDecimal yearsExposed;

	@Column(name = "height_cm", precision = 5, scale = 1)
	private BigDecimal heightCm;

	@Column(name = "weight_kg", precision = 5, scale = 1)
	private BigDecimal weightKg;

	@Column(name = "systolic_bp")
	private Integer systolicBp;

	@Column(name = "diastolic_bp")
	private Integer diastolicBp;

	private Integer pulse;

	private Boolean smoker;

	@Column(name = "cigarettes_per_day", precision = 4, scale = 1)
	private BigDecimal cigarettesPerDay;

	@Column(name = "fvc_l", precision = 4, scale = 2)
	private BigDecimal fvcL;

	@Column(name = "fev1_l", precision = 4, scale = 2)
	private BigDecimal fev1L;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> extra = new LinkedHashMap<>();

	@Column(name = "schema_version", nullable = false)
	private int schemaVersion = CURRENT_SCHEMA_VERSION;

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
