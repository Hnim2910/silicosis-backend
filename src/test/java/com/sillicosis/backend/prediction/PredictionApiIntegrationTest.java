package com.sillicosis.backend.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.sillicosis.backend.TestcontainersConfiguration;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Lượt chẩn đoán qua một server HTTP giả (JDK HttpServer) thay cho service FastAPI; Postgres thật
 * (Testcontainers). Dữ liệu đều là dữ liệu giả.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PredictionApiIntegrationTest {

	private static final Path IMAGE_DIR = temporaryImageDirectory();

	/** Body giả cùng dạng PGResult của POST /predict/pg (predict/app/schemas.py), số liệu tự đặt. */
	private static final String OK_IMAGE = """
			{"nhan":"ketqua","xac_suat":0.55,"xac_suat_theo_fold":[0.5,0.6,0.55,0.52,0.58],"co_anh":true,
			 "tinh":"Thai Nguyen","chu_y_vung":{"RU":0.2,"RM":0.15,"RL":0.1,"LU":0.25,"LM":0.18,"LL":0.12},
			 "qa_anh":{},"canh_bao":["Cảnh báo thử"],"canh_bao_dau_vao":[],"experimental":true,
			 "disclaimer":"x","model_name":"PG ketqua","model_version":"abc123def456"}
			""";

	private static final String OK_CLINICAL = """
			{"nhan":"ketqua","xac_suat":0.03,"xac_suat_theo_fold":[0.02,0.04,0.03,0.03,0.03],"co_anh":false,
			 "tinh":"Thai Nguyen","chu_y_vung":null,"qa_anh":null,"canh_bao":[],"canh_bao_dau_vao":[],
			 "experimental":true,"disclaimer":"x","model_name":"PG ketqua","model_version":"abc123def456"}
			""";

	private static final String SCHEMA = """
			{"required":["tinh"],"tinh":["Binh Dinh","Dong Nai","Hai Duong","Phu Yen","Thai Nguyen"],
			 "kind":{"ho":"muc","fvctt":"so","cviec":"chu","gioitinh":"muc","namsinh":"so"},
			 "levels":{"ho":["Co","Khong"],"gioitinh":["Nam","Nu"]},
			 "derived":["bmi","fev1_fvc","cviec_lv","pxuong_lv","cviec1_lv","cviec2_lv"]}
			""";

	private static final String LAM_SANG = """
			{"tinh":"Thai Nguyen","ho":"Co","fvctt":"3,2","cviec":"cong nhan"}""";

	/** Hành vi của server giả: ok, slow, error500, reject400, reject422, garbage, noversion. */
	private static final AtomicReference<String> MODE = new AtomicReference<>("ok");

	private static final AtomicInteger CALLS = new AtomicInteger();

	/** Yêu cầu đúng dạng: multipart có "lam_sang", kèm "file" (filename, Content-Type ảnh) khi có ảnh. */
	private static final AtomicBoolean WELL_FORMED = new AtomicBoolean();

	private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();

	private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

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
	void imageAndClinicalInOneRequestWithProfileCopiedIn() throws Exception {
		Account me = register("Người Thử Dự Đoán");
		authed(put("/api/users/me"), me.token(), """
				{"fullName":"Người Thử Dự Đoán","email":"%s","birthDate":"1975-04-02","gender":"MALE",
				 "tieuSu":{"cviec":"Thợ nghiền đá","pxuong":"Phân xưởng thử","tuoinghe":"12,5","tsnoi":""}}
				""".formatted(me.email()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("BN-")))
			.andExpect(jsonPath("$.tieuSu.pxuong").value("Phân xưởng thử"))
			.andExpect(jsonPath("$.tieuSu.tuoinghe").value(12.5))
			.andExpect(jsonPath("$.tieuSu.tsnoi").doesNotExist());
		authed(put("/api/users/me"), me.token(), """
				{"fullName":"Người Thử","email":"%s","tieuSu":{"hutthuoc":"Co"}}
				""".formatted(me.email()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['tieuSu.hutthuoc']").exists());

		int before = CALLS.get();
		String body = submit(me.token(), "{\"examDate\":\"2026-11-01\",\"lamSang\":%s}".formatted(LAM_SANG),
				png(300, 300))
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.examDate").value("2026-11-01"))
			.andExpect(jsonPath("$.lamSang.ho").value("Co"))
			.andExpect(jsonPath("$.lamSang.gioitinh").value("Nam"))
			.andExpect(jsonPath("$.lamSang.namsinh").value(1975))
			.andExpect(jsonPath("$.image.contentType").value("image/png"))
			.andExpect(jsonPath("$.image.width").value(300))
			.andExpect(jsonPath("$.status").value("SUCCESS"))
			.andExpect(jsonPath("$.lamSang.tinh").value("Thai Nguyen"))
			.andExpect(jsonPath("$.coAnh").value(true))
			.andExpect(jsonPath("$.xacSuat").value(0.55))
			.andExpect(jsonPath("$.xacSuatTheoFold.length()").value(5))
			.andExpect(jsonPath("$.chuYVung.LU").value(0.25))
			.andExpect(jsonPath("$.canhBao[0]").value("Cảnh báo thử"))
			// PG không có ngưỡng, không có kết luận dương/âm
			.andExpect(jsonPath("$.pLamSang").doesNotExist())
			.andExpect(jsonPath("$.ketLuanCuoi").doesNotExist())
			.andExpect(jsonPath("$.nguongGhep").doesNotExist())
			.andExpect(jsonPath("$.modelName").value("PG ketqua"))
			.andExpect(jsonPath("$.modelVersion").value("abc123def456"))
			.andExpect(jsonPath("$.experimental").value(true))
			.andExpect(jsonPath("$.disclaimer").isNotEmpty())
			.andExpect(jsonPath("$.user.fullName").value("Người Thử Dự Đoán"))
			.andExpect(jsonPath("$.user.code").value(org.hamcrest.Matchers.startsWith("BN-")))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");
		assertThat(CALLS.get()).isEqualTo(before + 1);
		assertThat(LAST_PATH.get()).isEqualTo("/predict/pg");
		assertThat(WELL_FORMED).isTrue();
		assertThat(LAST_BODY.get()).contains("\"gioitinh\":\"Nam\"").contains("\"namsinh\":1975")
			.contains("\"tinh\":\"Thai Nguyen\"");

		authed(get("/api/predictions/" + id), me.token(), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id));
		authed(get("/api/predictions"), me.token(), null)
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].id").value(id));
		authed(get("/api/predictions/" + id + "/image"), me.token(), null)
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"))
			.andExpect(header().string("Cache-Control", "private, no-store"));

		// Lượt đã thành công không thử lại được
		authed(post("/api/predictions/" + id + "/retry"), me.token(), null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PREDICTION_NOT_FAILED"));

		// Người khác → 404 ở mọi thao tác
		String stranger = register("Người Lạ Dự Đoán").token();
		authed(get("/api/predictions/" + id), stranger, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PREDICTION_NOT_FOUND"));
		authed(get("/api/predictions/" + id + "/image"), stranger, null).andExpect(status().isNotFound());
		authed(post("/api/predictions/" + id + "/retry"), stranger, null).andExpect(status().isNotFound());
		authed(delete("/api/predictions/" + id), stranger, null).andExpect(status().isNotFound());
		authed(get("/api/predictions"), stranger, null).andExpect(jsonPath("$.totalElements").value(0));

		// Chủ lượt xoá mềm → biến mất khỏi danh sách
		authed(delete("/api/predictions/" + id), me.token(), null).andExpect(status().isNoContent());
		authed(get("/api/predictions/" + id), me.token(), null).andExpect(status().isNotFound());
		authed(get("/api/predictions"), me.token(), null).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void clinicalOnlySendsNoFilePartAndHasNoImage() throws Exception {
		String token = register("Người Thử Lâm Sàng").token();
		String id = JsonPath.read(submit(token, "{\"lamSang\":%s}".formatted(LAM_SANG), null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCESS"))
			.andExpect(jsonPath("$.coAnh").value(false))
			.andExpect(jsonPath("$.xacSuat").value(0.03))
			.andExpect(jsonPath("$.chuYVung").doesNotExist())
			.andExpect(jsonPath("$.canhBao").isEmpty())
			.andExpect(jsonPath("$.image").doesNotExist())
			.andExpect(jsonPath("$.examDate").isNotEmpty())
			// Hồ sơ chưa có giới tính/ngày sinh → không tự thêm
			.andExpect(jsonPath("$.lamSang.gioitinh").doesNotExist())
			.andReturn().getResponse().getContentAsString(), "$.id");
		assertThat(LAST_PATH.get()).isEqualTo("/predict/pg");
		assertThat(WELL_FORMED).isTrue();
		assertThat(LAST_BODY.get()).doesNotContain("name=\"file\"");
		authed(get("/api/predictions/" + id + "/image"), token, null).andExpect(status().isNotFound());
	}

	@Test
	void imageWithOnlyTinhIsAccepted() throws Exception {
		String token = register("Người Thử Chỉ Ảnh").token();
		submit(token, "{\"lamSang\":{\"tinh\":\"Hai Duong\"}}", png(300, 300))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUCCESS"))
			.andExpect(jsonPath("$.coAnh").value(true))
			.andExpect(jsonPath("$.lamSang.tinh").value("Hai Duong"));
		assertThat(LAST_PATH.get()).isEqualTo("/predict/pg");
		assertThat(WELL_FORMED).isTrue();
	}

	@Test
	void tinhIsRequiredAndMustBeOneOfFive() throws Exception {
		String token = register("Người Thử Tỉnh").token();
		int before = CALLS.get();
		// Ảnh mà không gửi lâm sàng → thiếu tỉnh
		submit(token, null, png(300, 300))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.fieldErrors['lamSang.tinh']").exists());
		submit(token, "{\"lamSang\":{\"ho\":\"Co\"}}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['lamSang.tinh']").exists());
		submit(token, "{\"lamSang\":{\"tinh\":\"Ha Noi\",\"ho\":\"Co\"}}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['lamSang.tinh']").exists());
		submit(token, "{\"lamSang\":{\"tinh\":\"Thái Nguyên\",\"ho\":\"Co\"}}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['lamSang.tinh']").exists());
		assertThat(CALLS.get()).isEqualTo(before);
	}

	@Test
	void invalidInputIsRejectedBeforeCallingTheModel() throws Exception {
		String token = register("Người Thử Mã Sai").token();
		int before = CALLS.get();

		submit(token, "{\"lamSang\":{\"ho\":\"Có\",\"fvctt\":\"abc\",\"tinh\":\"x\"}}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['lamSang.ho']").exists())
			.andExpect(jsonPath("$.fieldErrors['lamSang.fvctt']").exists())
			.andExpect(jsonPath("$.fieldErrors['lamSang.tinh']").exists());
		// Trường cũ của New Way (fvc) và trường mô hình tự tính (bmi) → bị từ chối
		submit(token, "{\"lamSang\":{\"tinh\":\"Phu Yen\",\"fvc\":\"3\",\"bmi\":22}}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['lamSang.fvc']").value("Trường không có trong mô hình."))
			.andExpect(jsonPath("$.fieldErrors['lamSang.bmi']").exists())
			.andExpect(jsonPath("$.fieldErrors['lamSang.tinh']").doesNotExist());
		submit(token, "{\"lamSang\":{\"tinh\":\"Phu Yen\",\"ho\":{\"x\":1}}}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['lamSang.ho']").exists());
		submit(token, "{\"lamSang\":[1,2]}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors['lamSang']").exists());
		submit(token, "{\"examDate\":\"2999-01-01\",\"lamSang\":%s}".formatted(LAM_SANG), null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.examDate").exists());
		// Không có ảnh, lâm sàng chỉ có tỉnh
		submit(token, "{\"lamSang\":{\"tinh\":\"Phu Yen\",\"ho\":\"\"}}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("EMPTY_SUBMISSION"));
		// Tệp không phải ảnh
		submit(token, "{\"lamSang\":{\"tinh\":\"Phu Yen\"}}", "not an image".getBytes(StandardCharsets.UTF_8))
			.andExpect(status().is4xxClientError());

		assertThat(CALLS.get()).isEqualTo(before);
		authed(get("/api/predictions"), token, null).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void serviceErrorsAreStoredAsFailedAndRetryUpdatesTheSameRow() throws Exception {
		String token = register("Người Thử Lỗi").token();

		MODE.set("error500");
		String id = JsonPath.read(submit(token, "{\"lamSang\":%s}".formatted(LAM_SANG), png(300, 300))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.errorCode").value("PREDICT_UNAVAILABLE"))
			.andExpect(jsonPath("$.xacSuat").doesNotExist())
			.andExpect(jsonPath("$.canhBao").doesNotExist())
			.andExpect(jsonPath("$.image.contentType").value("image/png"))
			.andReturn().getResponse().getContentAsString(), "$.id");

		assertRetryFails(token, id, "slow", "PREDICT_TIMEOUT");
		assertRetryFails(token, id, "garbage", "PREDICT_BAD_RESPONSE");
		assertRetryFails(token, id, "noversion", "PREDICT_BAD_RESPONSE");
		assertRetryFails(token, id, "reject400", "PREDICT_IMAGE_REJECTED");
		assertRetryFails(token, id, "reject422", "PREDICT_INVALID_INPUT");

		// Service chạy lại bình thường → thành công trên chính dòng đó (ảnh đọc lại từ đĩa)
		MODE.set("ok");
		authed(post("/api/predictions/" + id + "/retry"), token, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.status").value("SUCCESS"))
			.andExpect(jsonPath("$.errorCode").doesNotExist())
			.andExpect(jsonPath("$.xacSuat").value(0.55))
			.andExpect(jsonPath("$.coAnh").value(true));
		assertThat(LAST_PATH.get()).isEqualTo("/predict/pg");
		assertThat(WELL_FORMED).isTrue();
		authed(get("/api/predictions"), token, null).andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void adminListsAllPredictionsWithOwnerSummary() throws Exception {
		String token = register("Người Thử Danh Sách").token();
		String id = JsonPath.read(submit(token, "{\"lamSang\":%s}".formatted(LAM_SANG), null)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		String admin = adminToken();
		String list = authed(get("/api/predictions/all?size=100"), admin, null)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String base = "$.content[?(@.id == '%s')]".formatted(id);
		assertThat(JsonPath.<List<String>>read(list, base + ".user.fullName")).containsExactly("Người Thử Danh Sách");
		assertThat(JsonPath.<List<String>>read(list, base + ".user.code")).singleElement()
			.asString().startsWith("BN-");
		authed(get("/api/predictions/" + id), admin, null).andExpect(status().isOk());

		authed(get("/api/predictions/all"), token, null).andExpect(status().isForbidden());
	}

	// ---------- Tiện ích ----------

	private record Account(String token, String email) {
	}

	private void assertRetryFails(String token, String id, String mode, String code) throws Exception {
		MODE.set(mode);
		authed(post("/api/predictions/" + id + "/retry"), token, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.errorCode").value(code));
	}

	private ResultActions submit(String token, String data, byte[] image) throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipart("/api/predictions");
		if (data != null) {
			request.file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
					data.getBytes(StandardCharsets.UTF_8)));
		}
		if (image != null) {
			request.file(new MockMultipartFile("file", "xray.png", "image/png", image));
		}
		request.header("Authorization", "Bearer " + token);
		return mvc.perform(request);
	}

	private static HttpServer startServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/predict/pg", PredictionApiIntegrationTest::handle);
			server.createContext("/schema", ex -> respond(ex, 200, SCHEMA, true));
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	private static void handle(HttpExchange exchange) throws IOException {
		CALLS.incrementAndGet();
		String path = exchange.getRequestURI().getPath();
		LAST_PATH.set(path);
		String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		LAST_BODY.set(request);
		String type = exchange.getRequestHeaders().getFirst("Content-Type");
		boolean image = request.contains("name=\"file\"");
		// uvicorn từ chối yêu cầu có "Upgrade: h2c" (400), server JDK thì không: kiểm tra ở đây.
		WELL_FORMED.set(exchange.getRequestHeaders().getFirst("Upgrade") == null && type != null
				&& "POST".equals(exchange.getRequestMethod()) && type.startsWith("multipart/form-data")
				&& request.contains("name=\"lam_sang\"")
				&& (!image || request.contains("name=\"file\"; filename=\"image.png\"")
						&& request.contains("Content-Type: image/png")));
		String ok = image ? OK_IMAGE : OK_CLINICAL;
		switch (MODE.get()) {
			case "slow" -> {
				try {
					Thread.sleep(3000);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				respond(exchange, 200, ok, true);
			}
			case "error500" -> respond(exchange, 503, "{\"code\":\"MODEL_NOT_LOADED\",\"message\":\"x\"}", true);
			case "reject400" -> respond(exchange, 400, "{\"code\":\"INVALID_IMAGE\",\"message\":\"x\"}", true);
			case "reject422" -> respond(exchange, 422, "{\"code\":\"INVALID_INPUT\",\"message\":\"x\"}", true);
			case "garbage" -> respond(exchange, 200, "{\"xac_suat\":1.7,\"co_anh\":true}", true);
			case "noversion" -> respond(exchange, 200, ok, false);
			default -> respond(exchange, 200, ok, true);
		}
	}

	private static void respond(HttpExchange exchange, int status, String json, boolean version) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		if (version) {
			exchange.getResponseHeaders().set("X-Model-Version", "abc123def456");
		}
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

	private Account register(String fullName) throws Exception {
		String email = uniqueEmail();
		String body = authed(post("/api/auth/register"), null, """
				{"fullName":"%s","email":"%s","password":"MatKhau@123"}
				""".formatted(fullName, email)).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString();
		return new Account(JsonPath.read(body, "$.accessToken"), email);
	}

	private String adminToken() throws Exception {
		String email = uniqueEmail();
		User admin = new User();
		admin.setEmail(email);
		admin.setFullName("Quản Trị Thử");
		admin.setRole(Role.ADMIN);
		admin.setPasswordHash(passwordEncoder.encode("Admin@12345"));
		userRepository.saveAndFlush(admin);
		String body = authed(post("/api/auth/login"), null,
				"{\"identifier\":\"%s\",\"password\":\"Admin@12345\"}".formatted(email))
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
