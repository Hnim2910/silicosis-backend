package com.sillicosis.backend.prediction;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PredictionRepository extends JpaRepository<Prediction, UUID> {

	/**
	 * Với mỗi lượt khám: dòng mới nhất ứng với ảnh đang gắn (ảnh chưa xoá, images.visit_id trùng). Lượt khám chưa
	 * có ảnh hoặc ảnh chưa được dự đoán thì không có dòng nào.
	 */
	@Query(value = """
			SELECT DISTINCT ON (p.visit_id) p.*
			FROM predictions p
			JOIN images i ON i.id = p.image_id AND i.visit_id = p.visit_id AND i.deleted_at IS NULL
			WHERE p.visit_id IN (:visitIds)
			ORDER BY p.visit_id, p.created_at DESC, p.id DESC
			""", nativeQuery = true)
	List<Prediction> findLatestForAttachedImages(@Param("visitIds") Collection<UUID> visitIds);

}
