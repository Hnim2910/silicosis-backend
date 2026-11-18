package com.sillicosis.backend.prediction;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.InvalidFieldsException;
import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.image.ImageProcessor;
import com.sillicosis.backend.image.ImageProcessor.ProcessedImage;
import com.sillicosis.backend.image.ImageStorage;
import com.sillicosis.backend.prediction.dto.PredictionRequest;
import com.sillicosis.backend.prediction.dto.PredictionResponse;
import com.sillicosis.backend.prediction.dto.PredictionResponse.Owner;
import com.sillicosis.backend.user.Gender;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lượt chẩn đoán: mỗi lần gửi = một dòng {@code predictions} (đầu vào + ảnh + kết quả), không sửa được. Lời gọi
 * service dự đoán (có thể mất vài chục giây) nằm ngoài transaction; dòng chỉ được ghi sau khi có kết quả. Lỗi của
 * service vẫn tạo dòng (status FAILED + errorCode) và chỉ dòng FAILED mới được thử lại. Quyền: chủ lượt chẩn đoán
 * hoặc ADMIN; người khác nhận 404 như khi không tồn tại.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

	/** Số cột tối đa của {@code lamSang} (model hiện có khoảng 60 cột đầu vào). */
	static final int MAX_FIELDS = 200;

	private final PredictionRepository predictionRepository;

	private final UserRepository userRepository;

	private final ClinicalValidator clinicalValidator;

	private final ImageProcessor imageProcessor;

	private final ImageStorage imageStorage;

	private final PredictClient predictClient;

	private final JsonMapper jsonMapper;

	private final AppProperties props;

	private final Clock clock;

	/**
	 * Tạo lượt chẩn đoán cho chính người gửi: kiểm tra đầu vào, làm sạch ảnh, gọi model rồi lưu. Giới tính và năm
	 * sinh trong hồ sơ được chép vào {@code lamSang} (ghi đè giá trị client gửi nếu hồ sơ đã có).
	 */
	public PredictionResponse create(UUID actorId, PredictionRequest req, MultipartFile file) {
		User user = activeUser(actorId);
		PredictionRequest request = req == null ? new PredictionRequest(null, null) : req;
		Map<String, Object> lamSang = validate(request, user);
		ProcessedImage image = readImage(file);
		if (image == null && !hasValue(lamSang)) {
			throw ApiException.badRequest("EMPTY_SUBMISSION",
					"Vui lòng gửi ảnh X-quang hoặc ít nhất một thông tin lâm sàng.");
		}

		Prediction p = new Prediction();
		p.setUserId(user.getId());
		p.setCreatedBy(actorId);
		p.setExamDate(request.examDate() != null ? request.examDate() : today());
		p.setLamSang(withProfile(lamSang, user));
		if (image != null) {
			p.setImageContentType(image.contentType());
			p.setImageSizeBytes((long) image.bytes().length);
			p.setImageWidth(image.width());
			p.setImageHeight(image.height());
			p.setImageSha256(image.sha256());
		}
		long started = System.nanoTime();
		runModel(p, image == null ? null : image.bytes());

		String storageKey = null;
		if (image != null) {
			storageKey = UUID.randomUUID().toString();
			imageStorage.write(storageKey, image.bytes());
			p.setImageStorageKey(storageKey);
		}
		Instant now = clock.instant();
		p.setCreatedAt(now);
		p.setUpdatedAt(now);
		Prediction saved;
		try {
			saved = predictionRepository.saveAndFlush(p);
		}
		catch (RuntimeException ex) {
			if (storageKey != null) {
				imageStorage.delete(storageKey);
			}
			throw ex;
		}
		logResult(saved, started);
		return PredictionResponse.from(saved, owner(user));
	}

	/** Chạy lại model cho một lượt FAILED, ghi đè kết quả trên chính dòng đó. Lượt SUCCESS → 409. */
	public PredictionResponse retry(UUID actorId, boolean admin, UUID id) {
		activeUser(actorId);
		Prediction p = authorized(actorId, admin, id);
		if (p.getStatus() != PredictionStatus.FAILED) {
			throw ApiException.conflict("PREDICTION_NOT_FAILED", "Chỉ thử lại được lượt chẩn đoán bị lỗi.");
		}
		if (!props.predict().enabled()) {
			throw disabled();
		}
		long started = System.nanoTime();
		byte[] bytes = null;
		boolean readable = true;
		if (p.hasImage()) {
			try {
				bytes = imageStorage.read(p.getImageStorageKey());
			}
			catch (RuntimeException ex) {
				readable = false;
			}
		}
		if (readable) {
			runModel(p, bytes);
		}
		else {
			p.applyFailure(PredictException.IMAGE_UNREADABLE);
		}
		p.setUpdatedAt(clock.instant());
		Prediction saved = predictionRepository.saveAndFlush(p);
		logResult(saved, started);
		return PredictionResponse.from(saved, owner(userRepository.findById(saved.getUserId()).orElse(null)));
	}

	/** Lượt chẩn đoán của chính người gọi, mới nhất trước. */
	public PageResponse<PredictionResponse> listMine(UUID actorId, Pageable pageable) {
		User user = activeUser(actorId);
		Owner owner = owner(user);
		return PageResponse.of(predictionRepository.findByUserIdAndDeletedAtIsNull(user.getId(), pageable),
				p -> PredictionResponse.from(p, owner));
	}

	/** Mọi lượt chẩn đoán (chỉ ADMIN, kiểm tra ở controller); mỗi phần tử kèm thông tin ngắn của người được khám. */
	public PageResponse<PredictionResponse> listAll(Pageable pageable) {
		Page<Prediction> page = predictionRepository.findByDeletedAtIsNull(pageable);
		Map<UUID, Owner> owners = owners(page.getContent().stream().map(Prediction::getUserId).distinct().toList());
		return PageResponse.of(page, p -> PredictionResponse.from(p, owners.get(p.getUserId())));
	}

	public PredictionResponse get(UUID actorId, boolean admin, UUID id) {
		activeUser(actorId);
		Prediction p = authorized(actorId, admin, id);
		return PredictionResponse.from(p, owner(userRepository.findById(p.getUserId()).orElse(null)));
	}

	/** Nội dung ảnh X-quang của lượt chẩn đoán; lượt không có ảnh → 404. */
	public ImageContent image(UUID actorId, boolean admin, UUID id) {
		activeUser(actorId);
		Prediction p = authorized(actorId, admin, id);
		if (!p.hasImage()) {
			throw ApiException.notFound("Lượt chẩn đoán này không có ảnh.");
		}
		return new ImageContent(p.getImageContentType(), imageStorage.read(p.getImageStorageKey()));
	}

	/** Xoá mềm; tệp ảnh được giữ lại cùng bản ghi (dữ liệu y tế). */
	public void delete(UUID actorId, boolean admin, UUID id) {
		activeUser(actorId);
		Prediction p = authorized(actorId, admin, id);
		Instant now = clock.instant();
		p.setDeletedAt(now);
		p.setUpdatedAt(now);
		predictionRepository.saveAndFlush(p);
	}

	public static ApiException disabled() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PREDICT_DISABLED", "Chức năng dự đoán đang tắt.");
	}

	// ---------- Nội bộ ----------

	private void runModel(Prediction p, byte[] image) {
		if (!props.predict().enabled()) {
			p.applyFailure(PredictException.DISABLED);
			return;
		}
		try {
			p.applySuccess(predictClient.predict(p.getLamSang(), image, p.getImageContentType()));
		}
		catch (PredictException ex) {
			p.applyFailure(ex.getCode());
		}
	}

	/** Quy tắc phụ thuộc hồ sơ và giới hạn của {@code lamSang}; trả bản sao {@code lamSang} đã kiểm tra. */
	private Map<String, Object> validate(PredictionRequest req, User user) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (user.getBirthDate() != null && req.examDate() != null && req.examDate().isBefore(user.getBirthDate())) {
			errors.put("examDate", "Ngày khám không được trước ngày sinh.");
		}
		Map<String, Object> lamSang = new LinkedHashMap<>();
		Object raw = req.lamSang();
		if (raw instanceof Map<?, ?> map) {
			if (map.size() > MAX_FIELDS) {
				errors.put(ClinicalValidator.KEY, "Thông tin lâm sàng tối đa " + MAX_FIELDS + " trường.");
			}
			else if (jsonMapper.writeValueAsBytes(map).length > props.prediction().lamSangMaxSize().toBytes()) {
				errors.put(ClinicalValidator.KEY, "Thông tin lâm sàng tối đa "
						+ props.prediction().lamSangMaxSize().toKilobytes() + " KB.");
			}
			else {
				for (Map.Entry<?, ?> e : map.entrySet()) {
					Object v = e.getValue();
					if (v instanceof Map<?, ?> || v instanceof Collection<?>) {
						errors.put(ClinicalValidator.KEY + "." + e.getKey(), "Kiểu giá trị không hợp lệ.");
					}
					else {
						lamSang.put(String.valueOf(e.getKey()), v);
					}
				}
				if (errors.isEmpty()) {
					errors.putAll(clinicalValidator.validate(lamSang));
				}
			}
		}
		else if (raw != null) {
			errors.putAll(clinicalValidator.validate(raw));
		}
		if (!errors.isEmpty()) {
			throw new InvalidFieldsException(errors);
		}
		return lamSang;
	}

	private ProcessedImage readImage(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			return null;
		}
		try {
			return imageProcessor.process(file.getBytes());
		}
		catch (IOException ex) {
			throw ApiException.badRequest("INVALID_IMAGE", "Không thể đọc tệp ảnh.");
		}
	}

	/** Giới tính / năm sinh trong hồ sơ là nguồn đúng; hồ sơ chưa có thì giữ giá trị client gửi (nếu có). */
	static Map<String, Object> withProfile(Map<String, Object> lamSang, User user) {
		Map<String, Object> input = new LinkedHashMap<>(lamSang);
		if (user.getGender() == Gender.MALE) {
			input.put("gioitinh", "Nam");
		}
		else if (user.getGender() == Gender.FEMALE) {
			input.put("gioitinh", "Nu");
		}
		if (user.getBirthDate() != null) {
			input.put("namsinh", user.getBirthDate().getYear());
		}
		return input;
	}

	/** Có ít nhất một giá trị không rỗng. */
	static boolean hasValue(Map<String, Object> lamSang) {
		return lamSang.values().stream().anyMatch(v -> v != null && !(v instanceof String s && s.isBlank()));
	}

	private Prediction authorized(UUID actorId, boolean admin, UUID id) {
		Prediction p = predictionRepository.findByIdAndDeletedAtIsNull(id).orElseThrow(PredictionService::notFound);
		if (!admin && !actorId.equals(p.getUserId())) {
			throw notFound();
		}
		return p;
	}

	private User activeUser(UUID userId) {
		User user = userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng."));
		if (!user.isEnabled()) {
			throw ApiException.forbidden("ACCOUNT_DISABLED", "Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.");
		}
		return user;
	}

	private Owner owner(User user) {
		if (user == null) {
			return null;
		}
		return new Owner(user.getId(), user.getCode(), user.getFullName());
	}

	private Map<UUID, Owner> owners(Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userRepository.findAllById(userIds)
			.stream()
			.map(this::owner)
			.collect(Collectors.toMap(Owner::id, Function.identity()));
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), clock.getZone());
	}

	/** Không log điểm, kết luận, chỉ số lâm sàng hay nội dung ảnh. */
	private static void logResult(Prediction p, long started) {
		log.info("Prediction {}: {}{} in {} ms", p.getId(), p.getStatus(),
				p.getErrorCode() == null ? "" : " " + p.getErrorCode(), (System.nanoTime() - started) / 1_000_000);
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PREDICTION_NOT_FOUND", "Không tìm thấy lượt chẩn đoán.");
	}

	public record ImageContent(String contentType, byte[] bytes) {
	}

}
