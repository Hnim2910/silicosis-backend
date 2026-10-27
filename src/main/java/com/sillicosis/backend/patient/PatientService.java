package com.sillicosis.backend.patient;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.common.InvalidFieldsException;
import com.sillicosis.backend.common.PageResponse;
import com.sillicosis.backend.common.PhoneNumbers;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.patient.dto.PatientRequest;
import com.sillicosis.backend.patient.dto.PatientResponse;
import com.sillicosis.backend.user.User;
import com.sillicosis.backend.user.UserRepository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.CriteriaBuilder;
import lombok.RequiredArgsConstructor;

/**
 * Hồ sơ bệnh nhân. Mỗi tài khoản USER có đúng một hồ sơ, tạo cùng transaction với tài khoản. Họ tên, ngày sinh,
 * giới tính, nghề nghiệp được đồng bộ hai chiều giữa tài khoản và hồ sơ; SĐT thì không.
 */
@Service
@RequiredArgsConstructor
public class PatientService {

	private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

	/** Chữ có dấu tiếng Việt (thường + hoa) và chữ không dấu tương ứng, dùng cho translate() của Postgres. */
	private static final String ACCENTED;

	private static final String PLAIN;

	static {
		String[][] groups = { { "àáảãạăằắẳẵặâầấẩẫậ", "a" }, { "èéẻẽẹêềếểễệ", "e" }, { "ìíỉĩị", "i" },
				{ "òóỏõọôồốổỗộơờớởỡợ", "o" }, { "ùúủũụưừứửữự", "u" }, { "ỳýỷỹỵ", "y" }, { "đ", "d" } };
		StringBuilder from = new StringBuilder();
		StringBuilder to = new StringBuilder();
		for (String[] g : groups) {
			from.append(g[0]).append(g[0].toUpperCase(Locale.ROOT));
			to.append(g[1].repeat(g[0].length())).append(g[1].toUpperCase(Locale.ROOT).repeat(g[0].length()));
		}
		ACCENTED = from.toString();
		PLAIN = to.toString();
	}

	private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

	private final PatientRepository patientRepository;
	private final UserRepository userRepository;
	private final AppProperties props;
	private final Clock clock;

	// ---------- Gọi từ luồng tạo / sửa tài khoản ----------

	/**
	 * Tạo hồ sơ cho tài khoản USER vừa tạo; phải gọi trong transaction tạo tài khoản.
	 * @param createdBy người tạo: chính user khi tự đăng ký, ADMIN khi admin tạo
	 */
	@Transactional
	public Patient createForUser(User user, UUID createdBy) {
		Patient patient = new Patient();
		patient.setCode(nextCode());
		patient.setUserId(user.getId());
		patient.setFullName(user.getFullName());
		patient.setBirthDate(user.getBirthDate());
		patient.setGender(user.getGender());
		patient.setOccupation(user.getOccupation());
		patient.setPhone(user.getPhone());
		patient.setCreatedBy(createdBy);
		return patientRepository.save(patient);
	}

	/** Tài khoản → hồ sơ: chép 4 trường đồng bộ sang hồ sơ đang hoạt động của tài khoản (nếu có). */
	@Transactional
	public void syncFromUser(User user) {
		patientRepository.findByUserIdAndDeletedAtIsNull(user.getId()).ifPresent(p -> {
			p.setFullName(user.getFullName());
			p.setBirthDate(user.getBirthDate());
			p.setGender(user.getGender());
			p.setOccupation(user.getOccupation());
		});
	}

	// ---------- API ----------

	@Transactional(readOnly = true)
	public PatientResponse getMine(UUID actorId) {
		activeUser(actorId);
		return patientRepository.findByUserIdAndDeletedAtIsNull(actorId)
			.map(PatientResponse::from)
			.orElseThrow(PatientService::patientNotFound);
	}

	@Transactional(readOnly = true)
	public PageResponse<PatientResponse> list(String query, Pageable pageable) {
		return PageResponse.of(patientRepository.findAll(filter(query), pageable), PatientResponse::from);
	}

	@Transactional(readOnly = true)
	public PatientResponse get(UUID actorId, boolean admin, UUID id) {
		activeUser(actorId);
		return PatientResponse.from(authorizedPatient(actorId, admin, id));
	}

	/** ADMIN tạo hồ sơ không gắn tài khoản. */
	@Transactional
	public PatientResponse create(UUID actorId, PatientRequest req) {
		activeUser(actorId);
		validate(req);
		Patient patient = new Patient();
		patient.setCode(nextCode());
		patient.setCreatedBy(actorId);
		apply(patient, req);
		return PatientResponse.from(patientRepository.saveAndFlush(patient));
	}

	/** Ghi đè toàn bộ trường sửa được; {@code code} và {@code userId} không đổi. Hồ sơ → tài khoản: đồng bộ 4 trường. */
	@Transactional
	public PatientResponse update(UUID actorId, boolean admin, UUID id, PatientRequest req) {
		activeUser(actorId);
		Patient patient = authorizedPatient(actorId, admin, id);
		validate(req);
		apply(patient, req);
		if (patient.getUserId() != null) {
			userRepository.findByIdAndDeletedAtIsNull(patient.getUserId()).ifPresent(u -> {
				u.setFullName(patient.getFullName());
				u.setBirthDate(patient.getBirthDate());
				u.setGender(patient.getGender());
				u.setOccupation(patient.getOccupation());
			});
		}
		patientRepository.flush();
		return PatientResponse.from(patient);
	}

