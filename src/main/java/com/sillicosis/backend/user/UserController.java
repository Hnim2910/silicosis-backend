package com.sillicosis.backend.user;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sillicosis.backend.user.dto.ChangePasswordRequest;
import com.sillicosis.backend.user.dto.UpdateProfileRequest;
import com.sillicosis.backend.user.dto.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Tài khoản của người đang đăng nhập (mọi vai trò). */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
@Tag(name = "Tài khoản của tôi")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

	private final UserService userService;

	@GetMapping
	@Operation(summary = "Lấy thông tin tài khoản hiện tại")
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return userService.getMe(userId(jwt));
	}

	@PutMapping
	@Operation(summary = "Cập nhật hồ sơ (họ tên, email, SĐT, ngày sinh, giới tính, nghề nghiệp)")
	public UserResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest req) {
		return userService.updateMe(userId(jwt), req);
	}

	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Đổi mật khẩu (cần mật khẩu cũ); thu hồi mọi refresh token")
	public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
		userService.changePassword(userId(jwt), req);
	}

	static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
