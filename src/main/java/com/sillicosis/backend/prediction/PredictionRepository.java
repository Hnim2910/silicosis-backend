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
	// Chỉ dòng NEW_WAY (dòng LEGACY của model cũ giữ trong DB nhưng không hiện nữa); dòng chỉ lâm sàng (image_id
	// null) hoặc ứng với ảnh đang gắn.
	@Query(value = """
			SELECT DISTINCT ON (p.visit_id) p.*
			FROM predictions p
			LEFT JOIN images i ON i.id = p.image_id AND i.visit_id = p.visit_id AND i.deleted_at IS NULL
			WHERE p.visit_id IN (:visitIds) AND p.engine = 'NEW_WAY'
			  AND (p.image_id IS NULL OR i.id IS NOT NULL)
			ORDER BY p.visit_id, p.created_at DESC, p.id DESC
			""", nativeQuery = true)
	List<Prediction> findLatestForAttachedImages(@Param("visitIds") Collection<UUID> visitIds);

}
