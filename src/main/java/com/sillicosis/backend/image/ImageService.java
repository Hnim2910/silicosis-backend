package com.sillicosis.backend.image;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.image.ImageProcessor.ProcessedImage;
import com.sillicosis.backend.image.dto.ImageResponse;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ImageService {

	private final ImageRepository imageRepository;
	private final UserRepository userRepository;
	private final ImageProcessor imageProcessor;
	private final ImageStorage imageStorage;
	private final Clock clock;

	@Transactional
	public ImageResponse upload(UUID ownerId, MultipartFile file) {
		activeUser(ownerId);
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("INVALID_IMAGE", "Vui lòng chọn tệp ảnh.");
		}
		byte[] input;
		try {
			input = file.getBytes();
		}
		catch (IOException ex) {
			throw ApiException.badRequest("INVALID_IMAGE", "Không thể đọc tệp ảnh.");
		}
		ProcessedImage processed = imageProcessor.process(input);
		imageRepository.findByOwnerIdAndSha256AndDeletedAtIsNull(ownerId, processed.sha256())
			.ifPresent(existing -> {
				throw ApiException.conflict("IMAGE_DUPLICATE", "Ảnh này đã được tải lên.",
						Map.of("imageId", existing.getId().toString()));
			});

		String storageKey = UUID.randomUUID().toString();
		imageStorage.write(storageKey, processed.bytes());
		Image image = new Image();
		image.setOwnerId(ownerId);
		image.setStorageKey(storageKey);
		image.setContentType(processed.contentType());
		image.setSizeBytes(processed.bytes().length);
		image.setWidth(processed.width());
		image.setHeight(processed.height());
		image.setSha256(processed.sha256());
		try {
			return ImageResponse.from(imageRepository.saveAndFlush(image));
		}
		catch (DataIntegrityViolationException ex) {
			imageStorage.delete(storageKey);
			throw ApiException.conflict("CONFLICT", "Dữ liệu ảnh bị xung đột, vui lòng thử lại.");
		}
		catch (RuntimeException ex) {
			imageStorage.delete(storageKey);
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public PageResponse<ImageResponse> list(UUID ownerId, Pageable pageable) {
		activeUser(ownerId);
		Page<Image> page = imageRepository.findByOwnerIdAndDeletedAtIsNull(ownerId, pageable);
		return PageResponse.of(page, ImageResponse::from);
	}

	@Transactional(readOnly = true)
	public ImageResponse get(UUID actorId, boolean admin, UUID imageId) {
		activeUser(actorId);
		return ImageResponse.from(authorizedImage(actorId, admin, imageId));
	}

	@Transactional(readOnly = true)
	public ImageContent content(UUID actorId, boolean admin, UUID imageId) {
		activeUser(actorId);
		Image image = authorizedImage(actorId, admin, imageId);
		return new ImageContent(image.getContentType(), imageStorage.read(image.getStorageKey()));
	}

	@Transactional
	public void delete(UUID actorId, boolean admin, UUID imageId) {
		activeUser(actorId);
		Image image = authorizedImage(actorId, admin, imageId);
		image.setDeletedAt(clock.instant());
		imageRepository.flush();
		imageStorage.delete(image.getStorageKey());
	}

	private Image authorizedImage(UUID actorId, boolean admin, UUID imageId) {
		Image image = imageRepository.findByIdAndDeletedAtIsNull(imageId)
			.orElseThrow(() -> ApiException.notFound("Không tìm thấy ảnh."));
		if (!admin && !image.getOwnerId().equals(actorId)) {
			throw ApiException.notFound("Không tìm thấy ảnh.");
		}
		return image;
	}

	private User activeUser(UUID userId) {
		User user = userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng."));
		if (!user.isEnabled()) {
			throw ApiException.forbidden("ACCOUNT_DISABLED", "Tài khoản đã bị khóa.");
		}
		return user;
	}

	public record ImageContent(String contentType, byte[] bytes) {
	}

}
