package com.sillicosis.backend.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Các truy vấn theo email/SĐT chỉ xét tài khoản chưa bị xoá mềm. */
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

	Optional<User> findByEmailAndDeletedAtIsNull(String email);

	Optional<User> findByPhoneAndDeletedAtIsNull(String phone);

	Optional<User> findByIdAndDeletedAtIsNull(UUID id);

	boolean existsByEmailAndDeletedAtIsNull(String email);

	boolean existsByPhoneAndDeletedAtIsNull(String phone);

	boolean existsByEmailAndDeletedAtIsNullAndIdNot(String email, UUID id);

	boolean existsByPhoneAndDeletedAtIsNullAndIdNot(String phone, UUID id);

}
