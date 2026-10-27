package com.sillicosis.backend.visit;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VisitRepository extends JpaRepository<Visit, UUID> {

	Optional<Visit> findByIdAndDeletedAtIsNull(UUID id);

	Page<Visit> findByPatientIdAndDeletedAtIsNull(UUID patientId, Pageable pageable);

	/** Mọi lượt khám chưa xoá của các hồ sơ chưa xoá (ADMIN). */
	@Query("""
			SELECT v FROM Visit v
			WHERE v.deletedAt IS NULL
			  AND EXISTS (SELECT 1 FROM Patient p WHERE p.id = v.patientId AND p.deletedAt IS NULL)
			""")
	Page<Visit> findAllActive(Pageable pageable);

}
