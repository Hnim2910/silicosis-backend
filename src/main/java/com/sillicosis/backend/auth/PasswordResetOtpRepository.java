package com.sillicosis.backend.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, UUID> {

	Optional<PasswordResetOtp> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

	Optional<PasswordResetOtp> findByResetTokenHash(String resetTokenHash);

	/** Vô hiệu mọi OTP chưa dùng của user (khi gửi OTP mới hoặc đã đổi mật khẩu). */
	@Modifying
	@Query("update PasswordResetOtp o set o.used = true, o.resetTokenHash = null "
			+ "where o.user.id = :userId and (o.used = false or o.resetTokenHash is not null)")
	int invalidateAllForUser(@Param("userId") UUID userId);

}
