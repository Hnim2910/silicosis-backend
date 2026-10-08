package com.sillicosis.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

/**
 * Luồng end-to-end trên Postgres thật (Testcontainers) và SMTP giả (GreenMail). Dữ liệu đều là dữ liệu giả.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AuthFlowIntegrationTest {

	@RegisterExtension
	static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
		.withPerMethodLifecycle(false);

	private static final Pattern OTP_IN_MAIL = Pattern.compile(">\\s*(\\d{6})\\s*</p>");

	@DynamicPropertySource
	static void mailProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.mail.host", () -> "127.0.0.1");
		registry.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	// ---------- Luồng chính ----------

	@Test
	void registerLoginForgotVerifyReset() throws Exception {
		String email = uniqueEmail();

		// 1. Đăng ký → 201, vai trò USER, có token
		String registered = json(post("/api/auth/register"), """
				{"fullName":"Bệnh Nhân Thử","email":"%s","phone":"+84 912 000 %03d","password":"matkhau123"}
				""".formatted(email.toUpperCase(), (int) (Math.random() * 1000)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.role").value("USER"))
			.andExpect(jsonPath("$.user.email").value(email))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String accessToken = JsonPath.read(registered, "$.accessToken");

		// Đăng ký trùng email → 409
		json(post("/api/auth/register"), """
				{"fullName":"Người Khác","email":"%s","password":"matkhau123"}
				""".formatted(email)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

		// 2. /me với access token
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		// 3. Đăng nhập bằng email → có refresh token
		String oldRefresh = login(email, "matkhau123");

		// 4. Quên mật khẩu: email có thật và email không tồn tại nhận cùng phản hồi
		String forgotReal = json(post("/api/auth/forgot-password"), "{\"email\":\"%s\"}".formatted(email))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String forgotFake = json(post("/api/auth/forgot-password"), "{\"email\":\"%s\"}".formatted(uniqueEmail()))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(forgotFake).isEqualTo(forgotReal);

		// 5. Lấy OTP từ hộp thư GreenMail
		String otp = awaitOtp(email);

		// OTP sai → 400
		json(post("/api/auth/verify-otp"), "{\"email\":\"%s\",\"otp\":\"%s\"}".formatted(email, wrongOtp(otp)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_OTP"));

		// 6. OTP đúng → reset token
		String verified = json(post("/api/auth/verify-otp"), "{\"email\":\"%s\",\"otp\":\"%s\"}".formatted(email, otp))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String resetToken = JsonPath.read(verified, "$.resetToken");

		// 7. Đặt lại mật khẩu
		json(post("/api/auth/reset-password"), "{\"resetToken\":\"%s\",\"newPassword\":\"matkhaumoi456\"}"
			.formatted(resetToken)).andExpect(status().isOk());

		// Reset token chỉ dùng một lần
		json(post("/api/auth/reset-password"), "{\"resetToken\":\"%s\",\"newPassword\":\"matkhaukhac789\"}"
			.formatted(resetToken)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));

		// 8. Refresh token cũ đã bị thu hồi
		json(post("/api/auth/refresh"), "{\"refreshToken\":\"%s\"}".formatted(oldRefresh))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		// 9. Mật khẩu cũ không còn dùng được, mật khẩu mới thì được
		json(post("/api/auth/login"), "{\"identifier\":\"%s\",\"password\":\"matkhau123\"}".formatted(email))
			.andExpect(status().isUnauthorized());
		String newRefresh = login(email, "matkhaumoi456");

		// 10. Refresh xoay vòng token; logout thu hồi
		String rotated = json(post("/api/auth/refresh"), "{\"refreshToken\":\"%s\"}".formatted(newRefresh))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String rotatedRefresh = JsonPath.read(rotated, "$.refreshToken");
		json(post("/api/auth/logout"), "{\"refreshToken\":\"%s\"}".formatted(rotatedRefresh))
			.andExpect(status().isNoContent());
		json(post("/api/auth/refresh"), "{\"refreshToken\":\"%s\"}".formatted(rotatedRefresh))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void loginWithPhoneAndLockoutAfterFiveFailures() throws Exception {
		String email = uniqueEmail();
		String phone = "0987" + String.format("%06d", (int) (Math.random() * 1_000_000));
		json(post("/api/auth/register"), """
				{"fullName":"Người Thử SĐT","email":"%s","phone":"%s","password":"matkhau123"}
				""".formatted(email, phone)).andExpect(status().isCreated());

		// Đăng nhập bằng SĐT dạng +84
		login("+84" + phone.substring(1), "matkhau123");

		for (int i = 0; i < 4; i++) {
			json(post("/api/auth/login"), "{\"identifier\":\"%s\",\"password\":\"saimatkhau1\"}".formatted(phone))
				.andExpect(status().isUnauthorized());
		}
		json(post("/api/auth/login"), "{\"identifier\":\"%s\",\"password\":\"saimatkhau1\"}".formatted(phone))
			.andExpect(status().isLocked())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
		// Bị khoá tạm thì mật khẩu đúng cũng không vào được
		json(post("/api/auth/login"), "{\"identifier\":\"%s\",\"password\":\"matkhau123\"}".formatted(phone))
			.andExpect(status().isLocked());
	}

	// ---------- Validation & định dạng lỗi ----------

	@Test
	void validationErrorsUseUnifiedFormat() throws Exception {
		json(post("/api/auth/register"), """
				{"fullName":"","email":"khong-phai-email","phone":"12345","password":"ngan"}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.path").value("/api/auth/register"))
			.andExpect(jsonPath("$.fieldErrors.email").exists())
			.andExpect(jsonPath("$.fieldErrors.phone").exists())
			.andExpect(jsonPath("$.fieldErrors.password").exists())
			.andExpect(jsonPath("$.fieldErrors.fullName").exists());
	}

	@Test
	void swaggerIsPublic() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/auth/login']").exists())
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists());
	}

	// ---------- Phân quyền ----------

	@Test
	void adminEndpointsRequireAdminRole() throws Exception {
		// Không có token → 401
		mvc.perform(get("/api/users")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer khong-hop-le"))
			.andExpect(status().isUnauthorized());

		// USER → 403
		String patientEmail = uniqueEmail();
		String registered = json(post("/api/auth/register"), """
				{"fullName":"Bệnh Nhân","email":"%s","password":"matkhau123"}
				""".formatted(patientEmail)).andReturn().getResponse().getContentAsString();
		String patientToken = JsonPath.read(registered, "$.accessToken");
		mvc.perform(get("/api/users").header("Authorization", "Bearer " + patientToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	void adminManagesUsers() throws Exception {
		String adminEmail = uniqueEmail();
		User admin = new User();
		admin.setEmail(adminEmail);
		admin.setFullName("Quản Trị Thử");
		admin.setRole(Role.ADMIN);
		admin.setPasswordHash(passwordEncoder.encode("admin12345"));
		userRepository.saveAndFlush(admin);
		String adminToken = accessToken(adminEmail, "admin12345");

		// Admin tạo tài khoản: luôn là USER, trường "role" gửi kèm bị bỏ qua
		String memberEmail = uniqueEmail();
		String created = authed(post("/api/users"), adminToken, """
				{"fullName":"Bệnh Nhân Thử","email":"%s","password":"thanhvien123","role":"ADMIN"}
				""".formatted(memberEmail))
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.role").value("USER"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String memberId = JsonPath.read(created, "$.id");

		// Danh sách lọc theo vai trò + từ khoá
		mvc.perform(get("/api/users").param("role", "USER").param("q", memberEmail)
				.header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].email").value(memberEmail));

		// Người dùng đăng nhập được
		String memberToken = accessToken(memberEmail, "thanhvien123");

		// Khoá tài khoản → không đăng nhập được, /me bị từ chối
		authed(patch("/api/users/" + memberId + "/status"), adminToken, "{\"enabled\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false));
		json(post("/api/auth/login"), "{\"identifier\":\"%s\",\"password\":\"thanhvien123\"}".formatted(memberEmail))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
		mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + memberToken))
			.andExpect(status().isForbidden());

		// Mở khoá
		authed(patch("/api/users/" + memberId + "/status"), adminToken, "{\"enabled\":true}")
			.andExpect(status().isOk());
		accessToken(memberEmail, "thanhvien123");

		// Admin không tự khoá/xoá chính mình
		authed(patch("/api/users/" + admin.getId() + "/status"), adminToken, "{\"enabled\":false}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF"));

		// Xoá mềm → 204, sau đó 404 và email được giải phóng
		mvc.perform(delete("/api/users/" + memberId).header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/users/" + memberId).header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isNotFound());
		assertThat(userRepository.findById(UUID.fromString(memberId))).get()
			.satisfies(u -> assertThat(u.getDeletedAt()).isNotNull());
		json(post("/api/auth/register"), """
				{"fullName":"Dùng Lại Email","email":"%s","password":"matkhau123"}
				""".formatted(memberEmail)).andExpect(status().isCreated());
	}

	@Test
	void userUpdatesProfileAndChangesPassword() throws Exception {
		String email = uniqueEmail();
		json(post("/api/auth/register"), """
				{"fullName":"Người Dùng","email":"%s","password":"matkhau123"}
				""".formatted(email)).andExpect(status().isCreated());
		String token = accessToken(email, "matkhau123");

		authed(put("/api/users/me"), token, """
				{"fullName":"Người Dùng Mới","email":"%s","birthDate":"1980-05-20","gender":"MALE","occupation":"Thợ mỏ"}
				""".formatted(email))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fullName").value("Người Dùng Mới"))
			.andExpect(jsonPath("$.gender").value("MALE"));

		authed(put("/api/users/me/password"), token, """
				{"currentPassword":"saimatkhau1","newPassword":"matkhaumoi456"}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
		authed(put("/api/users/me/password"), token, """
				{"currentPassword":"matkhau123","newPassword":"matkhaumoi456"}
				""").andExpect(status().isNoContent());
		accessToken(email, "matkhaumoi456");
	}

	// ---------- Tiện ích ----------

	private static String uniqueEmail() {
		return "test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private static String wrongOtp(String otp) {
		return otp.equals("000000") ? "111111" : "000000";
	}

	private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions authed(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
		return json(request.header("Authorization", "Bearer " + token), body);
	}

	private String loginBody(String identifier, String password) throws Exception {
		return json(post("/api/auth/login"),
				"{\"identifier\":\"%s\",\"password\":\"%s\"}".formatted(identifier, password))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	/** Đăng nhập, trả refresh token. */
	private String login(String identifier, String password) throws Exception {
		return JsonPath.read(loginBody(identifier, password), "$.refreshToken");
	}

	private String accessToken(String identifier, String password) throws Exception {
		return JsonPath.read(loginBody(identifier, password), "$.accessToken");
	}

	private static String awaitOtp(String recipient) throws Exception {
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			for (MimeMessage message : greenMail.getReceivedMessagesForDomain(recipient)) {
				Matcher m = OTP_IN_MAIL.matcher(text(message));
				if (m.find()) {
					return m.group(1);
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Không nhận được email OTP");
	}

	private static String text(Part part) throws Exception {
		Object content = part.getContent();
		if (content instanceof String s) {
			return s;
		}
		if (content instanceof Multipart mp) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < mp.getCount(); i++) {
				sb.append(text(mp.getBodyPart(i)));
			}
			return sb.toString();
		}
		return "";
	}

}
