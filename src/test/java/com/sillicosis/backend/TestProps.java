package com.sillicosis.backend;

import java.time.Duration;
import java.util.List;

import com.sillicosis.backend.config.AppProperties;

/** AppProperties với giá trị mặc định như application.yaml, dùng cho unit test. */
public final class TestProps {

	private TestProps() {
	}

	public static AppProperties defaults() {
		return new AppProperties(
				new AppProperties.Jwt("test-only-secret-0123456789-abcdefghijklmnopqrstuvwxyz", "silicocare",
						Duration.ofMinutes(15), Duration.ofDays(7)),
				new AppProperties.Security(5, Duration.ofMinutes(15)),
				new AppProperties.Otp(Duration.ofMinutes(5), 5, Duration.ofSeconds(60), Duration.ofMinutes(15)),
				new AppProperties.Mail("SilicoCare <no-reply@silicocare.local>"),
				new AppProperties.Admin(null, null, "Quản trị viên"),
				new AppProperties.Cors(List.of("http://localhost:3000")),
				new AppProperties.Storage(System.getProperty("java.io.tmpdir"),
						org.springframework.util.unit.DataSize.ofMegabytes(10), 256, 8000),
				new AppProperties.Patient(2000),
				new AppProperties.Visit(org.springframework.util.unit.DataSize.ofKilobytes(8), 3),
				new AppProperties.Predict(false, "http://localhost:8001", Duration.ofSeconds(30), Duration.ofSeconds(3)));
	}

}
