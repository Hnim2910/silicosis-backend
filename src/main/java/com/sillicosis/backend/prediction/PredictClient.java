package com.sillicosis.backend.prediction;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sillicosis.backend.config.AppProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Gọi service FastAPI New Way (thư mục predict/): {@code POST /predict/clinical} (chỉ lâm sàng, JSON) hoặc
 * {@code POST /predict/clinical-image} (multipart: phần {@code lam_sang} là chuỗi JSON + phần {@code file}). Lỗi nào
 * cũng thành {@link PredictException}.
 */
@Component
public class PredictClient {

	public static final String ENGINE = "NEW_WAY";

	private final RestClient restClient;

	private final JsonMapper jsonMapper;

	public PredictClient(AppProperties props, JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
		AppProperties.Predict p = props.predict();
		// HTTP/1.1: mặc định JdkHttpClient gửi "Upgrade: h2c", uvicorn trả 400 "Unsupported upgrade request".
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(p.connectTimeout())
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(p.timeout());
		this.restClient = RestClient.builder().baseUrl(p.baseUrl()).requestFactory(factory).build();
	}

	/** {@code image} null = chỉ lâm sàng. {@code lamSang} là {tên_cột: giá_trị} theo MO_TA_API.md (mã không dấu). */
	public Outcome predict(Map<String, Object> lamSang, byte[] image, String contentType) {
		Result result;
		HttpHeaders headers;
		try {
			RestClient.RequestBodySpec req;
			if (image == null) {
				req = restClient.post().uri("/predict/clinical").contentType(MediaType.APPLICATION_JSON);
				req.body(jsonMapper.writeValueAsString(lamSang));
			}
			else {
				req = restClient.post().uri("/predict/clinical-image").contentType(MediaType.MULTIPART_FORM_DATA);
				req.body(multipart(lamSang, image, contentType));
			}
			var entity = req.retrieve().onStatus(HttpStatusCode::isError, (rq, res) -> {
				throw new PredictException(errorCode(res.getStatusCode()));
			}).toEntity(Result.class);
			result = entity.getBody();
			headers = entity.getHeaders();
		}
		catch (ResourceAccessException ex) {
			throw new PredictException(isReadTimeout(ex) ? PredictException.TIMEOUT : PredictException.UNAVAILABLE);
		}
		catch (RestClientException ex) {
			throw new PredictException(PredictException.BAD_RESPONSE);
		}
		String version = headers.getFirst("X-Model-Version");
		String name = headers.getFirst("X-Model-Name");
		if (result == null || !result.isValid(image != null) || version == null || version.isBlank()
				|| version.length() > 64) {
			throw new PredictException(PredictException.BAD_RESPONSE);
		}
		return new Outcome(result, name == null || name.isBlank() ? "New Way ketqua" : truncate(name, 100), version);
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	public record Outcome(Result result, String modelName, String modelVersion) {

		@Override
		public String toString() {
			return "Outcome[" + modelVersion + "]";
		}

	}

	/**
	 * Danh sách cột và mức hợp lệ ({@code GET /schema}): {@code kind} {cột: so|chu|muc}, {@code levels} {cột: [mức]}.
	 * Rỗng nếu service chưa chạy.
	 */
	public Optional<Schema> schema() {
		try {
			return Optional.ofNullable(restClient.get().uri("/schema").retrieve().body(Schema.class));
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	private MultiValueMap<String, Object> multipart(Map<String, Object> lamSang, byte[] image, String contentType) {
		HttpHeaders jsonHeaders = new HttpHeaders();
		jsonHeaders.setContentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8));
		HttpHeaders partHeaders = new HttpHeaders();
		partHeaders.setContentType(MediaType.parseMediaType(contentType));
		// Tên tệp cố định: FastAPI cần filename để nhận là UploadFile; không gửi tên gốc.
		String filename = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? "image.png" : "image.jpg";
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("lam_sang", new HttpEntity<>(jsonMapper.writeValueAsString(lamSang), jsonHeaders));
		parts.add("file", new HttpEntity<>(new ByteArrayResource(image) {
			@Override
			public String getFilename() {
				return filename;
			}
		}, partHeaders));
		return parts;
	}

	private static String errorCode(HttpStatusCode status) {
		if (status.value() == 422) {
			return PredictException.INVALID_INPUT;
		}
		return status.is4xxClientError() ? PredictException.IMAGE_REJECTED : PredictException.UNAVAILABLE;
	}

	/** Hết thời gian chờ phản hồi; không kết nối được (kể cả hết giờ kết nối) tính là UNAVAILABLE. */
	private static boolean isReadTimeout(Throwable ex) {
		for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
			if (t instanceof HttpConnectTimeoutException) {
				return false;
			}
			if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
				return true;
			}
			if (!(t instanceof IOException)) {
				return false;
			}
		}
		return false;
	}

	/** Dict trả về từ MoHinh.du_doan() (tên trường snake_case, xem predict/ml/new_way/MO_TA_API.md). */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Result(String nhan, @JsonProperty("p_lam_sang") Double pLamSang,
			@JsonProperty("nguong_lam_sang") Double nguongLamSang,
			@JsonProperty("bao_nguy_co_lam_sang") Boolean baoNguyCoLamSang, @JsonProperty("co_anh") Boolean coAnh,
			@JsonProperty("p_anh") Double pAnh, @JsonProperty("p_ghep") Double pGhep,
			@JsonProperty("nguong_ghep") Double nguongGhep, @JsonProperty("bao_nguy_co_ghep") Boolean baoNguyCoGhep,
			@JsonProperty("ket_luan_cuoi") Boolean ketLuanCuoi) {

		boolean isValid(boolean withImage) {
			boolean base = unit(pLamSang) && unit(nguongLamSang) && baoNguyCoLamSang != null && ketLuanCuoi != null
					&& coAnh != null && coAnh == withImage;
			if (!base) {
				return false;
			}
			return !withImage || (unit(pAnh) && unit(pGhep) && unit(nguongGhep) && baoNguyCoGhep != null);
		}

		/** Điểm dùng cho kết luận cuối: điểm ghép nếu có ảnh, ngược lại điểm lâm sàng. */
		public double finalProbability() {
			return Boolean.TRUE.equals(coAnh) ? pGhep : pLamSang;
		}

		public double finalThreshold() {
			return Boolean.TRUE.equals(coAnh) ? nguongGhep : nguongLamSang;
		}

		private static boolean unit(Double v) {
			return v != null && v >= 0 && v <= 1;
		}

		@Override
		public String toString() {
			return "Result[***]";
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Schema(Map<String, String> kind, Map<String, List<String>> levels) {
	}

}
