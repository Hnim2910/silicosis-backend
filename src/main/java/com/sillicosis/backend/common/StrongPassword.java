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

/** Mật khẩu 8–72 ký tự, có ít nhất một chữ cái và một chữ số (72 là giới hạn của BCrypt). */
@Documented
@NotNull
@Pattern(regexp = "^(?=.*\\p{L})(?=.*\\d).{8,72}$")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

	String message() default "Mật khẩu phải dài 8–72 ký tự và có cả chữ lẫn số.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
