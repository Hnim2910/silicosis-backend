package com.sillicosis.backend.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Chuyển mọi lỗi thành {@link ApiError}. Không đưa giá trị người dùng nhập vào log hay phản hồi.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
		return ResponseEntity.status(ex.getStatus())
			.body(ApiError.of(ex.getStatus().value(), ex.getCode(), ex.getMessage(), req.getRequestURI(), ex.getDetails()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
		}
		return validationError(fields, req);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
		Map<String, String> fields = new LinkedHashMap<>();
		ex.getConstraintViolations()
			.forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
		return validationError(fields, req);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class, MissingServletRequestPartException.class })
	ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest req) {
		return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Yêu cầu không đúng định dạng.", req);
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
		return build(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "Ảnh vượt quá dung lượng cho phép.", req);
	}

	/** Hai yêu cầu đồng thời vượt qua kiểm tra trùng và va vào unique index. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
		return build(HttpStatus.CONFLICT, "CONFLICT", "Dữ liệu bị trùng hoặc xung đột, vui lòng thử lại.", req);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
		return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bạn cần đăng nhập để tiếp tục.", req);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
		return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này.", req);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
		return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ.", req);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
		return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên.", req);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
		// Chỉ log đường dẫn và stack trace; không log body request.
		log.error("Lỗi không mong đợi tại {}", req.getRequestURI(), ex);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"Đã có lỗi xảy ra, vui lòng thử lại sau.", req);
	}

	private ResponseEntity<ApiError> validationError(Map<String, String> fields, HttpServletRequest req) {
		return ResponseEntity.badRequest()
			.body(new ApiError(Instant.now(), 400, "VALIDATION_FAILED", "Dữ liệu không hợp lệ.",
					req.getRequestURI(), fields));
	}

	private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest req) {
		return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message, req.getRequestURI()));
	}

}
