package com.sillicosis.backend.visit;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.prediction.PredictionService;
import com.sillicosis.backend.prediction.dto.PredictionResponse;
import com.sillicosis.backend.visit.dto.AttachImageRequest;
import com.sillicosis.backend.visit.dto.VisitRequest;
import com.sillicosis.backend.visit.dto.VisitResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Lượt khám (bản demo). ADMIN hoặc chủ hồ sơ; người khác nhận 404. Gắn ảnh (khi tạo hoặc qua PUT image) thì dự đoán
 * ngay sau khi lưu, ngoài transaction; kết quả nằm trong {@code latestPrediction}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Lượt khám")
@SecurityRequirement(name = "bearerAuth")
public class VisitController {

	private final VisitService visitService;

	private final PredictionService predictionService;

	@PostMapping("/patients/{patientId}/visits")
	@Operation(summary = "Gửi một lượt khám: ảnh (imageId) và/hoặc chỉ số lâm sàng; createdAt = thời điểm gửi. Không có gì để lưu → 400 EMPTY_SUBMISSION")
	public ResponseEntity<VisitResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
			@Valid @RequestBody VisitRequest req) {
		VisitResponse created = withPrediction(visitService.create(userId(jwt), isAdmin(jwt), patientId, req),
				userId(jwt));
		return ResponseEntity.created(URI.create("/api/visits/" + created.id())).body(created);
	}

	@GetMapping("/patients/{patientId}/visits")
	@Operation(summary = "Danh sách lượt khám của hồ sơ, mới nhất trước")
	public PageResponse<VisitResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
				Sort.by(Sort.Direction.DESC, "visitDate", "createdAt"));
		return visitService.list(userId(jwt), isAdmin(jwt), patientId, pageable);
	}

	@GetMapping("/visits")
	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Mọi lượt khám (ADMIN), mới nhất trước; mỗi phần tử có patient {id, code, fullName}")
	public PageResponse<VisitResponse> listAll(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
				Sort.by(Sort.Direction.DESC, "visitDate", "createdAt"));
		return visitService.listAll(pageable);
	}

	@GetMapping("/visits/{id}")
	@Operation(summary = "Chi tiết lượt khám, kèm ảnh đã gắn (hoặc null)")
	public VisitResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return visitService.get(userId(jwt), isAdmin(jwt), id);
	}

	@PutMapping("/visits/{id}")
	@Operation(summary = "Sửa lượt khám (ghi đè toàn bộ; trường không gửi thành null)")
	public VisitResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody VisitRequest req) {
		return visitService.update(userId(jwt), isAdmin(jwt), id, req);
	}

	@DeleteMapping("/visits/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Xoá mềm lượt khám; ảnh đang gắn được gỡ ra, không bị xoá")
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		visitService.delete(userId(jwt), isAdmin(jwt), id);
	}

	@PutMapping("/visits/{id}/image")
	@Operation(summary = "Gắn ảnh vào lượt khám; lượt khám đã có ảnh khác → 409 VISIT_ALREADY_HAS_IMAGE, ảnh đã gắn chỗ khác → 409 IMAGE_ALREADY_ATTACHED")
	public VisitResponse attachImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody AttachImageRequest req) {
		return withPrediction(visitService.attachImage(userId(jwt), isAdmin(jwt), id, req.imageId()), userId(jwt));
	}

	@DeleteMapping("/visits/{id}/image")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Gỡ ảnh khỏi lượt khám (không xoá ảnh)")
	public void detachImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		visitService.detachImage(userId(jwt), isAdmin(jwt), id);
	}

	@PostMapping("/visits/{id}/predictions")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Dự đoán lại ảnh đang gắn (thử nghiệm). Lỗi service vẫn trả 201 với status FAILED; chưa có ảnh → 400 VISIT_HAS_NO_IMAGE; tắt dự đoán → 503 PREDICT_DISABLED")
	public PredictionResponse predict(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		VisitResponse visit = visitService.get(userId(jwt), isAdmin(jwt), id);
		if (visit.image() == null) {
			throw ApiException.badRequest("VISIT_HAS_NO_IMAGE", "Lượt khám chưa có ảnh X-quang.");
		}
		if (!predictionService.enabled()) {
			throw PredictionService.disabled();
		}
		return predictionService.run(visit.id(), visit.image().id(), userId(jwt));
	}

	/** Chạy sau khi transaction lưu lượt khám đã commit. Ảnh đã có kết quả (gắn lại cùng ảnh) thì không chạy lại. */
	private VisitResponse withPrediction(VisitResponse visit, UUID actorId) {
		if (visit.image() == null || visit.latestPrediction() != null || !predictionService.enabled()) {
			return visit;
		}
		return visit.withLatestPrediction(predictionService.run(visit.id(), visit.image().id(), actorId));
	}

	private static boolean isAdmin(Jwt jwt) {
		return "ADMIN".equals(jwt.getClaimAsString("role"));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
