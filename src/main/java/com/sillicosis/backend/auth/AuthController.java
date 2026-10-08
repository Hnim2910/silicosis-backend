package com.sillicosis.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sillicosis.backend.auth.dto.AuthResponse;
import com.sillicosis.backend.auth.dto.ForgotPasswordRequest;
import com.sillicosis.backend.auth.dto.LoginRequest;
import com.sillicosis.backend.auth.dto.RefreshTokenRequest;
import com.sillicosis.backend.auth.dto.RegisterRequest;
import com.sillicosis.backend.auth.dto.ResetPasswordRequest;
import com.sillicosis.backend.auth.dto.VerifyOtpRequest;
import com.sillicosis.backend.auth.dto.VerifyOtpResponse;
import com.sillicosis.backend.common.MessageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Đăng ký, đăng nhập, làm mới token, quên mật khẩu")
public class AuthController {

	private final AuthService authService;
	private final PasswordResetService passwordResetService;

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Bệnh nhân tự đăng ký; trả token luôn")
	public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
		return authService.register(req);
	}

	@PostMapping("/login")
	@Operation(summary = "Đăng nhập bằng email hoặc số điện thoại")
	public AuthResponse login(@Valid @RequestBody LoginRequest req) {
		return authService.login(req);
	}

	@PostMapping("/refresh")
	@Operation(summary = "Đổi refresh token lấy cặp token mới (refresh token cũ bị thu hồi)")
	public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest req) {
		return authService.refresh(req.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Thu hồi refresh token")
	public void logout(@Valid @RequestBody RefreshTokenRequest req) {
		authService.logout(req.refreshToken());
	}

	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Gửi OTP đặt lại mật khẩu qua email (luôn trả cùng một thông báo)")
	public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
		passwordResetService.requestOtp(req.email());
		return new MessageResponse(PasswordResetService.GENERIC_MESSAGE);
	}

	@PostMapping("/verify-otp")
	@Operation(summary = "Xác minh OTP, nhận reset token dùng một lần")
	public VerifyOtpResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
		return passwordResetService.verifyOtp(req.email(), req.otp());
	}

	@PostMapping("/reset-password")
	@Operation(summary = "Đặt mật khẩu mới bằng reset token; thu hồi mọi phiên đăng nhập")
	public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
		passwordResetService.resetPassword(req.resetToken(), req.newPassword());
		return new MessageResponse("Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại.");
	}

}
