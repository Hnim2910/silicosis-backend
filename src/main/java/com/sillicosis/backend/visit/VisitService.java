package com.sillicosis.backend.visit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.InvalidFieldsException;
import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.image.Image;
import com.sillicosis.backend.image.ImageRepository;
import com.sillicosis.backend.patient.Patient;
import com.sillicosis.backend.patient.PatientService;
import com.sillicosis.backend.prediction.PredictionService;
import com.sillicosis.backend.prediction.dto.PredictionResponse;
import com.sillicosis.backend.visit.dto.VisitRequest;
import com.sillicosis.backend.visit.dto.VisitResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lượt khám của hồ sơ bệnh nhân (bản demo). Quyền theo hồ sơ: ADMIN hoặc chủ hồ sơ; người khác nhận 404. Mỗi lượt
 * khám gắn tối đa một ảnh qua {@code images.visit_id}.
 */
@Service
@RequiredArgsConstructor
public class VisitService {

	private final VisitRepository visitRepository;
	private final ImageRepository imageRepository;
	private final PatientService patientService;
	private final PredictionService predictionService;
	private final JsonMapper jsonMapper;
	private final AppProperties props;
	private final Clock clock;

	@Transactional
	public VisitResponse create(UUID actorId, boolean admin, UUID patientId, VisitRequest req) {
		Patient patient = patientService.requireAccessible(actorId, admin, patientId);
		Map<String, Object> extra = validate(req, patient);
		if (isEmpty(req, extra)) {
			throw ApiException.badRequest("EMPTY_SUBMISSION",
					"Vui lòng gửi ít nhất một ảnh hoặc một thông tin lâm sàng.");
		}
		Visit visit = new Visit();
		visit.setPatientId(patient.getId());
		visit.setCreatedBy(actorId);
		apply(visit, req, extra);
		if (visit.getVisitDate() == null) {
			visit.setVisitDate(LocalDate.ofInstant(clock.instant(), clock.getZone()));
		}
		visitRepository.saveAndFlush(visit);
		Image image = req.imageId() == null ? null : attach(visit.getId(), actorId, admin, req.imageId());
		return toResponse(visit, patient, image, null);
	}

	@Transactional(readOnly = true)
	public PageResponse<VisitResponse> list(UUID actorId, boolean admin, UUID patientId, Pageable pageable) {
		Patient patient = patientService.requireAccessible(actorId, admin, patientId);
		Page<Visit> page = visitRepository.findByPatientIdAndDeletedAtIsNull(patient.getId(), pageable);
		List<UUID> ids = page.getContent().stream().map(Visit::getId).toList();
		Map<UUID, Image> images = imagesOf(ids);
		Map<UUID, PredictionResponse> predictions = predictionService.latestFor(ids);
		return PageResponse.of(page,
				v -> toResponse(v, patient, images.get(v.getId()), predictions.get(v.getId())));
	}

	/** Mọi lượt khám (chỉ ADMIN, kiểm tra ở controller); mỗi phần tử kèm thông tin ngắn của bệnh nhân. */
	@Transactional(readOnly = true)
	public PageResponse<VisitResponse> listAll(Pageable pageable) {
		Page<Visit> page = visitRepository.findAllActive(pageable);
		List<UUID> ids = page.getContent().stream().map(Visit::getId).toList();
		Map<UUID, Patient> patients = patientService
			.findAllByIds(page.getContent().stream().map(Visit::getPatientId).distinct().toList());
		Map<UUID, Image> images = imagesOf(ids);
		Map<UUID, PredictionResponse> predictions = predictionService.latestFor(ids);
		return PageResponse.of(page, v -> toResponse(v, patients.get(v.getPatientId()), images.get(v.getId()),
				predictions.get(v.getId())));
	}

	@Transactional(readOnly = true)
	public VisitResponse get(UUID actorId, boolean admin, UUID id) {
		Access a = authorizedVisit(actorId, admin, id);
		return toResponse(a.visit(), a.patient(), attachedImage(id), latestPrediction(id));
	}

	/** Ghi đè toàn bộ trường sửa được; {@code patientId} không đổi. */
	@Transactional
	public VisitResponse update(UUID actorId, boolean admin, UUID id, VisitRequest req) {
		Access a = authorizedVisit(actorId, admin, id);
		Map<String, Object> extra = validate(req, a.patient());
		apply(a.visit(), req, extra);
		visitRepository.flush();
		return toResponse(a.visit(), a.patient(), attachedImage(id), latestPrediction(id));
	}

	/** Xoá mềm; ảnh đang gắn được gỡ khỏi lượt khám, không bị xoá. */
	@Transactional
	public void delete(UUID actorId, boolean admin, UUID id) {
		Access a = authorizedVisit(actorId, admin, id);
		detach(id);
		a.visit().setDeletedAt(clock.instant());
	}


