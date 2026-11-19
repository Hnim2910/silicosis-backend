package com.sillicosis.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

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
				new BCryptPasswordEncoder(4), Clock.systemUTC());
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
				new CreateUserRequest("Người Mới", "moi@example.com", null, "MatKhau@123"));

		assertThat(created.role()).isEqualTo(Role.USER);
		assertThat(created.code()).isEqualTo("BN-000042");
	}

	@Test
	void updateMeOverwritesAccountAndPatientProfileButKeepsCode() {
		Map<String, Object> tieuSu = new LinkedHashMap<>();
		tieuSu.put("cviec", "  Thợ nghiền đá ");
		tieuSu.put("tuoinghe", "12,0");
		tieuSu.put("nampx", 3.5);
		tieuSu.put("hutthuoc", "Khong hut thuoc");
		tieuSu.put("tsnoi", " ");
		UserResponse res = userService.updateMe(user.getId(), new UpdateProfileRequest("Tên Mới", "cu@example.com",
				null, LocalDate.of(1985, 6, 7), Gender.MALE, tieuSu));

		assertThat(user.getFullName()).isEqualTo("Tên Mới");
		assertThat(user.getTieuSu()).containsExactly(Map.entry("cviec", "Thợ nghiền đá"), Map.entry("tuoinghe", 12L),
				Map.entry("nampx", 3.5), Map.entry("hutthuoc", "Khong hut thuoc"));
		assertThat(res.code()).isEqualTo("BN-000007");
	}

	@Test
	void tieuSuIsKeptWhenNotSent() {
		user.setTieuSu(new LinkedHashMap<>(Map.of("cviec", "Thợ mỏ")));
		userService.updateMe(user.getId(),
				new UpdateProfileRequest("Tên", "cu@example.com", null, null, null, null));

		assertThat(user.getTieuSu()).containsEntry("cviec", "Thợ mỏ");
	}

	@Test
	void profileAndTieuSuAreValidated() {
		Map<String, Object> tieuSu = new LinkedHashMap<>();
		tieuSu.put("cviec", "x".repeat(501));
		tieuSu.put("tuoinghe", "mười");
		tieuSu.put("tgian1", 90);
		tieuSu.put("hutthuoc", "Co");
		tieuSu.put("fvc", 80);
		assertThatThrownBy(() -> userService.updateMe(user.getId(), new UpdateProfileRequest("Tên", "cu@example.com",
				null, LocalDate.of(1850, 1, 1), null, tieuSu)))
			.isInstanceOfSatisfying(InvalidFieldsException.class,
					e -> assertThat(e.getFieldErrors()).containsOnlyKeys("birthDate", "tieuSu.cviec",
							"tieuSu.tuoinghe", "tieuSu.tgian1", "tieuSu.hutthuoc", "tieuSu.fvc"));
	}

}
