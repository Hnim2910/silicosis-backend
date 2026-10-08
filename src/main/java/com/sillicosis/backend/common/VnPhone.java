package com.sillicosis.backend.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** Số di động Việt Nam (0 hoặc +84, đầu số 3/5/7/8/9, 8 chữ số sau). Giá trị rỗng được coi là hợp lệ. */
@Documented
@Constraint(validatedBy = VnPhone.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface VnPhone {

	String message() default "Số điện thoại Việt Nam không hợp lệ.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<VnPhone, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			return value == null || value.isBlank() || PhoneNumbers.isValid(value);
		}

	}

}
