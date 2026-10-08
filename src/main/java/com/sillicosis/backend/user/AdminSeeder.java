package com.sillicosis.backend.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sillicosis.backend.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Tạo tài khoản ADMIN đầu tiên từ ADMIN_EMAIL / ADMIN_PASSWORD nếu chưa tồn tại. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AppProperties props;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		AppProperties.Admin admin = props.admin();
		if (admin.email() == null || admin.email().isBlank() || admin.password() == null
				|| admin.password().isBlank()) {
			log.info("Không cấu hình ADMIN_EMAIL/ADMIN_PASSWORD, bỏ qua seed admin");
			return;
		}
		if (admin.password().length() < 8) {
			log.warn("ADMIN_PASSWORD quá ngắn (cần >= 8 ký tự), bỏ qua seed admin");
			return;
		}
		String email = UserAccounts.normalizeEmail(admin.email());
		if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
			return;
		}
		User user = new User();
		user.setEmail(email);
		user.setFullName(admin.fullName());
		user.setPasswordHash(passwordEncoder.encode(admin.password()));
		user.setRole(Role.ADMIN);
		userRepository.save(user);
		log.info("Đã tạo tài khoản admin mặc định");
	}

}
