package com.sillicosis.backend.visit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/**
 * Dùng cho cả tạo và sửa (ghi đè toàn bộ). Khoảng giá trị khớp ràng buộc CHECK của bảng {@code visits}. Quy tắc liên
 * trường và giới hạn của {@code extra} kiểm tra ở service. {@code extra} khai báo là Object để báo lỗi rõ khi client
 * gửi mảng hoặc giá trị đơn thay vì object. {@code schemaVersion} do server quản lý. Mọi trường đều tuỳ chọn (một lần
 * gửi có thể chỉ có ảnh); {@code visitDate} trống = ngày gửi. {@code imageId} chỉ dùng khi tạo (gắn ảnh ngay trong lần
 * gửi); khi sửa thì bị bỏ qua, dùng PUT /visits/{id}/image.
 */
public record VisitRequest(
		@PastOrPresent(message = "Ngày khám không được ở tương lai.") LocalDate visitDate,
		@Size(max = 100, message = "Ngành nghề tối đa 100 ký tự.") String industry,
		@DecimalMin(value = "0", message = "Tuổi nghề từ 0 đến 60 năm.") @DecimalMax(value = "60", message = "Tuổi nghề từ 0 đến 60 năm.") BigDecimal yearsInJob,
		@Size(max = 50, message = "Tỉnh/thành tối đa 50 ký tự.") String province,
		@DecimalMin(value = "0", message = "Số năm phơi nhiễm từ 0 đến 60.") @DecimalMax(value = "60", message = "Số năm phơi nhiễm từ 0 đến 60.") BigDecimal yearsExposed,
		@DecimalMin(value = "100", message = "Chiều cao từ 100 đến 220 cm.") @DecimalMax(value = "220", message = "Chiều cao từ 100 đến 220 cm.") BigDecimal heightCm,
		@DecimalMin(value = "20", message = "Cân nặng từ 20 đến 200 kg.") @DecimalMax(value = "200", message = "Cân nặng từ 20 đến 200 kg.") BigDecimal weightKg,
		@Min(value = 60, message = "Huyết áp tâm thu từ 60 đến 260 mmHg.") @Max(value = 260, message = "Huyết áp tâm thu từ 60 đến 260 mmHg.") Integer systolicBp,
		@Min(value = 30, message = "Huyết áp tâm trương từ 30 đến 160 mmHg.") @Max(value = 160, message = "Huyết áp tâm trương từ 30 đến 160 mmHg.") Integer diastolicBp,
		@Min(value = 30, message = "Mạch từ 30 đến 220 lần/phút.") @Max(value = 220, message = "Mạch từ 30 đến 220 lần/phút.") Integer pulse,
		Boolean smoker,
		@DecimalMin(value = "0", message = "Số điếu mỗi ngày từ 0 đến 100.") @DecimalMax(value = "100", message = "Số điếu mỗi ngày từ 0 đến 100.") BigDecimal cigarettesPerDay,
		@DecimalMin(value = "0.3", message = "FVC từ 0,3 đến 8 lít.") @DecimalMax(value = "8", message = "FVC từ 0,3 đến 8 lít.") BigDecimal fvcL,
		@DecimalMin(value = "0.2", message = "FEV1 từ 0,2 đến 8 lít.") @DecimalMax(value = "8", message = "FEV1 từ 0,2 đến 8 lít.") BigDecimal fev1L,
		Object extra, UUID imageId) {

	@Override
	public String toString() {
		return "VisitRequest[***]";
	}

}
