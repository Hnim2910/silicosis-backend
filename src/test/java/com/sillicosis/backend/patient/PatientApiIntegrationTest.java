package com.sillicosis.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.sillicosis.backend.TestcontainersConfiguration;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

/** Hồ sơ bệnh nhân trên Postgres thật (Testcontainers). Dữ liệu đều là dữ liệu giả. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PatientApiIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PatientRepository patientRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void registeredUserOwnsOnePatientAndProfileSyncsBothWays() throws Exception {
		String email = uniqueEmail();
		String token = register("Bệnh Nhân Thử", email);

		// Đăng ký xong có hồ sơ, lấy sẵn họ tên từ tài khoản
		String mine = authed(get("/api/patients/me"), token, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.fullName").value("Bệnh Nhân Thử"))
			.andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("BN-\\d{6}")))
			.andExpect(jsonPath("$.userId").isNotEmpty())
			.andExpect(jsonPath("$.workplace").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String patientId = JsonPath.read(mine, "$.id");
		String code = JsonPath.read(mine, "$.code");
		String userId = JsonPath.read(mine, "$.userId");

		// Sửa hồ sơ của mình → tài khoản đổi theo 4 trường, SĐT tài khoản giữ nguyên
		authed(put("/api/patients/" + patientId), token, """
				{"fullName":"Tên Hồ Sơ Mới","birthDate":"1980-05-20","gender":"MALE","phone":"0333000111",
				 "occupation":"Thợ giả định","workplace":"Xưởng giả định","exposureHistory":"Giả","riskFactors":"Không",
				 "code":"BN-999999","userId":"%s"}
				""".formatted(UUID.randomUUID()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.userId").value(userId))
			.andExpect(jsonPath("$.phone").value("0333000111"));
		authed(get("/api/users/me"), token, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.fullName").value("Tên Hồ Sơ Mới"))
			.andExpect(jsonPath("$.birthDate").value("1980-05-20"))
			.andExpect(jsonPath("$.gender").value("MALE"))
			.andExpect(jsonPath("$.occupation").value("Thợ giả định"))
			.andExpect(jsonPath("$.phone").doesNotExist());

		// Sửa /users/me → hồ sơ đổi theo, SĐT hồ sơ giữ nguyên
		authed(put("/api/users/me"), token, """
				{"fullName":"Tên Tài Khoản Mới","email":"%s","birthDate":"1979-01-02","gender":"FEMALE","occupation":"Nghề khác"}
				""".formatted(email)).andExpect(status().isOk());
		authed(get("/api/patients/" + patientId), token, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.fullName").value("Tên Tài Khoản Mới"))
			.andExpect(jsonPath("$.birthDate").value("1979-01-02"))
			.andExpect(jsonPath("$.gender").value("FEMALE"))
			.andExpect(jsonPath("$.occupation").value("Nghề khác"))
			.andExpect(jsonPath("$.phone").value("0333000111"))
			.andExpect(jsonPath("$.workplace").value("Xưởng giả định"));

		// Kiểm tra dữ liệu
		authed(put("/api/patients/" + patientId), token, """
				{"fullName":"A","birthDate":"2999-01-01","occupation":"","phone":"123"}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.fieldErrors.fullName").exists())
			.andExpect(jsonPath("$.fieldErrors.birthDate").exists())
			.andExpect(jsonPath("$.fieldErrors.occupation").exists())
			.andExpect(jsonPath("$.fieldErrors.phone").exists());
		authed(put("/api/patients/" + patientId), token, """
				{"fullName":"Tên Thử","birthDate":"1980-01-01","occupation":"Nghề","exposureHistory":"%s"}
				""".formatted("x".repeat(2001)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.fieldErrors.exposureHistory").exists());

		assertThat(patientRepository.findByUserIdAndDeletedAtIsNull(UUID.fromString(userId))).isPresent();
	}

	@Test
	void userCannotSeeOthersOrUseAdminEndpoints() throws Exception {
		String tokenA = register("Người Thử A", uniqueEmail());
		String tokenB = register("Người Thử B", uniqueEmail());
		String idB = JsonPath.read(authed(get("/api/patients/me"), tokenB, null).andReturn().getResponse()
			.getContentAsString(), "$.id");

		authed(get("/api/patients/" + idB), tokenA, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
		authed(put("/api/patients/" + idB), tokenA, """
				{"fullName":"Kẻ Lạ","birthDate":"1980-01-01","occupation":"Nghề"}
				""").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
		authed(get("/api/patients/" + UUID.randomUUID()), tokenA, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));

		authed(get("/api/patients"), tokenA, null).andExpect(status().isForbidden());
		authed(post("/api/patients"), tokenA, """
				{"fullName":"Tên Thử","birthDate":"1980-01-01","occupation":"Nghề"}
				""").andExpect(status().isForbidden());
		authed(delete("/api/patients/" + idB), tokenA, null).andExpect(status().isForbidden());
		mvc.perform(get("/api/patients/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void adminManagesPatients() throws Exception {
		String adminToken = adminToken();
		String tag = UUID.randomUUID().toString().substring(0, 8);

		// ADMIN không có hồ sơ
		authed(get("/api/patients/me"), adminToken, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));

		// Tạo hồ sơ không gắn tài khoản
		String created = authed(post("/api/patients"), adminToken, """
				{"fullName":"Nguyễn Thị Thử %s","birthDate":"1970-02-03","gender":"FEMALE","phone":"+84 912 000 222",
				 "occupation":"Thợ đá giả định","workplace":"Mỏ giả định","exposureHistory":"10 năm (giả)","riskFactors":"Hút thuốc (giả)"}
				""".formatted(tag))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/patients/")))
			.andExpect(jsonPath("$.userId").doesNotExist())
			.andExpect(jsonPath("$.phone").value("0912000222"))
			.andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("BN-\\d{6}")))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");
		String code = JsonPath.read(created, "$.code");

		// Tìm không phân biệt hoa thường và dấu; theo mã; theo nghề nghiệp
		authed(get("/api/patients").param("q", "NGUYEN THI THU " + tag), adminToken, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].id").value(id));
		authed(get("/api/patients").param("q", code.toLowerCase()), adminToken, null)
			.andExpect(jsonPath("$.totalElements").value(1));
		authed(get("/api/patients").param("q", "tho da gia dinh").param("size", "100"), adminToken, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(id)).exists());
		authed(get("/api/patients").param("sort", "fullName").param("direction", "ASC"), adminToken, null)
			.andExpect(status().isOk());

		// Sửa
		authed(put("/api/patients/" + id), adminToken, """
				{"fullName":"Trần Văn Thử %s","birthDate":"1970-02-03","occupation":"Thợ đá giả định"}
				""".formatted(tag))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.workplace").doesNotExist());

		// Xoá mềm → biến khỏi danh sách và GET trả 404
		mvc.perform(delete("/api/patients/" + id).header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isNoContent());
		authed(get("/api/patients/" + id), adminToken, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
		authed(get("/api/patients").param("q", tag), adminToken, null).andExpect(jsonPath("$.totalElements").value(0));
		assertThat(patientRepository.findById(UUID.fromString(id))).get()
			.satisfies(p -> assertThat(p.getDeletedAt()).isNotNull());
	}

	@Test
	void adminCreatedUserAlsoGetsPatient() throws Exception {
		String adminToken = adminToken();
		String email = uniqueEmail();
		String user = authed(post("/api/users"), adminToken, """
				{"fullName":"Người Do Admin Tạo","email":"%s","password":"matkhau123"}
				""".formatted(email)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String userId = JsonPath.read(user, "$.id");

		String token = accessToken(email, "matkhau123");
		String mine = authed(get("/api/patients/me"), token, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.fullName").value("Người Do Admin Tạo"))
			.andReturn().getResponse().getContentAsString();
		String patientId = JsonPath.read(mine, "$.id");

		// Hồ sơ gắn tài khoản đang hoạt động → không xoá được, tài khoản không mất hồ sơ
		authed(delete("/api/patients/" + patientId), adminToken, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PATIENT_LINKED_TO_ACCOUNT"));
		authed(get("/api/patients/me"), token, null).andExpect(status().isOk());

		// Xoá tài khoản → hồ sơ vẫn giữ; sau đó ADMIN xoá được hồ sơ
		authed(delete("/api/users/" + userId), adminToken, null).andExpect(status().isNoContent());
		authed(get("/api/patients/" + patientId), adminToken, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(userId));
		authed(delete("/api/patients/" + patientId), adminToken, null).andExpect(status().isNoContent());
	}

	// ---------- Tiện ích ----------

	private String register(String fullName, String email) throws Exception {
		String body = authed(post("/api/auth/register"), null, """
				{"fullName":"%s","email":"%s","password":"matkhau123"}
				""".formatted(fullName, email)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private String adminToken() throws Exception {
		String email = uniqueEmail();
		User admin = new User();
		admin.setEmail(email);
		admin.setFullName("Quản Trị Thử");
		admin.setRole(Role.ADMIN);
		admin.setPasswordHash(passwordEncoder.encode("admin12345"));
		userRepository.saveAndFlush(admin);
		return accessToken(email, "admin12345");
	}

	private String accessToken(String identifier, String password) throws Exception {
		String body = authed(post("/api/auth/login"), null,
				"{\"identifier\":\"%s\",\"password\":\"%s\"}".formatted(identifier, password))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private ResultActions authed(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private static String uniqueEmail() {
		return "test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

}
