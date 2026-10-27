package com.sillicosis.backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.sillicosis.backend.TestProps;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.InvalidFieldsException;
import com.sillicosis.backend.image.Image;
import com.sillicosis.backend.image.ImageRepository;
import com.sillicosis.backend.patient.Patient;
import com.sillicosis.backend.patient.PatientService;
import com.sillicosis.backend.prediction.PredictionService;
import com.sillicosis.backend.user.Gender;
import com.sillicosis.backend.visit.dto.VisitRequest;
import com.sillicosis.backend.visit.dto.VisitResponse;

import tools.jackson.databind.json.JsonMapper;

/** Dữ liệu đều là dữ liệu giả. */
class VisitServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private final UUID actorId = UUID.randomUUID();

	private VisitRepository visitRepository;

	private ImageRepository imageRepository;

	private PatientService patientService;

	private VisitService visitService;

	private Patient patient;

	@BeforeEach
	void setUp() {
		visitRepository = mock(VisitRepository.class);
		imageRepository = mock(ImageRepository.class);
		patientService = mock(PatientService.class);
		visitService = new VisitService(visitRepository, imageRepository, patientService,
				mock(PredictionService.class), JsonMapper.builder().build(),
				TestProps.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));

		patient = new Patient();
		patient.setId(UUID.randomUUID());
		patient.setGender(Gender.MALE);
		patient.setBirthDate(LocalDate.of(1980, 6, 15));
		when(patientService.requireAccessible(eq(actorId), anyBoolean(), eq(patient.getId()))).thenReturn(patient);
		when(visitRepository.saveAndFlush(any())).thenAnswer(inv -> {
			Visit v = inv.getArgument(0);
			if (v.getId() == null) {
				v.setId(UUID.randomUUID());
			}
			return v;
		});
	}

	// ---------- Tuổi và BMI ----------

	@Test
	void ageAtVisitCountsFullYears() {
		LocalDate birth = LocalDate.of(1980, 6, 15);
		assertThat(VisitService.ageAt(birth, LocalDate.of(2025, 6, 14))).isEqualTo(44);
		assertThat(VisitService.ageAt(birth, LocalDate.of(2025, 6, 15))).isEqualTo(45);
		assertThat(VisitService.ageAt(LocalDate.of(2000, 2, 29), LocalDate.of(2021, 2, 28))).isEqualTo(20);
		assertThat(VisitService.ageAt(null, LocalDate.of(2025, 1, 1))).isNull();
	}

	@Test
	void bmiRoundsToOneDecimal() {
		assertThat(VisitService.bmi(new BigDecimal("170"), new BigDecimal("65"))).isEqualByComparingTo("22.5");
		assertThat(VisitService.bmi(new BigDecimal("160"), new BigDecimal("50.5"))).isEqualByComparingTo("19.7");
		assertThat(VisitService.bmi(null, new BigDecimal("65"))).isNull();
		assertThat(VisitService.bmi(new BigDecimal("170"), null)).isNull();
	}

	@Test
	void createComputesAgeAndBmiAndDropsCigarettesForNonSmoker() {
		VisitResponse res = visitService.create(actorId, false, patient.getId(),
				request(b -> b.smoker(false).cigarettesPerDay(new BigDecimal("10"))));

		assertThat(res.ageAtVisit()).isEqualTo(45);
		assertThat(res.bmi()).isEqualByComparingTo("22.5");
		assertThat(res.cigarettesPerDay()).isNull();
		assertThat(res.schemaVersion()).isEqualTo(Visit.CURRENT_SCHEMA_VERSION);
		assertThat(res.extra()).isEmpty();
		assertThat(res.image()).isNull();

		VisitResponse smoker = visitService.create(actorId, false, patient.getId(),
				request(b -> b.smoker(true).cigarettesPerDay(new BigDecimal("10"))));
		assertThat(smoker.cigarettesPerDay()).isEqualByComparingTo("10");
	}

	// ---------- Quy tắc liên trường ----------

	@Test
	void crossFieldRulesReportFieldErrors() {
		assertThatThrownBy(() -> visitService.create(actorId, false, patient.getId(),
				request(b -> b.systolicBp(80).diastolicBp(80).fvcL(new BigDecimal("3.0")).fev1L(new BigDecimal("3.1"))
					.visitDate(LocalDate.of(1970, 1, 1)))))
			.isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.getFieldErrors())
				.containsOnlyKeys("systolicBp", "fev1L", "visitDate"));

		// Chỉ một trong hai giá trị thì không kiểm tra; bằng nhau ở FEV1/FVC là hợp lệ
		VisitResponse ok = visitService.create(actorId, false, patient.getId(),
				request(b -> b.systolicBp(120).diastolicBp(null).fvcL(new BigDecimal("3.0")).fev1L(new BigDecimal("3.0"))));
		assertThat(ok.systolicBp()).isEqualTo(120);
	}

	@Test
	void extraMustBeSmallShallowObject() {
		assertThatThrownBy(() -> visitService.create(actorId, false, patient.getId(),
				request(b -> b.extra(List.of(1, 2)))))
			.isInstanceOfSatisfying(InvalidFieldsException.class,
					ex -> assertThat(ex.getFieldErrors()).containsOnlyKeys("extra"));
		assertThatThrownBy(() -> visitService.create(actorId, false, patient.getId(),
				request(b -> b.extra(Map.of("a", Map.of("b", Map.of("c", Map.of("d", 1))))))))
			.isInstanceOf(InvalidFieldsException.class);
		assertThatThrownBy(() -> visitService.create(actorId, false, patient.getId(),
				request(b -> b.extra(Map.of("note", "x".repeat(9000))))))
			.isInstanceOf(InvalidFieldsException.class);

		VisitResponse ok = visitService.create(actorId, false, patient.getId(),
				request(b -> b.extra(Map.of("a", Map.of("b", List.of(1, 2))))));
		assertThat(ok.extra()).containsKey("a");
	}

	@Test
	void submissionNeedsImageOrClinicalDataAndDefaultsToToday() {
		// Rỗng → từ chối
		assertThatThrownBy(() -> visitService.create(actorId, false, patient.getId(), emptySubmission(null, null)))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("EMPTY_SUBMISSION"));

		// Chỉ có một chỉ số; hồ sơ thiếu ngày sinh vẫn lưu được (tuổi để trống); ngày khám = ngày gửi
		patient.setBirthDate(null);
		patient.setGender(null);
		VisitResponse res = visitService.create(actorId, false, patient.getId(), emptySubmission(new BigDecimal("60"), null));
		assertThat(res.visitDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(res.ageAtVisit()).isNull();
		assertThat(res.industry()).isNull();
	}

	@Test
	void imageOnlySubmissionAttachesImageInSameCall() {
		Image image = image(actorId, null);
		when(imageRepository.findByIdAndDeletedAtIsNull(image.getId())).thenReturn(Optional.of(image));
		when(imageRepository.findByVisitIdAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

		VisitResponse res = visitService.create(actorId, false, patient.getId(), emptySubmission(null, image.getId()));
		assertThat(res.image().id()).isEqualTo(image.getId());
		assertThat(image.getVisitId()).isEqualTo(res.id());
	}

	// ---------- Gắn / gỡ ảnh ----------

	@Test
	void attachAndDetachImage() {
		Visit visit = existingVisit();
		Image image = image(actorId, null);
		when(imageRepository.findByIdAndDeletedAtIsNull(image.getId())).thenReturn(Optional.of(image));
		when(imageRepository.findByVisitIdAndDeletedAtIsNull(visit.getId())).thenReturn(Optional.empty());

		VisitResponse res = visitService.attachImage(actorId, false, visit.getId(), image.getId());
		assertThat(image.getVisitId()).isEqualTo(visit.getId());
		assertThat(res.image().id()).isEqualTo(image.getId());

		// Gắn lại đúng ảnh đó: không lỗi
		when(imageRepository.findByVisitIdAndDeletedAtIsNull(visit.getId())).thenReturn(Optional.of(image));
		visitService.attachImage(actorId, false, visit.getId(), image.getId());

		visitService.detachImage(actorId, false, visit.getId());
		assertThat(image.getVisitId()).isNull();
		assertThat(image.getDeletedAt()).isNull();
	}

	@Test
	void imageAttachedElsewhereOrVisitAlreadyHasImageIsConflict() {
		Visit visit = existingVisit();
		Image elsewhere = image(actorId, UUID.randomUUID());
		when(imageRepository.findByIdAndDeletedAtIsNull(elsewhere.getId())).thenReturn(Optional.of(elsewhere));
		assertThatThrownBy(() -> visitService.attachImage(actorId, false, visit.getId(), elsewhere.getId()))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo("IMAGE_ALREADY_ATTACHED"));

		Image free = image(actorId, null);
		when(imageRepository.findByIdAndDeletedAtIsNull(free.getId())).thenReturn(Optional.of(free));
		when(imageRepository.findByVisitIdAndDeletedAtIsNull(visit.getId()))
			.thenReturn(Optional.of(image(actorId, visit.getId())));
		assertThatThrownBy(() -> visitService.attachImage(actorId, false, visit.getId(), free.getId()))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo("VISIT_ALREADY_HAS_IMAGE"));
		assertThat(free.getVisitId()).isNull();
	}

	@Test
	void otherUsersImageIsNotFoundButAdminMayAttachIt() {
		Visit visit = existingVisit();
		Image foreign = image(UUID.randomUUID(), null);
		when(imageRepository.findByIdAndDeletedAtIsNull(foreign.getId())).thenReturn(Optional.of(foreign));
		when(imageRepository.findByVisitIdAndDeletedAtIsNull(visit.getId())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> visitService.attachImage(actorId, false, visit.getId(), foreign.getId()))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
		visitService.attachImage(actorId, true, visit.getId(), foreign.getId());
		assertThat(foreign.getVisitId()).isEqualTo(visit.getId());
	}

	@Test
	void deleteSoftDeletesVisitAndDetachesImage() {
		Visit visit = existingVisit();
		Image image = image(actorId, visit.getId());
		when(imageRepository.findByVisitIdAndDeletedAtIsNull(visit.getId())).thenReturn(Optional.of(image));

		visitService.delete(actorId, false, visit.getId());
		assertThat(visit.getDeletedAt()).isEqualTo(NOW);
		assertThat(image.getVisitId()).isNull();
		assertThat(image.getDeletedAt()).isNull();
	}

	@Test
	void visitOfInaccessiblePatientIsVisitNotFound() {
		Visit visit = existingVisit();
		UUID stranger = UUID.randomUUID();
		when(patientService.requireAccessible(stranger, false, patient.getId()))
			.thenThrow(new ApiException(HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", "x"));
		assertThatThrownBy(() -> visitService.get(stranger, false, visit.getId()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("VISIT_NOT_FOUND"));
		assertThatThrownBy(() -> visitService.get(actorId, false, UUID.randomUUID()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("VISIT_NOT_FOUND"));
	}

	// ---------- Tiện ích ----------

	private Visit existingVisit() {
		Visit visit = new Visit();
		visit.setId(UUID.randomUUID());
		visit.setPatientId(patient.getId());
		visit.setVisitDate(LocalDate.of(2025, 6, 20));
		when(visitRepository.findByIdAndDeletedAtIsNull(visit.getId())).thenReturn(Optional.of(visit));
		return visit;
	}

	private static Image image(UUID ownerId, UUID visitId) {
		Image image = new Image();
		image.setId(UUID.randomUUID());
		image.setOwnerId(ownerId);
		image.setVisitId(visitId);
		image.setContentType("image/png");
		return image;
	}

	private static VisitRequest emptySubmission(BigDecimal weightKg, UUID imageId) {
		return new VisitRequest(null, null, null, null, null, null, weightKg, null, null, null, null, null, null, null,
				null, imageId);
	}

	private static VisitRequest request(java.util.function.Consumer<Builder> customize) {
		Builder b = new Builder();
		customize.accept(b);
		return b.build();
	}

	/** Lượt khám hợp lệ mặc định; test chỉnh từng trường. */
	private static final class Builder {

		private LocalDate visitDate = LocalDate.of(2025, 6, 20);
		private Integer systolicBp = 120;
		private Integer diastolicBp = 80;
		private Boolean smoker;
		private BigDecimal cigarettesPerDay;
		private BigDecimal fvcL;
		private BigDecimal fev1L;
		private Object extra;

		Builder visitDate(LocalDate v) { visitDate = v; return this; }
		Builder systolicBp(Integer v) { systolicBp = v; return this; }
		Builder diastolicBp(Integer v) { diastolicBp = v; return this; }
		Builder smoker(Boolean v) { smoker = v; return this; }
		Builder cigarettesPerDay(BigDecimal v) { cigarettesPerDay = v; return this; }
		Builder fvcL(BigDecimal v) { fvcL = v; return this; }
		Builder fev1L(BigDecimal v) { fev1L = v; return this; }
		Builder extra(Object v) { extra = v; return this; }

		VisitRequest build() {
			return new VisitRequest(visitDate, "Khai thác đá (giả)", new BigDecimal("10"), "Tỉnh giả định",
					new BigDecimal("8"), new BigDecimal("170"), new BigDecimal("65"), systolicBp, diastolicBp, 75, smoker,
					cigarettesPerDay, fvcL, fev1L, extra, null);
		}

	}

}
