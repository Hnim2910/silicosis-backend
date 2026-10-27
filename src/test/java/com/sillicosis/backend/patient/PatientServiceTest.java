package com.sillicosis.backend.patient;

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
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.sillicosis.backend.TestProps;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.InvalidFieldsException;
import com.sillicosis.backend.patient.dto.PatientRequest;
import com.sillicosis.backend.patient.dto.PatientResponse;
import com.sillicosis.backend.user.Gender;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

/** Dữ liệu đều là dữ liệu giả. */
class PatientServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private PatientRepository patientRepository;

	private UserRepository userRepository;

	private PatientService patientService;

	private User owner;

	private User admin;

	private Patient patient;

	@BeforeEach
	void setUp() {
		patientRepository = mock(PatientRepository.class);
		userRepository = mock(UserRepository.class);
		patientService = new PatientService(patientRepository, userRepository, TestProps.defaults(),
				Clock.fixed(NOW, ZoneOffset.UTC));
		when(patientRepository.nextCodeNumber()).thenReturn(42L);
		when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(patientRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

		owner = user(Role.USER);
		owner.setFullName("Người Thử");
		owner.setPhone("0912345678");
		owner.setBirthDate(LocalDate.of(1980, 5, 20));
		owner.setGender(Gender.MALE);
		owner.setOccupation("Thợ giả định");
		admin = user(Role.ADMIN);

		patient = new Patient();
		patient.setId(UUID.randomUUID());
		patient.setCode("BN-000001");
		patient.setUserId(owner.getId());
		patient.setFullName(owner.getFullName());
		when(patientRepository.findByIdAndDeletedAtIsNull(patient.getId())).thenReturn(Optional.of(patient));
		when(patientRepository.findByUserIdAndDeletedAtIsNull(owner.getId())).thenReturn(Optional.of(patient));
	}

	// ---------- Tạo hồ sơ cùng tài khoản ----------

	@Test
	void createForUserCopiesAccountFieldsAndGeneratesCode() {
		Patient created = patientService.createForUser(owner, owner.getId());

		assertThat(created.getCode()).isEqualTo("BN-000042");
		assertThat(created.getUserId()).isEqualTo(owner.getId());
		assertThat(created.getCreatedBy()).isEqualTo(owner.getId());
		assertThat(created.getFullName()).isEqualTo("Người Thử");
		assertThat(created.getBirthDate()).isEqualTo(LocalDate.of(1980, 5, 20));
		assertThat(created.getGender()).isEqualTo(Gender.MALE);
		assertThat(created.getOccupation()).isEqualTo("Thợ giả định");
		assertThat(created.getPhone()).isEqualTo("0912345678");
		assertThat(created.getWorkplace()).isNull();
		assertThat(created.getExposureHistory()).isNull();
		assertThat(created.getRiskFactors()).isNull();
	}

	@Test
	void codeIsZeroPaddedToSixDigits() {
		when(patientRepository.nextCodeNumber()).thenReturn(1L, 123456L);
		assertThat(patientService.nextCode()).isEqualTo("BN-000001");
		assertThat(patientService.nextCode()).isEqualTo("BN-123456");
	}

	// ---------- Đồng bộ hai chiều ----------

	@Test
	void syncFromUserCopiesOnlyFourFields() {
		patient.setPhone("0987654321");
		owner.setFullName("Tên Mới");
		owner.setBirthDate(LocalDate.of(1975, 1, 2));
		owner.setGender(Gender.OTHER);
		owner.setOccupation(null);
		owner.setPhone("0911111111");

		patientService.syncFromUser(owner);

		assertThat(patient.getFullName()).isEqualTo("Tên Mới");
		assertThat(patient.getBirthDate()).isEqualTo(LocalDate.of(1975, 1, 2));
		assertThat(patient.getGender()).isEqualTo(Gender.OTHER);
		assertThat(patient.getOccupation()).isNull();
		assertThat(patient.getPhone()).isEqualTo("0987654321");
	}

	@Test
	void updateLinkedPatientSyncsFourFieldsToAccountButNotPhone() {
		PatientResponse res = patientService.update(owner.getId(), false, patient.getId(),
				request("  Tên Hồ Sơ ", "0333333333"));

		assertThat(res.fullName()).isEqualTo("Tên Hồ Sơ");
		assertThat(res.phone()).isEqualTo("0333333333");
		assertThat(res.code()).isEqualTo("BN-000001");
		assertThat(owner.getFullName()).isEqualTo("Tên Hồ Sơ");
		assertThat(owner.getBirthDate()).isEqualTo(LocalDate.of(1990, 3, 4));
		assertThat(owner.getGender()).isEqualTo(Gender.FEMALE);
		assertThat(owner.getOccupation()).isEqualTo("Nghề thử");
		assertThat(owner.getPhone()).isEqualTo("0912345678");
	}

	@Test
	void updateUnlinkedPatientDoesNotTouchAccounts() {
		patient.setUserId(null);

		patientService.update(admin.getId(), true, patient.getId(), request("Hồ Sơ Rời", null));

		assertThat(patient.getFullName()).isEqualTo("Hồ Sơ Rời");
		verify(userRepository, never()).findByIdAndDeletedAtIsNull(owner.getId());
	}

	// ---------- Quyền ----------

	@Test
	void otherUserGetsPatientNotFound() {
		User stranger = user(Role.USER);

		assertThatThrownBy(() -> patientService.get(stranger.getId(), false, patient.getId()))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.getCode()).isEqualTo("PATIENT_NOT_FOUND");
			});
		assertThatThrownBy(() -> patientService.update(stranger.getId(), false, patient.getId(), request("X Y", null)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PATIENT_NOT_FOUND"));
	}

	@Test
	void adminHasNoOwnPatient() {
		assertThatThrownBy(() -> patientService.getMine(admin.getId()))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PATIENT_NOT_FOUND"));
	}

	@Test
	void adminCreatesUnlinkedPatient() {
		PatientResponse res = patientService.create(admin.getId(), request("Bệnh Nhân Rời", "+84 912 000 111"));

		ArgumentCaptor<Patient> saved = ArgumentCaptor.forClass(Patient.class);
		verify(patientRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getUserId()).isNull();
		assertThat(saved.getValue().getCreatedBy()).isEqualTo(admin.getId());
		assertThat(res.code()).isEqualTo("BN-000042");
		assertThat(res.phone()).isEqualTo("0912000111");
	}

	@Test
	void deleteIsSoft() {
		patient.setUserId(null);
		patientService.delete(admin.getId(), patient.getId());
		assertThat(patient.getDeletedAt()).isEqualTo(NOW);
	}

	@Test
	void patientLinkedToActiveAccountCannotBeDeleted() {
		assertThatThrownBy(() -> patientService.delete(admin.getId(), patient.getId()))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.getCode()).isEqualTo("PATIENT_LINKED_TO_ACCOUNT");
			});
		assertThat(patient.getDeletedAt()).isNull();
	}

	@Test
	void patientOfDeletedAccountCanBeDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(owner.getId())).thenReturn(Optional.empty());

		patientService.delete(admin.getId(), patient.getId());

		assertThat(patient.getDeletedAt()).isEqualTo(NOW);
	}

	// ---------- Kiểm tra dữ liệu ----------

	@Test
	void freeTextLongerThanLimitIsRejected() {
		String tooLong = "a".repeat(2001);
		var req = new PatientRequest("Tên Thử", LocalDate.of(1990, 1, 1), null, null, "Nghề", null, tooLong,
				"a".repeat(2000));

		assertThatThrownBy(() -> patientService.create(admin.getId(), req))
			.isInstanceOfSatisfying(InvalidFieldsException.class,
					e -> assertThat(e.getFieldErrors()).containsOnlyKeys("exposureHistory"));
	}

	@Test
	void birthDateBefore1900IsRejected() {
		var req = new PatientRequest("Tên Thử", LocalDate.of(1899, 12, 31), null, null, "Nghề", null, null, null);

		assertThatThrownBy(() -> patientService.create(admin.getId(), req))
			.isInstanceOfSatisfying(InvalidFieldsException.class,
					e -> assertThat(e.getFieldErrors()).containsOnlyKeys("birthDate"));
	}

	@Test
	void foldRemovesVietnameseAccentsAndCase() {
		assertThat(PatientService.fold("Nguyễn Thị ĐÀO")).isEqualTo("nguyen thi dao");
		assertThat(PatientService.fold("Thợ Mỏ")).isEqualTo("tho mo");
	}

	// ---------- Tiện ích ----------

	private User user(Role role) {
		User u = new User();
		u.setId(UUID.randomUUID());
		u.setRole(role);
		u.setFullName("Tài Khoản Thử");
		when(userRepository.findByIdAndDeletedAtIsNull(u.getId())).thenReturn(Optional.of(u));
		return u;
	}

	private static PatientRequest request(String fullName, String phone) {
		return new PatientRequest(fullName, LocalDate.of(1990, 3, 4), Gender.FEMALE, phone, "Nghề thử",
				"Xưởng giả định", "Tiền sử giả", "Không");
	}

}