	/**
	 * Ảnh phải còn, thuộc người thao tác (hoặc người thao tác là ADMIN), chưa gắn lượt khám khác; lượt khám chưa có
	 * ảnh khác. Gắn lại đúng ảnh đang gắn thì không đổi gì.
	 */
	@Transactional
	public VisitResponse attachImage(UUID actorId, boolean admin, UUID id, UUID imageId) {
		Access a = authorizedVisit(actorId, admin, id);
		Image image = attach(id, actorId, admin, imageId);
		return toResponse(a.visit(), a.patient(), image, latestPrediction(id));
	}

	private Image attach(UUID visitId, UUID actorId, boolean admin, UUID imageId) {
		Image image = imageRepository.findByIdAndDeletedAtIsNull(imageId)
			.filter(i -> admin || i.getOwnerId().equals(actorId))
			.orElseThrow(() -> ApiException.notFound("Không tìm thấy ảnh."));
		if (!visitId.equals(image.getVisitId())) {
			if (image.getVisitId() != null) {
				throw ApiException.conflict("IMAGE_ALREADY_ATTACHED", "Ảnh đã được gắn vào một lượt khám khác.");
			}
			if (imageRepository.findByVisitIdAndDeletedAtIsNull(visitId).isPresent()) {
				throw ApiException.conflict("VISIT_ALREADY_HAS_IMAGE",
						"Lượt khám đã có ảnh. Hãy gỡ ảnh cũ trước khi gắn ảnh mới.");
			}
			image.setVisitId(visitId);
			imageRepository.flush();
		}
		return image;
	}

	/** Gỡ ảnh khỏi lượt khám (không xoá ảnh); lượt khám chưa có ảnh thì không làm gì. */
	@Transactional
	public void detachImage(UUID actorId, boolean admin, UUID id) {
		authorizedVisit(actorId, admin, id);
		detach(id);
	}

	// ---------- Tính khi trả về ----------

	/** Tuổi tròn năm tại ngày khám; null nếu hồ sơ chưa có ngày sinh. */
	static Integer ageAt(LocalDate birthDate, LocalDate visitDate) {
		if (birthDate == null || visitDate == null || visitDate.isBefore(birthDate)) {
			return null;
		}
		return Period.between(birthDate, visitDate).getYears();
	}

	/** BMI = cân nặng (kg) / chiều cao (m)², làm tròn 1 chữ số; null nếu thiếu một trong hai. */
	static BigDecimal bmi(BigDecimal heightCm, BigDecimal weightKg) {
		if (heightCm == null || weightKg == null || heightCm.signum() <= 0) {
			return null;
		}
		BigDecimal heightM = heightCm.movePointLeft(2);
		return weightKg.divide(heightM.multiply(heightM), 1, RoundingMode.HALF_UP);
	}

	// ---------- Nội bộ ----------

	private record Access(Visit visit, Patient patient) {
	}

	/** Lượt khám không tồn tại, đã xoá, hoặc thuộc hồ sơ người thao tác không được xem → 404 VISIT_NOT_FOUND. */
	private Access authorizedVisit(UUID actorId, boolean admin, UUID id) {
		Visit visit = visitRepository.findByIdAndDeletedAtIsNull(id).orElseThrow(VisitService::visitNotFound);
		try {
			return new Access(visit, patientService.requireAccessible(actorId, admin, visit.getPatientId()));
		}
		catch (ApiException ex) {
			if ("PATIENT_NOT_FOUND".equals(ex.getCode())) {
				throw visitNotFound();
			}
			throw ex;
		}
	}

