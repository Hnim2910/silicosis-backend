package com.sillicosis.backend.image;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.config.AppProperties;

@Component
public class ImageStorage {

	private final Path root;

	public ImageStorage(AppProperties properties) {
		this.root = Path.of(properties.storage().imagesDir()).toAbsolutePath().normalize();
	}

	public void write(String storageKey, byte[] content) {
		Path target = resolve(storageKey);
		Path temporary = null;
		try {
			Files.createDirectories(root);
			temporary = Files.createTempFile(root, ".image-", ".tmp");
			Files.write(temporary, content);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(temporary, target);
			}
		}
		catch (IOException ex) {
			deleteQuietly(temporary);
			throw new IllegalStateException("Could not store image.", ex);
		}
	}

	public byte[] read(String storageKey) {
		try {
			return Files.readAllBytes(resolve(storageKey));
		}
		catch (IOException ex) {
			throw ApiException.notFound("Không tìm thấy ảnh.");
		}
	}

	public void delete(String storageKey) {
		try {
			Files.deleteIfExists(resolve(storageKey));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not delete image.", ex);
		}
	}

	private Path resolve(String storageKey) {
		try {
			String canonicalKey = UUID.fromString(storageKey).toString();
			if (!canonicalKey.equals(storageKey)) {
				throw new IllegalArgumentException();
			}
			Path path = root.resolve(canonicalKey).normalize();
			if (!path.startsWith(root)) {
				throw new IllegalArgumentException();
			}
			return path;
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Invalid image storage key.");
		}
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// Best-effort cleanup after a failed write.
		}
	}

}
