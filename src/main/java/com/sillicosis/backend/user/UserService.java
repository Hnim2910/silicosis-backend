package com.sillicosis.backend.user;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sillicosis.backend.auth.TokenService;
import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.common.PhoneNumbers;
import com.sillicosis.backend.common.InvalidFieldsException;
import com.sillicosis.backend.user.dto.ChangePasswordRequest;
import com.sillicosis.backend.user.dto.CreateUserRequest;
import com.sillicosis.backend.user.dto.UpdateProfileRequest;
import com.sillicosis.backend.user.dto.UserResponse;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

	private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

	private final UserRepository userRepository;
	private final UserAccounts userAccounts;
	private final TokenService tokenService;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	// ---------- Người dùng hiện tại ----------

	@Transactional(readOnly = true)
	public UserResponse getMe(UUID userId) {
		return UserResponse.from(currentUser(userId));
	}

	/** Ghi đè thông tin tài khoản và hồ sơ bệnh nhân; mã bệnh nhân không đổi. */
	@Transactional
	public UserResponse updateMe(UUID userId, UpdateProfileRequest req) {
		User user = currentUser(userId);
		validateProfile(req);
		String email = UserAccounts.normalizeEmail(req.email());
		String phone = PhoneNumbers.normalize(req.phone());
		userAccounts.ensureUnique(email, phone, userId);
		user.setFullName(req.fullName().trim());
		user.setEmail(email);
		user.setPhone(phone);
		user.setBirthDate(req.birthDate());
		user.setGender(req.gender());
		if (req.tieuSu() != null) {
			user.setTieuSu(TieuSu.normalize(req.tieuSu()));
		}
		userRepository.flush();
		return UserResponse.from(user);
	}

	/** Đổi mật khẩu cần mật khẩu cũ; thu hồi mọi refresh token (các thiết bị khác phải đăng nhập lại). */
	@Transactional
	public void changePassword(UUID userId, ChangePasswordRequest req) {
		User user = currentUser(userId);
		if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
			throw ApiException.badRequest("WRONG_PASSWORD", "Mật khẩu hiện tại không chính xác.");
		}
		if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
			throw ApiException.badRequest("SAME_PASSWORD", "Mật khẩu mới phải khác mật khẩu hiện tại.");
		}
		user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
		tokenService.revokeAll(userId);
	}

	// ---------- Quản trị ----------

	@Transactional(readOnly = true)
	public PageResponse<UserResponse> list(Role role, String query, Pageable pageable) {
		return PageResponse.of(userRepository.findAll(filter(role, query), pageable), UserResponse::from);
	}

	@Transactional(readOnly = true)
	public UserResponse get(UUID id) {
		return UserResponse.from(userAccounts.getActive(id));
	}

	/** Tài khoản USER (bệnh nhân) mới, kèm mã bệnh nhân. */
	@Transactional
	public UserResponse create(UUID actorId, CreateUserRequest req) {
		String email = UserAccounts.normalizeEmail(req.email());
		String phone = PhoneNumbers.normalize(req.phone());
		userAccounts.ensureUnique(email, phone, null);
		User user = new User();
		user.setEmail(email);
		user.setPhone(phone);
		user.setFullName(req.fullName().trim());
		user.setPasswordHash(passwordEncoder.encode(req.password()));
		user.setRole(Role.USER);
		userAccounts.assignPatientCode(user);
		userRepository.saveAndFlush(user);
		return UserResponse.from(user);
	}

	/** Khoá/mở khoá. Mở khoá cũng xoá khoá tạm do đăng nhập sai. */
	@Transactional
	public UserResponse updateStatus(UUID actorId, UUID id, boolean enabled) {
		forbidSelf(actorId, id);
		User user = userAccounts.getActive(id);
		user.setEnabled(enabled);
		if (enabled) {
			user.clearLoginFailures();
		}
		else {
			tokenService.revokeAll(id);
		}
		userRepository.flush();
		return UserResponse.from(user);
	}

	/**
	 * Xoá mềm: giữ bản ghi cùng hồ sơ và lượt chẩn đoán (dữ liệu y tế), giải phóng email/SĐT, thu hồi phiên.
	 */
	@Transactional
	public void delete(UUID actorId, UUID id) {
		forbidSelf(actorId, id);
		User user = userAccounts.getActive(id);
		user.setDeletedAt(clock.instant());
		user.setEnabled(false);
		tokenService.revokeAll(id);
	}

	// ---------- Nội bộ ----------

	private User currentUser(UUID userId) {
		User user = userAccounts.getActive(userId);
		if (!user.isEnabled()) {
			throw ApiException.forbidden("ACCOUNT_DISABLED", "Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.");
		}
		return user;
	}

	/** Quy tắc phụ thuộc cấu hình hoặc ngày hiện tại; phần còn lại nằm ở annotation của request. */
	private void validateProfile(UpdateProfileRequest req) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (req.birthDate() != null && req.birthDate().isBefore(MIN_BIRTH_DATE)) {
			errors.put("birthDate", "Ngày sinh không hợp lệ.");
		}
		errors.putAll(TieuSu.validate(req.tieuSu()));
		if (!errors.isEmpty()) {
			throw new InvalidFieldsException(errors);
		}
	}

	private static void forbidSelf(UUID actorId, UUID targetId) {
		if (actorId.equals(targetId)) {
			throw ApiException.badRequest("CANNOT_MODIFY_SELF",
					"Không thể tự đổi vai trò, khoá hoặc xoá chính tài khoản của mình.");
		}
	}

	private static Specification<User> filter(Role role, String query) {
		return (root, cq, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.isNull(root.get("deletedAt")));
			if (role != null) {
				predicates.add(cb.equal(root.get("role"), role));
			}
			if (query != null && !query.isBlank()) {
				String like = "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("email")), like, '\\'),
						cb.like(cb.lower(root.get("fullName")), like, '\\'), cb.like(root.get("phone"), like, '\\')));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	private static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
