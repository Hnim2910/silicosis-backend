package com.sillicosis.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.sillicosis.backend.TestProps;
import com.sillicosis.backend.auth.TokenService;
import com.sillicosis.backend.common.InvalidFieldsException;
import com.sillicosis.backend.user.dto.CreateUserRequest;
import com.sillicosis.backend.user.dto.UpdateProfileRequest;
import com.sillicosis.backend.user.dto.UserResponse;

/** Tài khoản kiêm hồ sơ bệnh nhân; dữ liệu giả. */
class UserServiceTest {

	private UserRepository userRepository;

	private UserService userService;

	private User user;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		userService = new UserService(userRepository, new UserAccounts(userRepository), mock(TokenService.class),
				new BCryptPasswordEncoder(4), TestProps.defaults(), Clock.systemUTC());
		when(userRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
		when(userRepository.nextCodeNumber()).thenReturn(42L);

		user = new User();
		user.setId(UUID.randomUUID());
		user.setRole(Role.USER);
		user.setEnabled(true);
		user.setCode("BN-000007");
		user.setEmail("cu@example.com");
		user.setFullName("Tên Cũ");
		when(userRepository.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));
	}

	@Test
	void adminCreatedUserIsAPatientWithCode() {
		UserResponse created = userService.create(UUID.randomUUID(),
				new CreateUserRequest("Người Mới", "moi@example.com", null, "matkhau123"));

		assertThat(created.role()).isEqualTo(Role.USER);
		assertThat(created.code()).isEqualTo("BN-000042");
	}

	@Test
	void updateMeOverwritesAccountAndPatientProfileButKeepsCode() {
		UserResponse res = userService.updateMe(user.getId(), new UpdateProfileRequest("Tên Mới", "cu@example.com",
				null, LocalDate.of(1985, 6, 7), Gender.MALE, "Thợ mỏ", "  Mỏ đá Bình Minh ", "Bụi đá 10 năm", " "));

		assertThat(user.getFullName()).isEqualTo("Tên Mới");
		assertThat(user.getOccupation()).isEqualTo("Thợ mỏ");
		assertThat(user.getWorkplace()).isEqualTo("Mỏ đá Bình Minh");
		assertThat(user.getExposureHistory()).isEqualTo("Bụi đá 10 năm");
		assertThat(user.getRiskFactors()).isNull();
		assertThat(res.code()).isEqualTo("BN-000007");
	}

	@Test
	void profileTextLimitsAreChecked() {
		String tooLong = "x".repeat(2001);
		assertThatThrownBy(() -> userService.updateMe(user.getId(), new UpdateProfileRequest("Tên", "cu@example.com",
				null, LocalDate.of(1850, 1, 1), null, null, null, tooLong, null)))
			.isInstanceOfSatisfying(InvalidFieldsException.class,
					e -> assertThat(e.getFieldErrors()).containsKeys("birthDate", "exposureHistory"));
	}

}
