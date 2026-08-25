package com.saga.be.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	public static final String SESSION_COOKIE_SCHEME = "SAGA_SESSION";

	@Bean
	public OpenAPI sagaOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("SAGA Backend API")
						.description(
								"""
								Student Activity Graph Based Continuous Assessment

								Auth V1 uses an HttpOnly server-side session cookie (`SAGA_SESSION`) backed by \
								Valkey/Redis. There is no Bearer JWT for browser Auth V1. Do not send `role` from \
								the client; the server loads it from MySQL.

								CSRF: call `GET /api/auth/csrf`, then send cookie `XSRF-TOKEN` as header \
								`X-XSRF-TOKEN` on POST/PUT/PATCH/DELETE. Swagger UI does this automatically.

								Google login is a browser redirect (`GET /oauth2/authorization/google`) when \
								`GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` are configured. Swagger cannot complete \
								that redirect. Client secrets are never documented here.

								Public registration (`POST /api/auth/register`) creates STUDENT only, for personal \
								email. Institutional FPT/FE accounts use Google. Lecturers and Admins cannot public-register.
								""")
						.version("v1"))
				.servers(List.of(new Server().url("/").description("Current host")))
				.components(new Components()
						.addSecuritySchemes(
								SESSION_COOKIE_SCHEME,
								new SecurityScheme()
										.type(SecurityScheme.Type.APIKEY)
										.in(SecurityScheme.In.COOKIE)
										.name("SAGA_SESSION")
										.description(
												"HttpOnly session cookie set after local login or Google OIDC. "
														+ "The browser retains it automatically. Not a JWT.")));
	}

	@Bean
	public OpenApiCustomizer csrfGetHasNoRequestParameters() {
		return openApi -> {
			PathItem csrf = openApi.getPaths().get("/api/auth/csrf");
			if (csrf != null && csrf.getGet() != null) {
				csrf.getGet().setParameters(null);
			}
		};
	}

	@Bean
	public OpenApiCustomizer googleAuthorizationPath() {
		return openApi -> openApi.path(
				"/oauth2/authorization/google",
				new PathItem()
						.get(new Operation()
								.addTagsItem("Authentication")
								.summary("Start Google OIDC login")
								.description(
										"""
										Browser entry point when Google OAuth is configured. This is a redirect, \
										not a JSON login. Requires `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` \
										in the server environment. Do not put the client secret in Swagger.
										""")
								.responses(new ApiResponses()
										.addApiResponse(
												"302",
												new ApiResponse().description("Redirect to Google, or to the failure URL if Google is not configured")))));
	}
}
