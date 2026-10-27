package com.sillicosis.backend.visit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.sillicosis.backend.image.Image;
import com.sillicosis.backend.patient.Patient;
import com.sillicosis.backend.prediction.dto.PredictionResponse;
import com.sillicosis.backend.visit.Visit;

/**
 * {@code ageAtVisit} (từ ngày sinh của hồ sơ) và {@code bmi} chỉ tính khi trả về, không lưu DB; null nếu thiếu dữ
 * liệu. {@code image} null khi chưa gắn ảnh. {@code latestPrediction}: kết quả mới nhất cho ảnh đang gắn, null nếu
 * chưa có ảnh hoặc ảnh chưa được dự đoán.
 */
public record VisitResponse(UUID id, UUID patientId, PatientSummary patient, LocalDate visitDate, String industry,
		BigDecimal yearsInJob, String province, BigDecimal yearsExposed, BigDecimal heightCm, BigDecimal weightKg,
		Integer systolicBp, Integer diastolicBp, Integer pulse, Boolean smoker, BigDecimal cigarettesPerDay,
		BigDecimal fvcL, BigDecimal fev1L, Map<String, Object> extra, int schemaVersion, Integer ageAtVisit,
		BigDecimal bmi, VisitImage image, PredictionResponse latestPrediction, Instant createdAt, Instant updatedAt) {

	public static VisitResponse from(Visit v, Patient patient, Integer ageAtVisit, BigDecimal bmi, Image image,
			PredictionResponse latestPrediction) {
		return new VisitResponse(v.getId(), v.getPatientId(), PatientSummary.from(patient), v.getVisitDate(),
				v.getIndustry(), v.getYearsInJob(), v.getProvince(), v.getYearsExposed(), v.getHeightCm(),
				v.getWeightKg(), v.getSystolicBp(), v.getDiastolicBp(), v.getPulse(), v.getSmoker(),
				v.getCigarettesPerDay(), v.getFvcL(), v.getFev1L(), v.getExtra(), v.getSchemaVersion(), ageAtVisit,
				bmi, image == null ? null : VisitImage.from(image), latestPrediction, v.getCreatedAt(),
				v.getUpdatedAt());
	}

	public VisitResponse withLatestPrediction(PredictionResponse prediction) {
		return new VisitResponse(id, patientId, patient, visitDate, industry, yearsInJob, province, yearsExposed,
				heightCm, weightKg, systolicBp, diastolicBp, pulse, smoker, cigarettesPerDay, fvcL, fev1L, extra,
				schemaVersion, ageAtVisit, bmi, image, prediction, createdAt, updatedAt);
	}

	/** Không in chỉ số lâm sàng hay họ tên. */
	@Override
	public String toString() {
		return "VisitResponse[id=" + id + "]";
	}

	/** Đủ để danh sách lượt khám (ADMIN) hiển thị bệnh nhân mà không phải gọi thêm. */
	public record PatientSummary(UUID id, String code, String fullName) {

		static PatientSummary from(Patient p) {
			return new PatientSummary(p.getId(), p.getCode(), p.getFullName());
		}

		@Override
		public String toString() {
			return "PatientSummary[id=" + id + "]";
		}

	}

	/** Thông tin ảnh đã gắn; nội dung tải qua {@code GET /api/images/{id}/content}. */
	public record VisitImage(UUID id, String contentType, long sizeBytes, int width, int height) {

		static VisitImage from(Image image) {
			return new VisitImage(image.getId(), image.getContentType(), image.getSizeBytes(), image.getWidth(),
					image.getHeight());
		}

	}

}
