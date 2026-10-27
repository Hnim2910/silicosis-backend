package com.sillicosis.backend.prediction;

import java.time.Instant;
import java.util.UUID;

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
 * Một lần gọi service dự đoán cho ảnh của lượt khám; lỗi cũng được ghi (status FAILED, kèm errorCode). Cố ý không
 * có toString() để kết quả không lọt vào log.
 */
@Entity
@Table(name = "predictions")
@Getter
@Setter
@NoArgsConstructor
public class Prediction {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "visit_id", nullable = false, updatable = false)
	private UUID visitId;

	@Column(name = "image_id", nullable = false, updatable = false)
	private UUID imageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PredictionStatus status;

	private Double probability;

	@Column(length = 10)
	private String label;

	private Double threshold;

	@Column(name = "model_name", length = 100)
	private String modelName;

	@Column(name = "model_version", length = 64)
	private String modelVersion;

	@Column(name = "error_code", length = 40)
	private String errorCode;

	@Column(name = "created_by")
	private UUID createdBy;

	/** Đặt từ Clock của ứng dụng để thứ tự "mới nhất" ổn định. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

}
