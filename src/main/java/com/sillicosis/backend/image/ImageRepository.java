package com.sillicosis.backend.image;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRepository extends JpaRepository<Image, UUID> {

	Optional<Image> findByIdAndDeletedAtIsNull(UUID id);

	Optional<Image> findByOwnerIdAndSha256AndDeletedAtIsNull(UUID ownerId, String sha256);

	Page<Image> findByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

	Optional<Image> findByVisitIdAndDeletedAtIsNull(UUID visitId);

	List<Image> findByVisitIdInAndDeletedAtIsNull(Collection<UUID> visitIds);

}
