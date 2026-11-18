package com.sillicosis.backend.prediction;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Các truy vấn chỉ xét lượt chẩn đoán chưa bị xoá mềm. */
public interface PredictionRepository extends JpaRepository<Prediction, UUID> {

	Optional<Prediction> findByIdAndDeletedAtIsNull(UUID id);

	Page<Prediction> findByUserIdAndDeletedAtIsNull(UUID userId, Pageable pageable);

	Page<Prediction> findByDeletedAtIsNull(Pageable pageable);

}
