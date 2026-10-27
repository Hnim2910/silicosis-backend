package com.sillicosis.backend.image.dto;

import java.time.Instant;
import java.util.UUID;

import com.sillicosis.backend.image.Image;

/** {@code visitId} null khi ảnh chưa gắn vào lượt khám nào. */
public record ImageResponse(UUID id, String contentType, long sizeBytes, int width, int height, UUID visitId,
		Instant createdAt) {

	public static ImageResponse from(Image image) {
		return new ImageResponse(image.getId(), image.getContentType(), image.getSizeBytes(), image.getWidth(),
				image.getHeight(), image.getVisitId(), image.getCreatedAt());
	}

}
