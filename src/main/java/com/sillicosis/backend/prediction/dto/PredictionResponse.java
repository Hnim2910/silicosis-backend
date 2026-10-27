package com.sillicosis.backend.prediction.dto;

import java.time.Instant;
import java.util.UUID;

import com.sillicosis.backend.prediction.Prediction;
import com.sillicosis.backend.prediction.PredictionStatus;

/**
 * Kết quả thử nghiệm. {@code probability} là xác suất lớp "Co" (có bệnh bụi phổi silic); {@code label} = "Co" khi
 * {@code probability >= threshold}. Khi status FAILED chỉ có {@code errorCode}.
 */
public record PredictionResponse(UUID id, UUID visitId, UUID imageId, PredictionStatus status, Double probability,
		String label, Double threshold, String modelName, String modelVersion, String errorCode, Instant createdAt,
		boolean experimental, String disclaimer) {

	public static final String DISCLAIMER = "Kết quả thử nghiệm, không thay thế chẩn đoán của bác sĩ.";

	public static PredictionResponse from(Prediction p) {
		return new PredictionResponse(p.getId(), p.getVisitId(), p.getImageId(), p.getStatus(), p.getProbability(),
				p.getLabel(), p.getThreshold(), p.getModelName(), p.getModelVersion(), p.getErrorCode(),
				p.getCreatedAt(), true, DISCLAIMER);
	}

	@Override
	public String toString() {
		return "PredictionResponse[id=" + id + ", status=" + status + "]";
	}

}
