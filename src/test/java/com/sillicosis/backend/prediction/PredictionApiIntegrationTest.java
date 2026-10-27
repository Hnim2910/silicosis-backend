package com.sillicosis.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Dự đoán qua một server HTTP giả (JDK HttpServer) thay cho service FastAPI; Postgres thật (Testcontainers). Dữ liệu
 * đều là dữ liệu giả.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PredictionApiIntegrationTest {

	private static final Path IMAGE_DIR = temporaryImageDirectory();

	private static final String OK_BODY = """
			{"probability":0.73,"label":"Co","threshold":0.403,"model_version":"abc123def456",
			 "model_name":"Model giả","experimental":true,"disclaimer":"x"}
			""";

	/** Hành vi của server giả: ok, slow, error500, reject400, garbage. */
	private static final AtomicReference<String> MODE = new AtomicReference<>("ok");

	private static final AtomicInteger CALLS = new AtomicInteger();

	/** Phần multipart "file" có filename và Content-Type ảnh. */
	private static final AtomicBoolean WELL_FORMED = new AtomicBoolean();

	private static final HttpServer SERVER = startServer();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.images-dir", () -> IMAGE_DIR.toString());
		registry.add("app.predict.enabled", () -> "true");
		registry.add("app.predict.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
		registry.add("app.predict.timeout", () -> "1s");
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void reset() {
		MODE.set("ok");
		WELL_FORMED.set(false);
	}

	@Test
	void predictsOnSubmitRerunsAndFollowsAttachedImage() throws Exception {
		String token = register("Người Thử Dự Đoán");
		String patientId = myPatientId(token);
		String imageId = upload(token, 300, 300);

		// Gửi lượt khám kèm ảnh → dự đoán ngay, kết quả trong latestPrediction
		int before = CALLS.get();
		String body = authed(post("/api/patients/" + patientId + "/visits"), token,
				"{\"imageId\":\"%s\",\"pulse\":80}".formatted(imageId))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.latestPrediction.status").value("SUCCESS"))
			.andExpect(jsonPath("$.latestPrediction.probability").value(0.73))
			.andExpect(jsonPath("$.latestPrediction.label").value("Co"))
			.andExpect(jsonPath("$.latestPrediction.threshold").value(0.403))
			.andExpect(jsonPath("$.latestPrediction.modelVersion").value("abc123def456"))
			.andExpect(jsonPath("$.latestPrediction.imageId").value(imageId))
			.andExpect(jsonPath("$.latestPrediction.experimental").value(true))
			.andExpect(jsonPath("$.latestPrediction.disclaimer").isNotEmpty())
			.andExpect(jsonPath("$.patient.id").value(patientId))
			.andReturn().getResponse().getContentAsString();
		String visitId = JsonPath.read(body, "$.id");
		String first = JsonPath.read(body, "$.latestPrediction.id");
		assertThat(CALLS.get()).isEqualTo(before + 1);
		assertThat(WELL_FORMED).isTrue();

		authed(get("/api/visits/" + visitId), token, null).andExpect(jsonPath("$.latestPrediction.id").value(first));
		authed(get("/api/patients/" + patientId + "/visits"), token, null)
			.andExpect(jsonPath("$.content[0].latestPrediction.id").value(first));

		// Gắn lại đúng ảnh đó: không gọi lại service
		authed(put("/api/visits/" + visitId + "/image"), token, "{\"imageId\":\"%s\"}".formatted(imageId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latestPrediction.id").value(first));
		assertThat(CALLS.get()).isEqualTo(before + 1);

		// Dự đoán lại → bản ghi mới trở thành mới nhất
		String rerun = JsonPath.read(authed(post("/api/visits/" + visitId + "/predictions"), token, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCESS"))
			.andReturn().getResponse().getContentAsString(), "$.id");
		assertThat(rerun).isNotEqualTo(first);
		authed(get("/api/visits/" + visitId), token, null).andExpect(jsonPath("$.latestPrediction.id").value(rerun));

		// Người khác → 404
		authed(post("/api/visits/" + visitId + "/predictions"), register("Người Lạ Dự Đoán"), null)
			.andExpect(status().isNotFound());

		// Gỡ ảnh → không còn kết quả; dự đoán lại → 400
		authed(delete("/api/visits/" + visitId + "/image"), token, null).andExpect(status().isNoContent());
		authed(get("/api/visits/" + visitId), token, null).andExpect(jsonPath("$.latestPrediction").doesNotExist());
		authed(post("/api/visits/" + visitId + "/predictions"), token, null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VISIT_HAS_NO_IMAGE"));

		// Gắn ảnh mới qua PUT → dự đoán cho ảnh mới
		String other = upload(token, 310, 300);
		authed(put("/api/visits/" + visitId + "/image"), token, "{\"imageId\":\"%s\"}".formatted(other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.latestPrediction.status").value("SUCCESS"))
			.andExpect(jsonPath("$.latestPrediction.imageId").value(other));

		// Lượt khám chỉ có chỉ số lâm sàng: không gọi service
		int beforeClinical = CALLS.get();
		authed(post("/api/patients/" + patientId + "/visits"), token, "{\"pulse\":70}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.latestPrediction").doesNotExist());
		assertThat(CALLS.get()).isEqualTo(beforeClinical);
	}

	@Test
	void serviceErrorsAreStoredAsFailedAndStillReturn201() throws Exception {
		String token = register("Người Thử Lỗi");
		String patientId = myPatientId(token);

		MODE.set("error500");
		String body = authed(post("/api/patients/" + patientId + "/visits"), token,
				"{\"imageId\":\"%s\"}".formatted(upload(token, 300, 300)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.latestPrediction.status").value("FAILED"))
			.andExpect(jsonPath("$.latestPrediction.errorCode").value("PREDICT_UNAVAILABLE"))
			.andExpect(jsonPath("$.latestPrediction.probability").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String visitId = JsonPath.read(body, "$.id");

		assertRerunFails(token, visitId, "slow", "PREDICT_TIMEOUT");
		assertRerunFails(token, visitId, "garbage", "PREDICT_BAD_RESPONSE");
		assertRerunFails(token, visitId, "reject400", "PREDICT_IMAGE_REJECTED");

		// Service chạy lại bình thường → thành công, trở thành kết quả mới nhất
		MODE.set("ok");
		authed(post("/api/visits/" + visitId + "/predictions"), token, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCESS"));
		authed(get("/api/visits/" + visitId), token, null)
			.andExpect(jsonPath("$.latestPrediction.status").value("SUCCESS"));
	}

	@Test
	void adminListsAllVisitsWithPatientSummary() throws Exception {
		String token = register("Người Thử Danh Sách");
		String patientId = myPatientId(token);
		String visitId = JsonPath.read(authed(post("/api/patients/" + patientId + "/visits"), token,
				"{\"imageId\":\"%s\"}".formatted(upload(token, 300, 300)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		String admin = adminToken();
		String list = authed(get("/api/visits?size=100"), admin, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").isNumber())
			.andReturn().getResponse().getContentAsString();
		String base = "$.content[?(@.id == '%s')]".formatted(visitId);
		assertThat(JsonPath.<java.util.List<String>>read(list, base + ".patient.fullName"))
			.containsExactly("Người Thử Danh Sách");
		assertThat(JsonPath.<java.util.List<String>>read(list, base + ".patient.code")).singleElement()
			.asString().startsWith("BN-");
		assertThat(JsonPath.<java.util.List<String>>read(list, base + ".latestPrediction.status"))
			.containsExactly("SUCCESS");

		authed(get("/api/visits"), token, null).andExpect(status().isForbidden());
	}

	// ---------- Tiện ích ----------

	private void assertRerunFails(String token, String visitId, String mode, String code) throws Exception {
		MODE.set(mode);
		authed(post("/api/visits/" + visitId + "/predictions"), token, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.errorCode").value(code));
	}

	private static HttpServer startServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/predict", PredictionApiIntegrationTest::handle);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	private static void handle(HttpExchange exchange) throws IOException {
		CALLS.incrementAndGet();
		String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
		// uvicorn từ chối yêu cầu có "Upgrade: h2c" (400), server JDK thì không: kiểm tra ở đây.
		WELL_FORMED.set(exchange.getRequestHeaders().getFirst("Upgrade") == null
				&& exchange.getRequestHeaders().getFirst("Content-Type").startsWith("multipart/form-data")
				&& request.contains("name=\"file\"; filename=\"image.png\"") && request.contains("Content-Type: image/png"));
		switch (MODE.get()) {
			case "slow" -> {
				try {
					Thread.sleep(3000);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				respond(exchange, 200, OK_BODY);
			}
			case "error500" -> respond(exchange, 503, "{\"code\":\"MODEL_NOT_LOADED\",\"message\":\"x\"}");
			case "reject400" -> respond(exchange, 400, "{\"code\":\"INVALID_IMAGE\",\"message\":\"x\"}");
			case "garbage" -> respond(exchange, 200, "{\"probability\":1.7,\"label\":\"maybe\"}");
			default -> respond(exchange, 200, OK_BODY);
		}
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		try {
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		catch (IOException ignored) {
			// Client đã ngắt vì hết thời gian chờ.
		}
		finally {
			exchange.close();
		}
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
			return Files.createTempDirectory("silicocare-prediction-test-");
		}
		catch (Exception ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	@AfterAll
	static void cleanUp() throws Exception {
		SERVER.stop(0);
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
