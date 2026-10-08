package com.sillicosis.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Swagger UI: /swagger-ui.html — bấm "Authorize" và dán access token. */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI silicoCareOpenApi() {
		return new OpenAPI()
			.info(new Info().title("SilicoCare API")
				.version("v1")
				.description("API hệ thống hỗ trợ sàng lọc bệnh bụi phổi silic — giai đoạn 1: xác thực & người dùng"))
			.components(new Components().addSecuritySchemes("bearerAuth",
					new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}

}
