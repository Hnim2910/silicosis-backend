package com.sillicosis.backend.image;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "images")
@Getter
@Setter
@NoArgsConstructor
public class Image {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;

	@Column(name = "storage_key", nullable = false, length = 100)
	private String storageKey;

	@Column(name = "content_type", nullable = false, length = 30)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(nullable = false)
	private int width;

	@Column(nullable = false)
	private int height;

	@Column(nullable = false, length = 64)
	private String sha256;

	@Column(name = "visit_id")
	private UUID visitId;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

}
