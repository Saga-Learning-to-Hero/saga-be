package com.saga.be.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsPreflightWebTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";
	private static final String DISALLOWED_ORIGIN = "https://evil.example";
	private static final String PREFLIGHT_HEADERS = "content-type, x-xsrf-token, idempotency-key, x-request-id";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private CorsConfigurationSource corsConfigurationSource;

	@Test
	void corsPolicyStaysExplicitAndAddsManualNotificationRequestHeaders() {
		HttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/notifications/system");
		CorsConfiguration config = corsConfigurationSource.getCorsConfiguration(request);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedOrigins()).contains(ALLOWED_ORIGIN);
		assertThat(config.getAllowCredentials()).isTrue();
		assertThat(config.getAllowedMethods())
				.containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
		assertThat(config.getAllowedHeaders())
				.containsExactly(
						"Content-Type",
						"Accept",
						"Cache-Control",
						"X-XSRF-TOKEN",
						"X-Requested-With",
						"Idempotency-Key",
						"X-Request-Id");
		assertThat(config.getAllowedHeaders()).doesNotContain("*");
		assertThat(config.getExposedHeaders()).containsExactly("X-XSRF-TOKEN");
	}

	@Test
	void allowedOriginPreflightAcceptsManualNotificationHeaders() throws Exception {
		assertSuccessfulPreflight("/api/admin/notifications/system");
		assertSuccessfulPreflight("/api/lecturer/notifications/all-courses");
	}

	@Test
	void disallowedOriginPreflightIsRejected() throws Exception {
		mockMvc.perform(options("/api/admin/notifications/system")
						.header("Origin", DISALLOWED_ORIGIN)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", PREFLIGHT_HEADERS))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void actualGetFromAllowedOriginStillExposesOnlyCsrfHeader() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/csrf").header("Origin", ALLOWED_ORIGIN))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"))
				.andReturn();
		String exposed = result.getResponse().getHeader("Access-Control-Expose-Headers");
		assertThat(exposed).isEqualToIgnoringCase("X-XSRF-TOKEN");
		assertThat(exposed).doesNotContainIgnoringCase("Idempotency-Key");
		assertThat(exposed).doesNotContainIgnoringCase("X-Request-Id");
	}

	private void assertSuccessfulPreflight(String path) throws Exception {
		MvcResult result = mockMvc.perform(options(path)
						.header("Origin", ALLOWED_ORIGIN)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", PREFLIGHT_HEADERS))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"))
				.andReturn();
		String allowHeaders = result.getResponse().getHeader("Access-Control-Allow-Headers");
		assertThat(allowHeaders).isNotNull();
		assertThat(allowHeaders.toLowerCase())
				.contains("content-type", "x-xsrf-token", "idempotency-key", "x-request-id");
		assertThat(allowHeaders).doesNotContain("*");
		String allowMethods = result.getResponse().getHeader("Access-Control-Allow-Methods");
		assertThat(allowMethods).isNotNull();
		assertThat(List.of(allowMethods.split("\\s*,\\s*")))
				.containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
	}
}