	/**
	 * Xoá mềm. Hồ sơ còn gắn với tài khoản đang hoạt động thì không xoá được (409), để mỗi tài khoản USER luôn có
	 * đúng một hồ sơ; phải xoá tài khoản trước. Xoá tài khoản thì hồ sơ được giữ lại (dữ liệu y tế).
	 */
	@Transactional
	public void delete(UUID actorId, UUID id) {
		activeUser(actorId);
		Patient patient = patientRepository.findByIdAndDeletedAtIsNull(id).orElseThrow(PatientService::patientNotFound);
		if (patient.getUserId() != null && userRepository.findByIdAndDeletedAtIsNull(patient.getUserId()).isPresent()) {
			throw ApiException.conflict("PATIENT_LINKED_TO_ACCOUNT",
					"Hồ sơ đang gắn với một tài khoản. Hãy xoá tài khoản trước khi xoá hồ sơ.");
		}
		patient.setDeletedAt(clock.instant());
	}

	/**
	 * Cho các module khác (lượt khám): hồ sơ chưa xoá mà người thao tác được phép truy cập, nếu không thì 404
	 * PATIENT_NOT_FOUND. Kiểm tra cả tài khoản người thao tác còn hoạt động.
	 */
	@Transactional(readOnly = true)
	public Patient requireAccessible(UUID actorId, boolean admin, UUID patientId) {
		activeUser(actorId);
		return authorizedPatient(actorId, admin, patientId);
	}

	/** Cho danh sách lượt khám của ADMIN: nạp nhiều hồ sơ một lần (quyền do nơi gọi kiểm tra). */
	@Transactional(readOnly = true)
	public Map<UUID, Patient> findAllByIds(Collection<UUID> ids) {
		return patientRepository.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(Patient::getId, Function.identity()));
	}

	// ---------- Nội bộ ----------

	String nextCode() {
		return "BN-%06d".formatted(patientRepository.nextCodeNumber());
	}

	/** Không phải ADMIN và không phải chủ hồ sơ → 404 như khi không tồn tại, để không lộ hồ sơ. */
	private Patient authorizedPatient(UUID actorId, boolean admin, UUID id) {
		Patient patient = patientRepository.findByIdAndDeletedAtIsNull(id).orElseThrow(PatientService::patientNotFound);
		if (!admin && !actorId.equals(patient.getUserId())) {
			throw patientNotFound();
		}
		return patient;
	}

	private void activeUser(UUID userId) {
		User user = userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng."));
		if (!user.isEnabled()) {
			throw ApiException.forbidden("ACCOUNT_DISABLED", "Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.");
		}
	}

	/** Quy tắc phụ thuộc cấu hình hoặc ngày hiện tại; phần còn lại nằm ở annotation của {@link PatientRequest}. */
	private void validate(PatientRequest req) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (req.birthDate() != null && req.birthDate().isBefore(MIN_BIRTH_DATE)) {
			errors.put("birthDate", "Ngày sinh không hợp lệ.");
		}
		int max = props.patient().maxTextLength();
		if (length(req.exposureHistory()) > max) {
			errors.put("exposureHistory", "Tiền sử phơi nhiễm tối đa " + max + " ký tự.");
		}
		if (length(req.riskFactors()) > max) {
			errors.put("riskFactors", "Yếu tố nguy cơ tối đa " + max + " ký tự.");
		}
		if (!errors.isEmpty()) {
			throw new InvalidFieldsException(errors);
		}
	}

	private static void apply(Patient p, PatientRequest req) {
		p.setFullName(req.fullName().trim());
		p.setBirthDate(req.birthDate());
		p.setGender(req.gender());
		p.setPhone(PhoneNumbers.normalize(req.phone()));
		p.setOccupation(blankToNull(req.occupation()));
		p.setWorkplace(blankToNull(req.workplace()));
		p.setExposureHistory(blankToNull(req.exposureHistory()));
		p.setRiskFactors(blankToNull(req.riskFactors()));
	}

	/** Tìm không phân biệt hoa thường và dấu tiếng Việt trong họ tên, mã, SĐT, nghề nghiệp. */
	private static Specification<Patient> filter(String query) {
		return (root, cq, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.isNull(root.get("deletedAt")));
			String term = query == null ? "" : fold(query.trim());
			if (!term.isEmpty()) {
				String like = "%" + escapeLike(term) + "%";
				predicates.add(cb.or(cb.like(folded(cb, root, "fullName"), like, '\\'),
						cb.like(cb.lower(root.get("code")), like, '\\'), cb.like(root.get("phone"), like, '\\'),
						cb.like(folded(cb, root, "occupation"), like, '\\')));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	private static Expression<String> folded(CriteriaBuilder cb, Root<Patient> root, String field) {
		return cb.lower(cb.function("translate", String.class, root.get(field), cb.literal(ACCENTED),
				cb.literal(PLAIN)));
	}

	/** Bỏ dấu và về chữ thường, khớp với {@link #folded}. */
	static String fold(String s) {
		String noMarks = COMBINING_MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
		return noMarks.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
	}

	private static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static int length(String s) {
		return s == null ? 0 : s.codePointCount(0, s.length());
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static ApiException patientNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", "Không tìm thấy hồ sơ bệnh nhân.");
	}

}
