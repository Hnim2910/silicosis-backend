package com.sillicosis.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

	@ParameterizedTest
	@ValueSource(strings = { "0912345678", "+84912345678", "84912345678", "091 234 5678", "091.234.5678",
			"(+84) 39-123-4567" })
	void acceptsVietnameseMobileFormats(String raw) {
		assertThat(PhoneNumbers.isValid(raw)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0212345678", "091234567", "09123456789", "abc", "+1 415 555 0100", "" })
	void rejectsInvalidNumbers(String raw) {
		assertThat(PhoneNumbers.isValid(raw)).isFalse();
	}

	@Test
	void normalizesToLocalFormat() {
		assertThat(PhoneNumbers.normalize("+84 912 345 678")).isEqualTo("0912345678");
		assertThat(PhoneNumbers.normalize("   ")).isNull();
	}

}
