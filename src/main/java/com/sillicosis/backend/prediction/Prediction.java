package com.sillicosis.backend.prediction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
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
 * Một lượt chẩn đoán: đầu vào lâm sàng, ảnh X-quang (tuỳ chọn) và kết quả model New Way. Đầu vào không sửa được —
 * đổi thông tin thì tạo lượt mới; chỉ phần kết quả được ghi lại khi thử lại một lượt FAILED. Cố ý không có toString()
 * để chỉ số lâm sàng và kết quả không lọt vào log.
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

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "exam_date", nullable = false, updatable = false)
	private LocalDate examDate;

	/** {tên_cột: giá_trị} theo predict/ml/new_way/MO_TA_API.md, đã kèm giới tính/năm sinh chép từ hồ sơ. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "lam_sang", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> lamSang = new LinkedHashMap<>();

	@Column(name = "image_storage_key", updatable = false, length = 100)
	private String imageStorageKey;

	@Column(name = "image_content_type", updatable = false, length = 30)
	private String imageContentType;

	@Column(name = "image_size_bytes", updatable = false)
	private Long imageSizeBytes;

	@Column(name = "image_width", updatable = false)
	private Integer imageWidth;

	@Column(name = "image_height", updatable = false)
	private Integer imageHeight;

	@Column(name = "image_sha256", updatable = false, length = 64)
	private String imageSha256;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PredictionStatus status;

	@Column(name = "error_code", length = 40)
	private String errorCode;

	@Column(name = "p_lam_sang")
	private Double pLamSang;

	@Column(name = "nguong_lam_sang")
	private Double nguongLamSang;

	@Column(name = "bao_nguy_co_lam_sang")
	private Boolean baoNguyCoLamSang;

	@Column(name = "co_anh")
	private Boolean coAnh;

	@Column(name = "p_anh")
	private Double pAnh;

	@Column(name = "p_ghep")
	private Double pGhep;

	@Column(name = "nguong_ghep")
	private Double nguongGhep;

	@Column(name = "bao_nguy_co_ghep")
	private Boolean baoNguyCoGhep;

	@Column(name = "ket_luan_cuoi")
	private Boolean ketLuanCuoi;

	@Column(name = "model_name", length = 100)
	private String modelName;

	@Column(name = "model_version", length = 64)
	private String modelVersion;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** Đặt từ Clock của ứng dụng để thứ tự "mới nhất" ổn định. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public boolean hasImage() {
		return imageStorageKey != null;
	}

	/** Ghi kết quả thành công; xoá mã lỗi của lần thử trước (nếu có). */
	void applySuccess(PredictClient.Outcome outcome) {
		PredictClient.Result r = outcome.result();
		status = PredictionStatus.SUCCESS;
		errorCode = null;
		pLamSang = r.pLamSang();
		nguongLamSang = r.nguongLamSang();
		baoNguyCoLamSang = r.baoNguyCoLamSang();
		coAnh = r.coAnh();
		pAnh = r.pAnh();
		pGhep = r.pGhep();
		nguongGhep = r.nguongGhep();
		baoNguyCoGhep = r.baoNguyCoGhep();
		ketLuanCuoi = r.ketLuanCuoi();
		modelName = outcome.modelName();
		modelVersion = outcome.modelVersion();
	}

	void applyFailure(String code) {
		status = PredictionStatus.FAILED;
		errorCode = code;
		pLamSang = null;
		nguongLamSang = null;
		baoNguyCoLamSang = null;
		coAnh = null;
		pAnh = null;
		pGhep = null;
		nguongGhep = null;
		baoNguyCoGhep = null;
		ketLuanCuoi = null;
		modelName = null;
		modelVersion = null;
	}

}
