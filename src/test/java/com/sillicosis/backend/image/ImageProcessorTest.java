package com.sillicosis.backend.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.sillicosis.backend.TestProps;
import com.sillicosis.backend.common.ApiException;

class ImageProcessorTest {

	private final ImageProcessor processor = new ImageProcessor(TestProps.defaults());

	@Test
	void acceptsPngAndStripsMetadataByReencoding() throws Exception {
		ImageProcessor.ProcessedImage image = processor.process(png(300, 280));

		assertThat(image.contentType()).isEqualTo("image/png");
		assertThat(image.width()).isEqualTo(300);
		assertThat(image.height()).isEqualTo(280);
		assertThat(image.sha256()).hasSize(64);
		assertThat(ImageIO.read(new java.io.ByteArrayInputStream(image.bytes()))).isNotNull();
	}

	@Test
	void rejectsTextEvenWhenNamedLikePng() {
		assertApiError(() -> processor.process("not an image".getBytes()), "UNSUPPORTED_IMAGE_TYPE",
				HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	@Test
	void rejectsInputOverConfiguredLimit() {
		byte[] oversized = new byte[10 * 1024 * 1024 + 1];
		assertApiError(() -> processor.process(oversized), "IMAGE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
	}

	@Test
	void rejectsCorruptPngWithValidSignature() {
		byte[] corrupt = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3 };
		assertApiError(() -> processor.process(corrupt), "INVALID_IMAGE", HttpStatus.BAD_REQUEST);
	}

	@Test
	void rejectsImageBelowMinimumDimension() throws Exception {
		byte[] tiny = png(100, 300);
		assertApiError(() -> processor.process(tiny), "INVALID_IMAGE", HttpStatus.BAD_REQUEST);
	}

	private static byte[] png(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private static void assertApiError(Runnable action, String code, HttpStatus status) {
		assertThatThrownBy(action::run).isInstanceOf(ApiException.class).satisfies(error -> {
			ApiException apiError = (ApiException) error;
			assertThat(apiError.getCode()).isEqualTo(code);
			assertThat(apiError.getStatus()).isEqualTo(status);
		});
	}

}
