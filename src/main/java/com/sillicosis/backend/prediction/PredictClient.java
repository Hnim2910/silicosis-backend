package com.sillicosis.backend.prediction;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;

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

/** Gọi {@code POST /predict} của service FastAPI (thư mục predict/). Lỗi nào cũng thành {@link PredictException}. */
@Component
public class PredictClient {

	private static final Set<String> LABELS = Set.of("Co", "Khong");

	private final RestClient restClient;

	public PredictClient(AppProperties props) {
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

	public Result predict(byte[] content, String contentType) {
		HttpHeaders partHeaders = new HttpHeaders();
		partHeaders.setContentType(MediaType.parseMediaType(contentType));
		// Tên tệp cố định: FastAPI cần filename để nhận là UploadFile; không gửi tên gốc.
		String filename = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? "image.png" : "image.jpg";
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", new HttpEntity<>(new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		}, partHeaders));

		Result result;
		try {
			result = restClient.post()
				.uri("/predict")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(parts)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (req, res) -> {
					throw new PredictException(res.getStatusCode().is4xxClientError() ? PredictException.IMAGE_REJECTED
							: PredictException.UNAVAILABLE);
				})
				.body(Result.class);
		}
		catch (ResourceAccessException ex) {
			throw new PredictException(isReadTimeout(ex) ? PredictException.TIMEOUT : PredictException.UNAVAILABLE);
		}
		catch (RestClientException ex) {
			throw new PredictException(PredictException.BAD_RESPONSE);
		}
		if (!isValid(result)) {
			throw new PredictException(PredictException.BAD_RESPONSE);
		}
		return result;
	}

	private static boolean isValid(Result r) {
		return r != null && inUnitRange(r.probability()) && inUnitRange(r.threshold()) && LABELS.contains(r.label())
				&& r.modelVersion() != null && !r.modelVersion().isBlank() && r.modelVersion().length() <= 64
				&& (r.modelName() == null || r.modelName().length() <= 100);
	}

	private static boolean inUnitRange(Double v) {
		return v != null && v >= 0 && v <= 1;
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

	/** Phản hồi thành công của service (tên trường snake_case). */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Result(Double probability, String label, Double threshold,
			@JsonProperty("model_version") String modelVersion, @JsonProperty("model_name") String modelName) {

		@Override
		public String toString() {
			return "Result[modelVersion=" + modelVersion + "]";
		}

	}

}
