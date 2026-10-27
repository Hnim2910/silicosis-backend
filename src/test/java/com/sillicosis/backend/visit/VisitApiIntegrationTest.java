package com.sillicosis.backend.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.sillicosis.backend.TestcontainersConfiguration;
import com.sillicosis.backend.image.ImageRepository;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

/** Lượt khám trên Postgres thật (Testcontainers). Dữ liệu đều là dữ liệu giả. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class VisitApiIntegrationTest {

	private static final Path IMAGE_DIR = temporaryImageDirectory();

	private static final String VISIT = """
			{"visitDate":"2025-06-20","industry":"Khai thác đá (giả)","yearsInJob":10,"province":"Tỉnh giả định",
			 "yearsExposed":8,"heightCm":170,"weightKg":65,"systolicBp":120,"diastolicBp":80,"pulse":75,
			 "smoker":false,"cigarettesPerDay":5,"fvcL":3.5,"fev1L":2.8,"extra":{"ghiChu":"giả","doBui":{"muc":2}}}
			""";

	@DynamicPropertySource
	static void imageStorageProperties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.images-dir", () -> IMAGE_DIR.toString());
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ImageRepository imageRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void ownerManagesVisitsAndOthersGet404() throws Exception {
		String token = register("Người Thử Khám");
		String patientId = myPatientId(token);

		// Gửi rỗng → 400; hồ sơ chưa có ngày sinh vẫn gửi được, chỉ chưa có tuổi
		authed(post("/api/patients/" + patientId + "/visits"), token, "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("EMPTY_SUBMISSION"));
		authed(post("/api/patients/" + patientId + "/visits"), token, "{\"weightKg\":60}")
			.andExpect(status().isCreated()).andExpect(jsonPath("$.ageAtVisit").doesNotExist())
			.andExpect(jsonPath("$.visitDate").isNotEmpty());

		completeProfile(token, patientId);

		// Tạo: tuổi và BMI tính khi trả về, số điếu bỏ vì không hút thuốc, extra lưu JSONB
		String created = authed(post("/api/patients/" + patientId + "/visits"), token, VISIT)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/visits/")))
			.andExpect(jsonPath("$.patientId").value(patientId))
			.andExpect(jsonPath("$.ageAtVisit").value(45))
			.andExpect(jsonPath("$.bmi").value(22.5))
			.andExpect(jsonPath("$.cigarettesPerDay").doesNotExist())
			.andExpect(jsonPath("$.schemaVersion").value(1))
			.andExpect(jsonPath("$.extra.doBui.muc").value(2))
			.andExpect(jsonPath("$.image").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String visitId = JsonPath.read(created, "$.id");

		// Lượt khám thứ hai mới hơn → đứng đầu danh sách
		authed(post("/api/patients/" + patientId + "/visits"), token, VISIT.replace("2025-06-20", "2025-09-01"))
			.andExpect(status().isCreated());
		authed(get("/api/patients/" + patientId + "/visits"), token, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.content[2].visitDate").value("2025-06-20"))
			.andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(visitId)).isNotEmpty());

		// Kiểm tra dữ liệu: bắt buộc, khoảng giá trị, liên trường
		authed(post("/api/patients/" + patientId + "/visits"), token, """
				{"visitDate":"2999-01-01","industry":"","yearsInJob":70,"heightCm":50,"systolicBp":80,"diastolicBp":90,
				 "fvcL":2.0,"fev1L":2.5,"extra":[1,2]}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.fieldErrors.visitDate").exists())
			.andExpect(jsonPath("$.fieldErrors.yearsInJob").exists())
			.andExpect(jsonPath("$.fieldErrors.heightCm").exists());
		authed(post("/api/patients/" + patientId + "/visits"), token, VISIT.replace("\"diastolicBp\":80",
				"\"diastolicBp\":130").replace("\"extra\":{\"ghiChu\":\"giả\",\"doBui\":{\"muc\":2}}", "\"extra\":[1]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.systolicBp").exists())
			.andExpect(jsonPath("$.fieldErrors.extra").exists());

		// Sửa: ghi đè toàn bộ
		authed(put("/api/visits/" + visitId), token, """
				{"visitDate":"2025-06-20","industry":"Xây dựng (giả)","yearsInJob":11,"province":"Tỉnh khác","smoker":true,
				 "cigarettesPerDay":10}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.industry").value("Xây dựng (giả)"))
			.andExpect(jsonPath("$.cigarettesPerDay").value(10))
			.andExpect(jsonPath("$.heightCm").doesNotExist())
			.andExpect(jsonPath("$.bmi").doesNotExist())
			.andExpect(jsonPath("$.extra").isEmpty());

		// Người khác: 404 ở mọi thao tác
		String other = register("Người Lạ Thử");
		authed(get("/api/visits/" + visitId), other, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VISIT_NOT_FOUND"));
		authed(put("/api/visits/" + visitId), other, VISIT).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VISIT_NOT_FOUND"));
		authed(delete("/api/visits/" + visitId), other, null).andExpect(status().isNotFound());
		authed(get("/api/patients/" + patientId + "/visits"), other, null).andExpect(status().isNotFound());
		authed(post("/api/patients/" + patientId + "/visits"), other, VISIT).andExpect(status().isNotFound());

		// Xoá mềm
		authed(delete("/api/visits/" + visitId), token, null).andExpect(status().isNoContent());
		authed(get("/api/visits/" + visitId), token, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VISIT_NOT_FOUND"));
		authed(get("/api/patients/" + patientId + "/visits"), token, null)
			.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void adminCanManageAnyPatientsVisits() throws Exception {
		String token = register("Người Thử Admin Xem");
		String patientId = myPatientId(token);
		completeProfile(token, patientId);
		String admin = adminToken();

		String created = authed(post("/api/patients/" + patientId + "/visits"), admin, VISIT)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String visitId = JsonPath.read(created, "$.id");
		authed(get("/api/patients/" + patientId + "/visits"), admin, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1));
		authed(put("/api/visits/" + visitId), admin, VISIT.replace("Tỉnh giả định", "Tỉnh admin sửa"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.province").value("Tỉnh admin sửa"));

		// ADMIN gắn được ảnh của chủ hồ sơ
		String imageId = upload(token, 300, 310);
		authed(put("/api/visits/" + visitId + "/image"), admin, "{\"imageId\":\"%s\"}".formatted(imageId))
			.andExpect(status().isOk()).andExpect(jsonPath("$.image.id").value(imageId));

		// Chủ hồ sơ vẫn thấy lượt khám do ADMIN tạo
		authed(get("/api/visits/" + visitId), token, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.image.id").value(imageId));
		authed(delete("/api/visits/" + visitId), admin, null).andExpect(status().isNoContent());
	}

	@Test
	void oneImagePerVisitAndOneVisitPerImage() throws Exception {
		String token = register("Người Thử Ảnh");
		String patientId = myPatientId(token);
		completeProfile(token, patientId);
		String visitA = createVisit(token, patientId);
		String visitB = createVisit(token, patientId);
		String image1 = upload(token, 300, 320);
		String image2 = upload(token, 320, 300);

		// Gắn thành công; GET chi tiết và ảnh đều thấy liên kết
		authed(put("/api/visits/" + visitA + "/image"), token, "{\"imageId\":\"%s\"}".formatted(image1))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.image.id").value(image1))
			.andExpect(jsonPath("$.image.contentType").value("image/png"))
			.andExpect(jsonPath("$.image.sizeBytes").isNumber());
		authed(get("/api/visits/" + visitA), token, null).andExpect(jsonPath("$.image.id").value(image1));
		authed(get("/api/images/" + image1), token, null).andExpect(jsonPath("$.visitId").value(visitA));
		authed(get("/api/patients/" + patientId + "/visits"), token, null)
			.andExpect(jsonPath("$.content[?(@.id == '%s')].image.id".formatted(visitA)).value(image1));

		// Gắn lại đúng ảnh đó: không lỗi
		authed(put("/api/visits/" + visitA + "/image"), token, "{\"imageId\":\"%s\"}".formatted(image1))
			.andExpect(status().isOk());

		// Ảnh thứ hai vào cùng lượt khám → 409
		authed(put("/api/visits/" + visitA + "/image"), token, "{\"imageId\":\"%s\"}".formatted(image2))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VISIT_ALREADY_HAS_IMAGE"));

		// Cùng một ảnh vào lượt khám khác → 409
		authed(put("/api/visits/" + visitB + "/image"), token, "{\"imageId\":\"%s\"}".formatted(image1))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IMAGE_ALREADY_ATTACHED"));

		// Ảnh của người khác → 404; thiếu imageId → 400
		String other = register("Người Lạ Ảnh");
		String foreign = upload(other, 330, 300);
		authed(put("/api/visits/" + visitB + "/image"), token, "{\"imageId\":\"%s\"}".formatted(foreign))
			.andExpect(status().isNotFound());
		authed(put("/api/visits/" + visitB + "/image"), token, "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.imageId").exists());

		// Gỡ ảnh: ảnh còn, gắn được sang lượt khám khác
		authed(delete("/api/visits/" + visitA + "/image"), token, null).andExpect(status().isNoContent());
		authed(delete("/api/visits/" + visitA + "/image"), token, null).andExpect(status().isNoContent());
		authed(get("/api/visits/" + visitA), token, null).andExpect(jsonPath("$.image").doesNotExist());
		authed(put("/api/visits/" + visitB + "/image"), token, "{\"imageId\":\"%s\"}".formatted(image1))
			.andExpect(status().isOk());

		// Xoá lượt khám → ảnh được gỡ ra, không bị xoá
		authed(delete("/api/visits/" + visitB), token, null).andExpect(status().isNoContent());
		authed(get("/api/images/" + image1), token, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.visitId").doesNotExist());
		assertThat(imageRepository.findByIdAndDeletedAtIsNull(UUID.fromString(image1))).get()
			.satisfies(i -> assertThat(i.getVisitId()).isNull());

		// Xoá ảnh đang gắn → được, liên kết gỡ cùng lúc
		authed(put("/api/visits/" + visitA + "/image"), token, "{\"imageId\":\"%s\"}".formatted(image2))
			.andExpect(status().isOk());
		authed(delete("/api/images/" + image2), token, null).andExpect(status().isNoContent());
		authed(get("/api/visits/" + visitA), token, null).andExpect(jsonPath("$.image").doesNotExist());
		assertThat(imageRepository.findById(UUID.fromString(image2))).get()
			.satisfies(i -> assertThat(i.getVisitId()).isNull());
	}

	@Test
	void submissionWithImageAndSymptomsIsStoredWithSubmitTime() throws Exception {
		String token = register("Người Thử Gửi");
		String patientId = myPatientId(token);
		String imageId = upload(token, 300, 300);

		// Một lần gửi: ảnh + triệu chứng; createdAt là thời điểm gửi, tự đặt ngày khám là hôm nay
		String body = authed(post("/api/patients/" + patientId + "/visits"), token,
				"{\"imageId\":\"%s\",\"extra\":{\"ho\":true},\"pulse\":80}".formatted(imageId))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.image.id").value(imageId))
			.andExpect(jsonPath("$.extra.ho").value(true))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andReturn().getResponse().getContentAsString();
		String visitId = JsonPath.read(body, "$.id");
		authed(get("/api/images/" + imageId), token, null).andExpect(jsonPath("$.visitId").value(visitId));

		// Chỉ gửi ảnh
		String imageOnly = upload(token, 310, 300);
		authed(post("/api/patients/" + patientId + "/visits"), token, "{\"imageId\":\"%s\"}".formatted(imageOnly))
			.andExpect(status().isCreated());

		// Ảnh đã dùng, ảnh người khác → lỗi và không tạo lượt khám
		authed(post("/api/patients/" + patientId + "/visits"), token, "{\"imageId\":\"%s\"}".formatted(imageId))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMAGE_ALREADY_ATTACHED"));
		String foreign = upload(register("Người Lạ Gửi"), 320, 300);
		authed(post("/api/patients/" + patientId + "/visits"), token, "{\"imageId\":\"%s\"}".formatted(foreign))
			.andExpect(status().isNotFound());

		// Lịch sử = danh sách lượt khám, mới nhất trước
		authed(get("/api/patients/" + patientId + "/visits"), token, null)
			.andExpect(jsonPath("$.totalElements").value(2));
	}

	// ---------- Tiện ích ----------

	private String createVisit(String token, String patientId) throws Exception {
		String body = authed(post("/api/patients/" + patientId + "/visits"), token, VISIT)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private void completeProfile(String token, String patientId) throws Exception {
		authed(put("/api/patients/" + patientId), token, """
				{"fullName":"Người Thử Khám","birthDate":"1980-06-15","gender":"MALE","occupation":"Thợ giả định"}
				""").andExpect(status().isOk());
	}

	private String myPatientId(String token) throws Exception {
		return JsonPath.read(authed(get("/api/patients/me"), token, null).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString(), "$.id");
	}

	private String upload(String token, int width, int height) throws Exception {
		String body = mvc.perform(multipart("/api/images")
				.file(new MockMultipartFile("file", "xray.png", "image/png", png(width, height)))
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String register(String fullName) throws Exception {
		String body = authed(post("/api/auth/register"), null, """
				{"fullName":"%s","email":"%s","password":"matkhau123"}
				""".formatted(fullName, uniqueEmail())).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString();
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
		String body = authed(post("/api/auth/login"), null,
				"{\"identifier\":\"%s\",\"password\":\"admin12345\"}".formatted(email))
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

	private static byte[] png(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private static Path temporaryImageDirectory() {
		try {
			return Files.createTempDirectory("silicocare-visit-test-");
		}
		catch (Exception ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	@AfterAll
	static void removeTemporaryImages() throws Exception {
		if (!Files.exists(IMAGE_DIR)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(IMAGE_DIR)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

}