	/** Quy tắc liên trường và giới hạn của {@code extra}; trả {@code extra} đã kiểm tra. */
	private Map<String, Object> validate(VisitRequest req, Patient patient) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (patient.getBirthDate() != null && req.visitDate() != null && req.visitDate().isBefore(patient.getBirthDate())) {
			errors.put("visitDate", "Ngày khám không được trước ngày sinh.");
		}
		if (req.systolicBp() != null && req.diastolicBp() != null && req.systolicBp() <= req.diastolicBp()) {
			errors.put("systolicBp", "Huyết áp tâm thu phải lớn hơn huyết áp tâm trương.");
		}
		if (req.fev1L() != null && req.fvcL() != null && req.fev1L().compareTo(req.fvcL()) > 0) {
			errors.put("fev1L", "FEV1 không được lớn hơn FVC.");
		}
		Map<String, Object> extra = new LinkedHashMap<>();
		String extraError = checkExtra(req.extra(), extra);
		if (extraError != null) {
			errors.put("extra", extraError);
		}
		if (!errors.isEmpty()) {
			throw new InvalidFieldsException(errors);
		}
		return extra;
	}

	/** Chép {@code value} vào {@code out} nếu hợp lệ; trả thông báo lỗi hoặc null. */
	@SuppressWarnings("unchecked")
	private String checkExtra(Object value, Map<String, Object> out) {
		if (value == null) {
			return null;
		}
		if (!(value instanceof Map<?, ?> map)) {
			return "Thông tin bổ sung phải là một đối tượng JSON.";
		}
		int maxDepth = props.visit().extraMaxDepth();
		if (exceedsDepth(map, 1, maxDepth)) {
			return "Thông tin bổ sung lồng tối đa " + maxDepth + " cấp.";
		}
		long maxBytes = props.visit().extraMaxSize().toBytes();
		if (jsonMapper.writeValueAsBytes(map).length > maxBytes) {
			return "Thông tin bổ sung tối đa " + maxBytes / 1024 + " KB.";
		}
		out.putAll((Map<String, Object>) map);
		return null;
	}

	/** {@code value} ở cấp {@code level}; object hoặc mảng lồng bên trong tính thêm một cấp. */
	private static boolean exceedsDepth(Object value, int level, int max) {
		Collection<?> children = value instanceof Map<?, ?> m ? m.values()
				: value instanceof Collection<?> c ? c : null;
		if (children == null) {
			return false;
		}
		if (level > max) {
			return true;
		}
		for (Object child : children) {
			if (exceedsDepth(child, level + 1, max)) {
				return true;
			}
		}
		return false;
	}

	/** Không có ảnh, không có chỉ số nào và {@code extra} trống. */
	private static boolean isEmpty(VisitRequest r, Map<String, Object> extra) {
		return r.imageId() == null && extra.isEmpty() && blankToNull(r.industry()) == null
				&& blankToNull(r.province()) == null
				&& Stream
					.of(r.yearsInJob(), r.yearsExposed(), r.heightCm(), r.weightKg(), r.systolicBp(), r.diastolicBp(),
							r.pulse(), r.smoker(), r.cigarettesPerDay(), r.fvcL(), r.fev1L())
					.allMatch(Objects::isNull);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	/** Ngày khám trống: giữ giá trị cũ (khi sửa) hoặc để create đặt thành ngày gửi. */
	private static void apply(Visit v, VisitRequest req, Map<String, Object> extra) {
		if (req.visitDate() != null) {
			v.setVisitDate(req.visitDate());
		}
		v.setIndustry(blankToNull(req.industry()));
		v.setYearsInJob(req.yearsInJob());
		v.setProvince(blankToNull(req.province()));
		v.setYearsExposed(req.yearsExposed());
		v.setHeightCm(req.heightCm());
		v.setWeightKg(req.weightKg());
		v.setSystolicBp(req.systolicBp());
		v.setDiastolicBp(req.diastolicBp());
		v.setPulse(req.pulse());
		v.setSmoker(req.smoker());
		// Số điếu chỉ có nghĩa khi đang hút thuốc.
		v.setCigarettesPerDay(Boolean.TRUE.equals(req.smoker()) ? req.cigarettesPerDay() : null);
		v.setFvcL(req.fvcL());
		v.setFev1L(req.fev1L());
		v.setExtra(extra);
		v.setSchemaVersion(Visit.CURRENT_SCHEMA_VERSION);
	}

	private VisitResponse toResponse(Visit v, Patient patient, Image image, PredictionResponse prediction) {
		return VisitResponse.from(v, patient, ageAt(patient.getBirthDate(), v.getVisitDate()),
				bmi(v.getHeightCm(), v.getWeightKg()), image, prediction);
	}

	private PredictionResponse latestPrediction(UUID visitId) {
		return predictionService.latestFor(List.of(visitId)).get(visitId);
	}

	private Image attachedImage(UUID visitId) {
		return imageRepository.findByVisitIdAndDeletedAtIsNull(visitId).orElse(null);
	}

	private Map<UUID, Image> imagesOf(List<UUID> visitIds) {
		if (visitIds.isEmpty()) {
			return Map.of();
		}
		return imageRepository.findByVisitIdInAndDeletedAtIsNull(visitIds)
			.stream()
			.collect(Collectors.toMap(Image::getVisitId, Function.identity()));
	}

	private void detach(UUID visitId) {
		imageRepository.findByVisitIdAndDeletedAtIsNull(visitId).ifPresent(i -> i.setVisitId(null));
	}

	private static ApiException visitNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "VISIT_NOT_FOUND", "Không tìm thấy lượt khám.");
	}

}
