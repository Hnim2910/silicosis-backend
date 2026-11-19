package com.sillicosis.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class StrongPasswordTest {

	private record Form(@StrongPassword String password) {
	}

	private static ValidatorFactory factory;

	private static Validator validator;

	@BeforeAll
	static void setUp() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		factory.close();
	}

	@ParameterizedTest
	@ValueSource(strings = { "MatKhau@123", "Aa1!aaaa", "Đổi#Mật9khẩu" })
	void acceptsStrongPasswords(String password) {
		assertThat(validator.validate(new Form(password))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = { 8, 72 })
	void acceptsBothLengthLimits(int length) {
		String password = "Aa1@" + "x".repeat(length - 4);
		assertThat(validator.validate(new Form(password))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"matkhau123", // no upper case, no special character
			"MATKHAU@123", // no lower case
			"MatKhau@abc", // no digit
			"MatKhau123", // no special character
			"Mk@1abc", // 7 characters
			"Mat Khau@123", // space
	})
	void rejectsWeakPasswords(String password) {
		assertThat(validator.validate(new Form(password))).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(ints = { 73 })
	void rejectsPasswordsLongerThanBcryptAllows(int length) {
		String password = "Aa1@" + "x".repeat(length - 4);
		assertThat(validator.validate(new Form(password))).hasSize(1);
	}

}
