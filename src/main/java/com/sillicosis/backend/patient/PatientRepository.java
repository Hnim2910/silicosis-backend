package com.sillicosis.backend.patient;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Các truy vấn theo id/user chỉ xét hồ sơ chưa bị xoá mềm. */
public interface PatientRepository extends JpaRepository<Patient, UUID>, JpaSpecificationExecutor<Patient> {

	Optional<Patient> findByIdAndDeletedAtIsNull(UUID id);

	Optional<Patient> findByUserIdAndDeletedAtIsNull(UUID userId);

	@Query(value = "SELECT nextval('patient_code_seq')", nativeQuery = true)
	long nextCodeNumber();

}
