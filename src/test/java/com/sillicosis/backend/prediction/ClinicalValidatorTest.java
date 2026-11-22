package com.sillicosis.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Kiểm {@code lamSang} theo danh sách cột của mô hình PG; schema và dữ liệu đều là dữ liệu giả. */
class ClinicalValidatorTest {

	private static final PredictClient.Schema SCHEMA = new PredictClient.Schema(
			Map.of("ho", "muc", "fvctt", "so", "cviec", "chu", "gioitinh", "muc", "bnn", "muc"),
			Map.of("ho", List.of("Co", "Khong"), "gioitinh", List.of("Nam", "Nu"), "bnn", List.of("0", "1")),
			ClinicalValidator.TINH, List.of("bmi", "fev1_fvc", "cviec_lv"));

	private static ClinicalValidator validator(Optional<PredictClient.Schema> schema) {
		PredictClient client = mock(PredictClient.class);
		when(client.schema()).thenReturn(schema);
		return new ClinicalValidator(client);
	}

	@Test
	void validInputPasses() {
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("tinh", "Thai Nguyen");
		in.put("ho", "Co");
		in.put("fvctt", "3,2");
		in.put("cviec", "cong nhan");
		in.put("bnn", 1);
		in.put("gioitinh", "");
		assertThat(validator(Optional.of(SCHEMA)).validate(in)).isEmpty();
	}

	@Test
	void tinhIsRequiredAndMustBeOneOfFiveEvenWithoutSchema() {
		ClinicalValidator v = validator(Optional.empty());
		assertThat(v.validate(null)).containsOnlyKeys("lamSang.tinh");
		assertThat(v.validate(Map.of("ho", "Co"))).containsOnlyKeys("lamSang.tinh");
		assertThat(v.validate(Map.of("tinh", " "))).containsOnlyKeys("lamSang.tinh");
		assertThat(v.validate(Map.of("tinh", "Ha Noi"))).containsOnlyKeys("lamSang.tinh");
		assertThat(v.validate(Map.of("tinh", "Thái Nguyên"))).containsOnlyKeys("lamSang.tinh");
		assertThat(v.validate(Map.of("tinh", "thai nguyen"))).containsOnlyKeys("lamSang.tinh");
		assertThat(v.validate(Map.of("tinh", 1))).containsOnlyKeys("lamSang.tinh");
		for (String t : ClinicalValidator.TINH) {
			assertThat(v.validate(Map.of("tinh", t))).isEmpty();
		}
	}

	@Test
	void unknownDerivedAndInvalidValuesAreRejected() {
		Map<String, String> errors = validator(Optional.of(SCHEMA)).validate(Map.of("tinh", "Phu Yen", "fvc", "3",
				"bmi", 22, "cviec_lv", "lv1", "ho", "Có", "fvctt", "abc", "bnn", 2, "cviec", "x".repeat(501)));
		assertThat(errors).containsOnlyKeys("lamSang.fvc", "lamSang.bmi", "lamSang.cviec_lv", "lamSang.ho",
				"lamSang.fvctt", "lamSang.bnn", "lamSang.cviec");
		assertThat(errors.get("lamSang.fvc")).isEqualTo("Trường không có trong mô hình.");
		assertThat(errors.get("lamSang.bmi")).contains("tự tính");
		// Thông báo không chứa giá trị người dùng nhập
		assertThat(errors.values()).noneMatch(m -> m.contains("Có") || m.contains("abc"));
	}

	@Test
	void notAnObjectIsRejected() {
		assertThat(validator(Optional.of(SCHEMA)).validate(List.of(1, 2))).containsOnlyKeys("lamSang");
	}

	@Test
	void resultValidation() {
		var clinical = new PredictClient.Result("ketqua", 0.3, List.of(0.2, 0.4), false, "Hai Duong", null, List.of());
		assertThat(clinical.isValid(false)).isTrue();
		assertThat(clinical.isValid(true)).isFalse();
		var image = new PredictClient.Result("ketqua", 0.3, List.of(0.3), true, "Hai Duong", Map.of("RU", 0.5),
				List.of("Cảnh báo thử"));
		assertThat(image.isValid(true)).isTrue();
		assertThat(new PredictClient.Result("ketqua", 1.7, List.of(0.3), false, "x", null, List.of()).isValid(false))
			.isFalse();
		assertThat(new PredictClient.Result("ketqua", 0.3, List.of(), false, "x", null, List.of()).isValid(false))
			.isFalse();
		assertThat(new PredictClient.Result("ketqua", 0.3, List.of(0.3), false, "x", Map.of("RU", 0.5), List.of())
			.isValid(false)).isFalse();
		assertThat(new PredictClient.Result("ketqua", 0.3, List.of(0.3), false, "x", null, null).isValid(false))
			.isFalse();
	}

}
