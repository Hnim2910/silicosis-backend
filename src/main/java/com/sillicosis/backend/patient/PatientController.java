package com.sillicosis.backend.patient;

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

import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.patient.dto.PatientRequest;
import com.sillicosis.backend.patient.dto.PatientResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Hồ sơ bệnh nhân. Người không có quyền xem một hồ sơ nhận 404 PATIENT_NOT_FOUND (không phải 403). */
@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
@Tag(name = "Hồ sơ bệnh nhân")
@SecurityRequirement(name = "bearerAuth")
public class PatientController {

	/** Các trường được phép sắp xếp. */
	public enum SortField {

		createdAt, fullName, code

	}

	private final PatientService patientService;

	@GetMapping("/me")
	@Operation(summary = "Hồ sơ của tài khoản đang đăng nhập (USER); ADMIN không có hồ sơ → 404")
	public PatientResponse me(@AuthenticationPrincipal Jwt jwt) {
		return patientService.getMine(userId(jwt));
	}

	@GetMapping
	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Danh sách hồ sơ (ADMIN); q tìm theo họ tên, mã, SĐT, nghề nghiệp — không phân biệt hoa thường và dấu")
	public PageResponse<PatientResponse> list(@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
			@RequestParam(defaultValue = "createdAt") SortField sort,
			@RequestParam(defaultValue = "DESC") Sort.Direction direction) {
		var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(direction, sort.name()));
		return patientService.list(q, pageable);
	}

	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Tạo hồ sơ không gắn tài khoản (ADMIN); mã BN-xxxxxx do server sinh")
	public ResponseEntity<PatientResponse> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody PatientRequest req) {
		PatientResponse created = patientService.create(userId(jwt), req);
		return ResponseEntity.created(URI.create("/api/patients/" + created.id())).body(created);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Chi tiết hồ sơ; ADMIN hoặc chủ hồ sơ")
	public PatientResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return patientService.get(userId(jwt), isAdmin(jwt), id);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Sửa hồ sơ (ghi đè toàn bộ); ADMIN hoặc chủ hồ sơ. Họ tên, ngày sinh, giới tính, nghề nghiệp đồng bộ sang tài khoản")
	public PatientResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody PatientRequest req) {
		return patientService.update(userId(jwt), isAdmin(jwt), id, req);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Xoá mềm hồ sơ (ADMIN); hồ sơ gắn tài khoản đang hoạt động → 409 PATIENT_LINKED_TO_ACCOUNT")
	public void delete(@AuthenticationPrincipal Jwt jwt, @Parameter(description = "id hồ sơ") @PathVariable UUID id) {
		patientService.delete(userId(jwt), id);
	}

	private static boolean isAdmin(Jwt jwt) {
		return "ADMIN".equals(jwt.getClaimAsString("role"));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
