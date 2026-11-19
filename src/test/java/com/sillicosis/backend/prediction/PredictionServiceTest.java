package com.sillicosis.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.sillicosis.backend.TestProps;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.image.ImageProcessor;
import com.sillicosis.backend.image.ImageStorage;
import com.sillicosis.backend.prediction.dto.PredictionRequest;
import com.sillicosis.backend.prediction.dto.PredictionResponse;
import com.sillicosis.backend.user.Gender;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

import tools.jackson.databind.json.JsonMapper;

/** Quy tắc của lượt chẩn đoán không cần DB hay service dự đoán thật; dữ liệu giả. */
class PredictionServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");

	private PredictionRepository predictionRepository;

	private PredictClient predictClient;

	private ClinicalValidator clinicalValidator;

	private UserRepository userRepository;

	private User user;

	@BeforeEach
	void setUp() {
		predictionRepository = mock(PredictionRepository.class);
		predictClient = mock(PredictClient.class);
		clinicalValidator = mock(ClinicalValidator.class);
		userRepository = mock(UserRepository.class);
		when(clinicalValidator.validate(any())).thenReturn(Map.of());
		when(predictionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		user = new User();
		user.setId(UUID.randomUUID());
		user.setRole(Role.USER);
		user.setEnabled(true);
		user.setFullName("Người Thử");
		when(userRepository.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));
	}

	private PredictionService service(boolean predictEnabled) {
		AppProperties d = TestProps.defaults();
		AppProperties props = new AppProperties(d.jwt(), d.security(), d.otp(), d.mail(), d.admin(), d.cors(),
				d.storage(), d.prediction(), new AppProperties.Predict(predictEnabled,
						d.predict().baseUrl(), d.predict().timeout(), d.predict().connectTimeout()));
		return new PredictionService(predictionRepository, userRepository,
				clinicalValidator, new ImageProcessor(props), mock(ImageStorage.class), predictClient,
				JsonMapper.builder().build(), props, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void birthYearComesFromProfileButSentGenderIsKept() {
		user.setGender(Gender.FEMALE);
		user.setBirthDate(LocalDate.of(1980, 5, 6));
		Map<String, Object> sent = new LinkedHashMap<>(Map.of("gioitinh", "Nam", "namsinh", 1999, "ho", "Co"));

		Map<String, Object> input = PredictionService.withProfile(sent, user);

		assertThat(input).containsEntry("gioitinh", "Nam").containsEntry("namsinh", 1980).containsEntry("ho", "Co");
		assertThat(sent).containsEntry("namsinh", 1999);
	}

	@Test
	void missingGenderIsFilledFromProfile() {
		user.setGender(Gender.FEMALE);
		Map<String, Object> input = PredictionService.withProfile(Map.of("gioitinh", " ", "ho", "Co"), user);
		assertThat(input).containsEntry("gioitinh", "Nu");
	}

	@Test
	void profileWithoutGenderKeepsClientValue() {
		user.setGender(Gender.OTHER);
		Map<String, Object> input = PredictionService.withProfile(Map.of("gioitinh", "Nam"), user);
		assertThat(input).containsEntry("gioitinh", "Nam").doesNotContainKey("namsinh");
	}

	@Test
	void blankValuesDoNotCountAsInput() {
		Map<String, Object> blank = new LinkedHashMap<>();
		blank.put("ho", "  ");
		blank.put("fvc", null);
		assertThat(PredictionService.hasValue(blank)).isFalse();
		assertThat(PredictionService.hasValue(Map.of("fvc", 3.2))).isTrue();
	}

	@Test
	void disabledPredictionIsStillSavedAsFailed() {
		PredictionResponse r = service(false).create(user.getId(),
				new PredictionRequest(null, Map.of("ho", "Co")), null);

		assertThat(r.status()).isEqualTo(PredictionStatus.FAILED);
		assertThat(r.errorCode()).isEqualTo("PREDICT_DISABLED");
		assertThat(r.examDate()).isEqualTo(LocalDate.of(2026, 11, 18));
		assertThat(r.createdAt()).isEqualTo(NOW);
		verify(predictClient, never()).predict(any(), any(), any());
	}

	@Test
	void emptySubmissionIsRejectedWithoutSaving() {
		assertThatThrownBy(() -> service(true).create(user.getId(), new PredictionRequest(null, Map.of()), null))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMPTY_SUBMISSION"));
		verify(predictionRepository, never()).saveAndFlush(any());
	}

	@Test
	void examDateBeforeBirthDateIsRejected() {
		user.setBirthDate(LocalDate.of(1990, 1, 1));
		assertThatThrownBy(() -> service(true).create(user.getId(),
				new PredictionRequest(LocalDate.of(1989, 12, 31), Map.of("ho", "Co")), null))
			.isInstanceOf(com.sillicosis.backend.common.InvalidFieldsException.class);
	}

	@Test
	void disabledAccountCannotSubmit() {
		user.setEnabled(false);
		assertThatThrownBy(() -> service(true).create(user.getId(),
				new PredictionRequest(null, Map.of("ho", "Co")), null))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
				assertThat(e.getCode()).isEqualTo("ACCOUNT_DISABLED");
			});
	}

	@Test
	void retryOnlyForFailedAndNeedsPredictionEnabled() {
		Prediction ok = new Prediction();
		ok.setId(UUID.randomUUID());
		ok.setUserId(user.getId());
		ok.setStatus(PredictionStatus.SUCCESS);
		when(predictionRepository.findByIdAndDeletedAtIsNull(ok.getId())).thenReturn(Optional.of(ok));
		assertThatThrownBy(() -> service(true).retry(user.getId(), false, ok.getId()))
			.isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.getCode()).isEqualTo("PREDICTION_NOT_FAILED"));

		Prediction failed = new Prediction();
		failed.setId(UUID.randomUUID());
		failed.setUserId(user.getId());
		failed.setStatus(PredictionStatus.FAILED);
		when(predictionRepository.findByIdAndDeletedAtIsNull(failed.getId())).thenReturn(Optional.of(failed));
		assertThatThrownBy(() -> service(false).retry(user.getId(), false, failed.getId()))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PREDICT_DISABLED"));
	}

	@Test
	void otherUsersPredictionLooksLikeItDoesNotExist() {
		Prediction other = new Prediction();
		other.setId(UUID.randomUUID());
		other.setUserId(UUID.randomUUID());
		other.setStatus(PredictionStatus.SUCCESS);
		when(predictionRepository.findByIdAndDeletedAtIsNull(other.getId())).thenReturn(Optional.of(other));

		assertThatThrownBy(() -> service(true).get(user.getId(), false, other.getId()))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.getCode()).isEqualTo("PREDICTION_NOT_FOUND");
			});
	}

}
