package com.sillicosis.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.sillicosis.backend.auth.TokenService;
import com.sillicosis.backend.patient.PatientService;
import com.sillicosis.backend.user.dto.CreateUserRequest;
import com.sillicosis.backend.user.dto.UpdateProfileRequest;

/** Phía tài khoản của việc tạo hồ sơ và đồng bộ; dữ liệu giả. */
class UserServiceTest {

	private UserRepository userRepository;

	private PatientService patientService;

	private UserService userService;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		patientService = mock(PatientService.class);
		userService = new UserService(userRepository, new UserAccounts(userRepository), mock(TokenService.class),
				patientService, new BCryptPasswordEncoder(4), Clock.systemUTC());
		when(userRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void adminCreatedUserGetsPatient() {
		UUID adminId = UUID.randomUUID();

		userService.create(adminId, new CreateUserRequest("Người Mới", "moi@example.com", null, "matkhau123"));

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(patientService).createForUser(captor.capture(), eq(adminId));
		assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
	}

	@Test
	void updateMeSyncsPatient() {
		User user = new User();
		user.setId(UUID.randomUUID());
		user.setRole(Role.USER);
		user.setEmail("cu@example.com");
		user.setFullName("Tên Cũ");
		when(userRepository.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));

		userService.updateMe(user.getId(), new UpdateProfileRequest("Tên Mới", "cu@example.com", null,
				LocalDate.of(1985, 6, 7), Gender.MALE, "Nghề mới"));

		assertThat(user.getFullName()).isEqualTo("Tên Mới");
		verify(patientService).syncFromUser(user);
	}

}
