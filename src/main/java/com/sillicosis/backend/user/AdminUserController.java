package com.sillicosis.backend.user;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.user.dto.CreateUserRequest;
import com.sillicosis.backend.user.dto.UpdateStatusRequest;
import com.sillicosis.backend.user.dto.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Quản lý người dùng — chỉ ADMIN. */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Quản lý người dùng (ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

	/** Các trường được phép sắp xếp. */
	public enum SortField {

		createdAt, fullName, email, role

	}

	private final UserService userService;

	@GetMapping
	@Operation(summary = "Danh sách người dùng (phân trang, lọc theo vai trò / từ khoá email, tên, SĐT)")
	public PageResponse<UserResponse> list(@RequestParam(required = false) Role role,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "createdAt") SortField sort,
			@RequestParam(defaultValue = "DESC") Sort.Direction direction) {
		var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(direction, sort.name()));
		return userService.list(role, q, pageable);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Chi tiết một người dùng")
	public UserResponse get(@PathVariable UUID id) {
		return userService.get(id);
	}

	@PostMapping
	@Operation(summary = "Tạo tài khoản người dùng")
	public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
		UserResponse created = userService.create(req);
		return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
	}

	@PatchMapping("/{id}/status")
	@Operation(summary = "Khoá (enabled=false) hoặc mở khoá (enabled=true) tài khoản")
	public UserResponse updateStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody UpdateStatusRequest req) {
		return userService.updateStatus(UserController.userId(jwt), id, req.enabled());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Xoá mềm tài khoản")
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		userService.delete(UserController.userId(jwt), id);
	}

}
