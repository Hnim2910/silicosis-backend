package com.sillicosis.backend.image;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

	@Mock
	private ImageRepository imageRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private ImageProcessor imageProcessor;

	@Mock
	private ImageStorage imageStorage;

	@Mock
	private java.time.Clock clock;

	@InjectMocks
	private ImageService imageService;

	@Test
	void duplicateForSameOwnerReturnsExistingImageIdWithoutWritingFile() {
		UUID ownerId = UUID.randomUUID();
		UUID existingId = UUID.randomUUID();
		User owner = new User();
		owner.setId(ownerId);
		Image existing = new Image();
		existing.setId(existingId);
		var processed = new ImageProcessor.ProcessedImage(new byte[] { 1, 2, 3 }, "image/png", 256, 256, "a".repeat(64));

		when(userRepository.findByIdAndDeletedAtIsNull(ownerId)).thenReturn(Optional.of(owner));
		when(imageProcessor.process(any())).thenReturn(processed);
		when(imageRepository.findByOwnerIdAndSha256AndDeletedAtIsNull(ownerId, processed.sha256()))
			.thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> imageService.upload(ownerId,
				new MockMultipartFile("file", "xray.png", "image/png", new byte[] { 1 })))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> {
				ApiException apiError = (ApiException) error;
				org.assertj.core.api.Assertions.assertThat(apiError.getCode()).isEqualTo("IMAGE_DUPLICATE");
				org.assertj.core.api.Assertions.assertThat(apiError.getDetails()).containsEntry("imageId", existingId.toString());
			});

		verify(imageStorage, never()).write(any(), any());
	}

}
