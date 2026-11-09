package com.sillicosis.backend.prediction;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.sillicosis.backend.common.ApiException;
import com.sillicosis.backend.config.AppProperties;
import com.sillicosis.backend.image.Image;
import com.sillicosis.backend.image.ImageRepository;
import com.sillicosis.backend.image.ImageStorage;
import com.sillicosis.backend.prediction.dto.PredictionResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chạy dự đoán cho ảnh của lượt khám. Không mở transaction quanh lời gọi HTTP (có thể mất tới vài chục giây); chỉ
 * lưu kết quả sau khi có. Quyền truy cập lượt khám do nơi gọi kiểm tra trước.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

	private final PredictionRepository predictionRepository;
	private final ImageRepository imageRepository;
	private final ImageStorage imageStorage;
	private final PredictClient predictClient;
	private final AppProperties props;
	private final Clock clock;

	public boolean enabled() {
		return props.predict().enabled();
	}

	/**
	 * Gọi service New Way rồi ghi một dòng predictions, kể cả khi lỗi (FAILED + errorCode). {@code imageId} null = chỉ
	 * lâm sàng; {@code lamSang} là {@code visits.extra["lam_sang"]} (null/không phải object coi như rỗng).
	 */
	public PredictionResponse run(UUID visitId, UUID imageId, Object lamSang, UUID actorId) {
		Image image = imageId == null ? null
				: imageRepository.findByIdAndDeletedAtIsNull(imageId)
					.orElseThrow(() -> ApiException.notFound("Không tìm thấy ảnh."));
		Prediction p = new Prediction();
		p.setVisitId(visitId);
		p.setImageId(imageId);
		p.setCreatedBy(actorId);
		long started = System.nanoTime();
		try {
			PredictClient.Outcome o = image == null ? predictClient.predict(asMap(lamSang), null, null)
					: predictClient.predict(asMap(lamSang), read(image), image.getContentType());
			PredictClient.Result r = o.result();
			p.setStatus(PredictionStatus.SUCCESS);
			// Cột cũ = kết luận cuối, để client cũ và ràng buộc V2 vẫn đúng.
			p.setProbability(r.finalProbability());
			p.setThreshold(r.finalThreshold());
			p.setLabel(r.ketLuanCuoi() ? "Co" : "Khong");
			p.setModelName(o.modelName());
			p.setModelVersion(o.modelVersion());
			p.setPLamSang(r.pLamSang());
			p.setNguongLamSang(r.nguongLamSang());
			p.setBaoNguyCoLamSang(r.baoNguyCoLamSang());
			p.setCoAnh(r.coAnh());
			p.setPAnh(r.pAnh());
			p.setPGhep(r.pGhep());
			p.setNguongGhep(r.nguongGhep());
			p.setBaoNguyCoGhep(r.baoNguyCoGhep());
			p.setKetLuanCuoi(r.ketLuanCuoi());
		}
		catch (PredictException ex) {
			p.setStatus(PredictionStatus.FAILED);
			p.setErrorCode(ex.getCode());
		}
		p.setCreatedAt(clock.instant());
		Prediction saved = predictionRepository.save(p);
		// Không log xác suất, nhãn hay nội dung ảnh.
		log.info("Prediction {} for visit {}: {}{} in {} ms", saved.getId(), visitId, saved.getStatus(),
				saved.getErrorCode() == null ? "" : " " + saved.getErrorCode(),
				(System.nanoTime() - started) / 1_000_000);
		return PredictionResponse.from(saved);
	}

	/** Kết quả mới nhất ứng với ảnh đang gắn của từng lượt khám; lượt khám không có thì vắng mặt trong map. */
	public Map<UUID, PredictionResponse> latestFor(Collection<UUID> visitIds) {
		if (visitIds.isEmpty()) {
			return Map.of();
		}
		return predictionRepository.findLatestForAttachedImages(visitIds)
			.stream()
			.map(PredictionResponse::from)
			.collect(Collectors.toMap(PredictionResponse::visitId, Function.identity()));
	}

	public static ApiException disabled() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PREDICT_DISABLED", "Chức năng dự đoán đang tắt.");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object lamSang) {
		return lamSang instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	private byte[] read(Image image) {
		try {
			return imageStorage.read(image.getStorageKey());
		}
		catch (RuntimeException ex) {
			throw new PredictException(PredictException.IMAGE_UNREADABLE);
		}
	}

}
