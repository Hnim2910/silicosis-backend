package com.sillicosis.backend.prediction.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.sillicosis.backend.prediction.Prediction;
import com.sillicosis.backend.prediction.PredictionStatus;

/**
 * Một lượt chẩn đoán (model New Way, nhãn ketqua). {@code ketLuanCuoi}: có ảnh thì theo điểm ghép
 * ({@code pGhep >= nguongGhep}), không có ảnh thì theo điểm lâm sàng ({@code pLamSang >= nguongLamSang}). Khi status
 * FAILED chỉ có {@code errorCode}. {@code image} null khi không gửi ảnh; nội dung tải qua
 * {@code GET /api/predictions/{id}/image}.
 */
public record PredictionResponse(UUID id, Owner user, LocalDate examDate, Map<String, Object> lamSang,
		ImageInfo image, PredictionStatus status, String errorCode, Double pLamSang, Double nguongLamSang,
		Boolean baoNguyCoLamSang, Boolean coAnh, Double pAnh, Double pGhep, Double nguongGhep, Boolean baoNguyCoGhep,
		Boolean ketLuanCuoi, String modelName, String modelVersion, Instant createdAt, Instant updatedAt,
		boolean experimental, String disclaimer) {

	public static final String DISCLAIMER = "Công cụ hỗ trợ sàng lọc thử nghiệm; kết quả không thay thế "
			+ "chẩn đoán và đánh giá của bác sĩ.";

	public static PredictionResponse from(Prediction p, Owner owner) {
		ImageInfo image = p.hasImage()
				? new ImageInfo(p.getImageContentType(), p.getImageSizeBytes(), p.getImageWidth(), p.getImageHeight())
				: null;
		return new PredictionResponse(p.getId(), owner, p.getExamDate(), p.getLamSang(), image, p.getStatus(),
				p.getErrorCode(), p.getPLamSang(), p.getNguongLamSang(), p.getBaoNguyCoLamSang(), p.getCoAnh(),
				p.getPAnh(), p.getPGhep(), p.getNguongGhep(), p.getBaoNguyCoGhep(), p.getKetLuanCuoi(),
				p.getModelName(), p.getModelVersion(), p.getCreatedAt(), p.getUpdatedAt(), true, DISCLAIMER);
	}

	/** Không in chỉ số lâm sàng, kết quả hay họ tên. */
	@Override
	public String toString() {
		return "PredictionResponse[id=" + id + ", status=" + status + "]";
	}

	/** Người được chẩn đoán; đủ để danh sách của ADMIN hiển thị mà không phải gọi thêm. */
	public record Owner(UUID id, String code, String fullName) {

		@Override
		public String toString() {
			return "Owner[id=" + id + "]";
		}

	}

	public record ImageInfo(String contentType, long sizeBytes, int width, int height) {
	}

}
