package com.sillicosis.backend.image;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.image.ImageService.ImageContent;
import com.sillicosis.backend.image.dto.ImageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
@Tag(name = "Ảnh X-quang")
@SecurityRequirement(name = "bearerAuth")
public class ImageController {

	private final ImageService imageService;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Tải ảnh X-quang PNG hoặc JPEG lên")
	public ResponseEntity<ImageResponse> upload(@AuthenticationPrincipal Jwt jwt,
			@RequestPart("file") MultipartFile file) {
		ImageResponse response = imageService.upload(userId(jwt), file);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	@Operation(summary = "Liệt kê ảnh của tài khoản đang đăng nhập")
	public PageResponse<ImageResponse> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100),
				Sort.by(Sort.Direction.DESC, "createdAt"));
		return imageService.list(userId(jwt), pageable);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Lấy metadata ảnh; chủ ảnh hoặc ADMIN")
	public ImageResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return imageService.get(userId(jwt), isAdmin(jwt), id);
	}

	@GetMapping("/{id}/content")
	@Operation(summary = "Tải nội dung ảnh; chủ ảnh hoặc ADMIN")
	public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		ImageContent content = imageService.content(userId(jwt), isAdmin(jwt), id);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(content.contentType()))
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.header("X-Content-Type-Options", "nosniff")
			.body(content.bytes());
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Xóa ảnh (xóa mềm trong DB và xóa tệp); chủ ảnh hoặc ADMIN. Ảnh đang gắn lượt khám được gỡ liên kết")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		imageService.delete(userId(jwt), isAdmin(jwt), id);
		return ResponseEntity.noContent().build();
	}

	private static boolean isAdmin(Jwt jwt) {
		return "ADMIN".equals(jwt.getClaimAsString("role"));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
