package com.saga.be.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Proves saga.auth.frontend-origins accepts a comma-separated multi-origin list (production
 * canonical custom domain + the Vercel default testing domain) purely via config, with no source
 * change, while still denying an arbitrary/malicious origin.
 */
@SpringBootTest(
		properties = "saga.auth.frontend-origins=https://saga.autos,https://saga-fe-ten.vercel.app")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsMultiOriginWebTest {

	private static final String CUSTOM_DOMAIN_ORIGIN = "https://saga.autos";
	private static final String VERCEL_DEFAULT_ORIGIN = "https://saga-fe-ten.vercel.app";
	private static final String DISALLOWED_ORIGIN = "https://evil.example";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private CorsConfigurationSource corsConfigurationSource;

	@Test
	void configuredOriginListContainsBothProductionOriginsAndOnlyThose() {
		HttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/me");
		CorsConfiguration config = corsConfigurationSource.getCorsConfiguration(request);
		assertThat(config).isNotNull();
		assertThat(config.getAllowedOrigins()).containsExactly(CUSTOM_DOMAIN_ORIGIN, VERCEL_DEFAULT_ORIGIN);
		assertThat(config.getAllowCredentials()).isTrue();
	}

	@Test
	void customDomainOriginPreflightAccepted() throws Exception {
		assertAccepted(CUSTOM_DOMAIN_ORIGIN);
	}

	@Test
	void vercelDefaultOriginPreflightAccepted() throws Exception {
		assertAccepted(VERCEL_DEFAULT_ORIGIN);
	}

	@Test
	void actualRequestFromVercelDefaultOriginCarriesCredentialedCorsHeaders() throws Exception {
		mockMvc.perform(get("/api/auth/csrf").header("Origin", VERCEL_DEFAULT_ORIGIN))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", VERCEL_DEFAULT_ORIGIN))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	@Test
	void disallowedOriginRemainsRejectedEvenWithMultiOriginAllowlist() throws Exception {
		mockMvc.perform(options("/api/auth/me")
						.header("Origin", DISALLOWED_ORIGIN)
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "content-type"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	private void assertAccepted(String origin) throws Exception {
		mockMvc.perform(options("/api/auth/me")
						.header("Origin", origin)
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "content-type, x-xsrf-token"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", origin))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}
}
