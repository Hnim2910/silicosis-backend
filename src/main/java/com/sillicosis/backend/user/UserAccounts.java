package com.sillicosis.backend.user;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.PhoneNumbers;

import lombok.RequiredArgsConstructor;

/** Tiện ích dùng chung cho auth và quản lý user: chuẩn hoá, tra cứu, kiểm tra trùng. */
@Component
@RequiredArgsConstructor
public class UserAccounts {

	private final UserRepository userRepository;

	public static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	/** Tìm tài khoản chưa xoá theo email hoặc số điện thoại. */
	public Optional<User> findByIdentifier(String identifier) {
		if (identifier == null || identifier.isBlank()) {
			return Optional.empty();
		}
		if (identifier.contains("@")) {
			return userRepository.findByEmailAndDeletedAtIsNull(normalizeEmail(identifier));
		}
		String phone = PhoneNumbers.normalize(identifier);
		return phone == null ? Optional.empty() : userRepository.findByPhoneAndDeletedAtIsNull(phone);
	}

	public User getActive(UUID id) {
		return userRepository.findByIdAndDeletedAtIsNull(id)
			.orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng."));
	}

	/** @param excludeId id của chính user đang sửa, null khi tạo mới */
	public void ensureUnique(String email, String phone, UUID excludeId) {
		boolean emailTaken = excludeId == null ? userRepository.existsByEmailAndDeletedAtIsNull(email)
				: userRepository.existsByEmailAndDeletedAtIsNullAndIdNot(email, excludeId);
		if (emailTaken) {
			throw ApiException.conflict("EMAIL_TAKEN", "Email đã được sử dụng.");
		}
		if (phone != null) {
			boolean phoneTaken = excludeId == null ? userRepository.existsByPhoneAndDeletedAtIsNull(phone)
					: userRepository.existsByPhoneAndDeletedAtIsNullAndIdNot(phone, excludeId);
			if (phoneTaken) {
				throw ApiException.conflict("PHONE_TAKEN", "Số điện thoại đã được sử dụng.");
			}
		}
	}

}
