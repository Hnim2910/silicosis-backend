package com.sillicosis.backend.prediction.dto;

import java.time.Instant;
import java.util.UUID;

import com.sillicosis.backend.prediction.Prediction;
import com.sillicosis.backend.prediction.PredictionStatus;

/**
 * Kết quả thử nghiệm của model New Way (nhãn ketqua). {@code ketLuanCuoi}: có phim thì theo điểm ghép
 * ({@code pGhep >= nguongGhep}), không có phim thì theo điểm lâm sàng ({@code pLamSang >= nguongLamSang}).
 * {@code probability}/{@code threshold}/{@code label} giữ cho client cũ: bằng điểm, ngưỡng và kết luận cuối
 * ({@code label} "Co"/"Khong"). Khi status FAILED chỉ có {@code errorCode}.
 */
public record PredictionResponse(UUID id, UUID visitId, UUID imageId, PredictionStatus status, Double probability,
		String label, Double threshold, String modelName, String modelVersion, String errorCode, Instant createdAt,
		Double pLamSang, Double nguongLamSang, Boolean baoNguyCoLamSang, Boolean coAnh, Double pAnh, Double pGhep,
		Double nguongGhep, Boolean baoNguyCoGhep, Boolean ketLuanCuoi, boolean experimental, String disclaimer) {

	public static final String DISCLAIMER = "Công cụ hỗ trợ sàng lọc thử nghiệm, không phải chẩn đoán; "
			+ "không thay thế đánh giá của bác sĩ.";

	public static PredictionResponse from(Prediction p) {
		return new PredictionResponse(p.getId(), p.getVisitId(), p.getImageId(), p.getStatus(), p.getProbability(),
				p.getLabel(), p.getThreshold(), p.getModelName(), p.getModelVersion(), p.getErrorCode(),
				p.getCreatedAt(), p.getPLamSang(), p.getNguongLamSang(), p.getBaoNguyCoLamSang(), p.getCoAnh(),
				p.getPAnh(), p.getPGhep(), p.getNguongGhep(), p.getBaoNguyCoGhep(), p.getKetLuanCuoi(), true,
				DISCLAIMER);
	}

	@Override
	public String toString() {
		return "PredictionResponse[id=" + id + ", status=" + status + "]";
	}

}
