package com.sillicosis.backend.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.sillicosis.backend.TestcontainersConfiguration;
import com.sillicosis.backend.auth.TokenService;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ImageApiIntegrationTest {

	private static final Path IMAGE_DIR = temporaryImageDirectory();

	@DynamicPropertySource
	static void imageStorageProperties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.images-dir", () -> IMAGE_DIR.toString());
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ImageRepository imageRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

	@Test
	void uploadListDownloadDuplicateAccessControlAndDelete() throws Exception {
		String ownerToken = registerAndGetToken();
		String otherToken = registerAndGetToken();
		byte[] png = png(300, 320);
		MockMultipartFile upload = new MockMultipartFile("file", "patient-name.png", "text/plain", png);

		String uploaded = mvc.perform(multipart("/api/images").file(upload)
				.header("Authorization", "Bearer " + ownerToken))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.contentType").value("image/png"))
			.andExpect(jsonPath("$.width").value(300))
			.andExpect(jsonPath("$.height").value(320))
			.andExpect(jsonPath("$.sha256").doesNotExist())
			.andExpect(jsonPath("$.storageKey").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(uploaded, "$.id");

		mvc.perform(get("/api/images").header("Authorization", "Bearer " + ownerToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].id").value(id));
		mvc.perform(get("/api/images/" + id).header("Authorization", "Bearer " + ownerToken))
			.andExpect(status().isOk());
		mvc.perform(get("/api/images/" + id + "/content").header("Authorization", "Bearer " + ownerToken))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(content().bytes(png));

		mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "again.png", "image/png", png))
				.header("Authorization", "Bearer " + ownerToken))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IMAGE_DUPLICATE"))
			.andExpect(jsonPath("$.details.imageId").value(id));

		mvc.perform(get("/api/images/" + id).header("Authorization", "Bearer " + otherToken))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/images/" + id + "/content").header("Authorization", "Bearer " + otherToken))
			.andExpect(status().isNotFound());

		String adminToken = createAdminToken();
		mvc.perform(get("/api/images/" + id).header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isOk());
		mvc.perform(get("/api/images/" + id + "/content").header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isOk());

		Image saved = imageRepository.findById(UUID.fromString(id)).orElseThrow();
		Path storedFile = IMAGE_DIR.resolve(saved.getStorageKey());
		assertThat(Files.exists(storedFile)).isTrue();
		mvc.perform(delete("/api/images/" + id).header("Authorization", "Bearer " + ownerToken))
			.andExpect(status().isNoContent());
		assertThat(Files.exists(storedFile)).isFalse();
		mvc.perform(get("/api/images/" + id).header("Authorization", "Bearer " + ownerToken))
			.andExpect(status().isNotFound());
	}

	private String registerAndGetToken() throws Exception {
		String email = "image-" + UUID.randomUUID() + "@example.com";
		String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Ảnh thử\",\"email\":\"" + email
						+ "\",\"password\":\"matkhau123\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.accessToken");
	}

	private String createAdminToken() {
		User admin = new User();
		admin.setEmail("admin-image-" + UUID.randomUUID() + "@example.com");
		admin.setFullName("Admin ảnh thử");
		admin.setPasswordHash(passwordEncoder.encode("admin12345"));
		admin.setRole(Role.ADMIN);
		admin = userRepository.saveAndFlush(admin);
		return tokenService.issueTokens(admin).accessToken();
	}

	private static byte[] png(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private static Path temporaryImageDirectory() {
		try {
			return Files.createTempDirectory("silicocare-image-test-");
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
		try (var paths = Files.walk(IMAGE_DIR)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

}
