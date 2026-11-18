package com.sillicosis.backend.prediction;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.prediction.PredictionService.ImageContent;
import com.sillicosis.backend.prediction.dto.PredictionRequest;
import com.sillicosis.backend.prediction.dto.PredictionResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Lượt chẩn đoán (model New Way, thử nghiệm). Chủ lượt chẩn đoán hoặc ADMIN; người khác nhận 404. */
@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
@Tag(name = "Lượt chẩn đoán")
@SecurityRequirement(name = "bearerAuth")
public class PredictionController {

	private final PredictionService predictionService;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Gửi một lượt chẩn đoán: phần data (JSON {examDate, lamSang}) và/hoặc phần file (ảnh X-quang)."
			+ " Chạy model rồi lưu; lỗi service vẫn trả 201 với status FAILED. Không có gì để chẩn đoán → 400 EMPTY_SUBMISSION")
	public ResponseEntity<PredictionResponse> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestPart(value = "data", required = false) PredictionRequest data,
			@RequestPart(value = "file", required = false) MultipartFile file) {
		PredictionResponse created = predictionService.create(userId(jwt), data, file);
		return ResponseEntity.created(URI.create("/api/predictions/" + created.id())).body(created);
	}

	@GetMapping
	@Operation(summary = "Lượt chẩn đoán của tôi, mới nhất trước")
	public PageResponse<PredictionResponse> listMine(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return predictionService.listMine(userId(jwt), pageable(page, size));
	}

	@GetMapping("/all")
	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Mọi lượt chẩn đoán (ADMIN), mới nhất trước; mỗi phần tử có user {id, code, fullName}")
	public PageResponse<PredictionResponse> listAll(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return predictionService.listAll(pageable(page, size));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Chi tiết một lượt chẩn đoán")
	public PredictionResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return predictionService.get(userId(jwt), isAdmin(jwt), id);
	}

	@GetMapping("/{id}/image")
	@Operation(summary = "Tải ảnh X-quang của lượt chẩn đoán; không có ảnh → 404")
	public ResponseEntity<byte[]> image(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		ImageContent content = predictionService.image(userId(jwt), isAdmin(jwt), id);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(content.contentType()))
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.header("X-Content-Type-Options", "nosniff")
			.body(content.bytes());
	}

	@PostMapping("/{id}/retry")
	@Operation(summary = "Chạy lại model cho lượt bị lỗi (FAILED), ghi đè kết quả trên chính lượt đó. Lượt đã thành công"
			+ " → 409 PREDICTION_NOT_FAILED; tắt dự đoán → 503 PREDICT_DISABLED")
	public PredictionResponse retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return predictionService.retry(userId(jwt), isAdmin(jwt), id);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Xoá mềm lượt chẩn đoán")
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		predictionService.delete(userId(jwt), isAdmin(jwt), id);
	}

	private static Pageable pageable(int page, int size) {
		return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
				Sort.by(Sort.Direction.DESC, "examDate", "createdAt"));
	}

	private static boolean isAdmin(Jwt jwt) {
		return "ADMIN".equals(jwt.getClaimAsString("role"));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
