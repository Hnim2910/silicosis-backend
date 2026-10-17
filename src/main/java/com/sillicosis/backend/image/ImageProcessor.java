package com.sillicosis.backend.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.config.AppProperties;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ImageProcessor {

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };

	private final AppProperties properties;

	public ProcessedImage process(byte[] input) {
		long maxBytes = properties.storage().maxUploadSize().toBytes();
		if (input == null || input.length == 0) {
			throw invalidImage();
		}
		if (input.length > maxBytes) {
			throw tooLarge();
		}

		String contentType = detectType(input);
		String format = contentType.equals("image/png") ? "png" : "jpeg";
		try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
			if (imageInput == null) {
				throw invalidImage();
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext()) {
				throw invalidImage();
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(imageInput, true, true);
				String readerFormat = reader.getFormatName().toLowerCase();
				if (!readerFormat.equals(format) && !(format.equals("jpeg") && readerFormat.equals("jpg"))) {
					throw invalidImage();
				}
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				int maxDimension = properties.storage().maxDimension();
				if (width <= 0 || height <= 0 || width > maxDimension || height > maxDimension
						|| Math.min(width, height) < properties.storage().minShortSide()) {
					throw invalidImage();
				}
				BufferedImage decoded = reader.read(0);
				if (decoded == null) {
					throw invalidImage();
				}
				BufferedImage sanitized = contentType.equals("image/jpeg") ? toRgb(decoded) : decoded;
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				if (!ImageIO.write(sanitized, format, output)) {
					throw invalidImage();
				}
				byte[] cleanBytes = output.toByteArray();
				if (cleanBytes.length > maxBytes) {
					throw tooLarge();
				}
				return new ProcessedImage(cleanBytes, contentType, width, height, sha256(cleanBytes));
			}
			finally {
				reader.dispose();
			}
		}
		catch (ApiException ex) {
			throw ex;
		}
		catch (IOException | RuntimeException ex) {
			throw invalidImage();
		}
	}

	private static String detectType(byte[] input) {
		if (startsWith(input, PNG_SIGNATURE)) {
			return "image/png";
		}
		if (input.length >= 3 && (input[0] & 0xff) == 0xff && (input[1] & 0xff) == 0xd8
				&& (input[2] & 0xff) == 0xff) {
			return "image/jpeg";
		}
		throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE_TYPE",
				"Chỉ chấp nhận ảnh PNG hoặc JPEG.");
	}

	private static boolean startsWith(byte[] input, byte[] prefix) {
		if (input.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (input[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static BufferedImage toRgb(BufferedImage source) {
		if (!source.getColorModel().hasAlpha()) {
			return source;
		}
		BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = rgb.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
			graphics.drawImage(source, 0, 0, null);
		}
		finally {
			graphics.dispose();
		}
		return rgb;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available.", ex);
		}
	}

	private static ApiException invalidImage() {
		return ApiException.badRequest("INVALID_IMAGE", "Ảnh không hợp lệ hoặc kích thước ảnh không được hỗ trợ.");
	}

	private static ApiException tooLarge() {
		return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "Ảnh vượt quá dung lượng cho phép.");
	}

	public record ProcessedImage(byte[] bytes, String contentType, int width, int height, String sha256) {
	}

}
