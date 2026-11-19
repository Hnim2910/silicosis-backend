package com.sillicosis.backend.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Mật khẩu 8–72 ký tự (72 là giới hạn của BCrypt), có ít nhất một chữ thường, một chữ hoa, một chữ số và một ký tự
 * đặc biệt, không chứa khoảng trắng. Chỉ áp dụng khi đặt mật khẩu mới; mật khẩu cũ vẫn đăng nhập được.
 */
@Documented
@NotNull
@Pattern(regexp = "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*\\d)(?=.*[^\\p{L}\\d\\s])\\S{8,72}$")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

	String message() default "Mật khẩu phải dài 8–72 ký tự, gồm chữ hoa, chữ thường, chữ số và ký tự đặc biệt, không chứa khoảng trắng.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
