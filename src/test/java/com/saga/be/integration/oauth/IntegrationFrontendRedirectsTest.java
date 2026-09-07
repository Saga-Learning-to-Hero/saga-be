package com.saga.be.integration.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class IntegrationFrontendRedirectsTest {

	@Test
	void safeRelativeReturnPathResolvesAgainstFrontendSuccessOrigin() {
		assertEquals(
				"http://localhost:3000/projects/123/integrations",
				IntegrationFrontendRedirects.successLocation(
						"http://localhost:3000/integrations/success", "/projects/123/integrations"));
	}

	@Test
	void omittedReturnPathUsesSuccessUrlExactly() {
		assertEquals(
				"http://localhost:3000/integrations/success",
				IntegrationFrontendRedirects.successLocation("http://localhost:3000/integrations/success", null));
	}

	@Test
	void productionFrontendOriginIsUsedInsteadOfBackendHost() {
		assertEquals(
				"https://app.saga.vn/projects/123",
				IntegrationFrontendRedirects.successLocation(
						"https://app.saga.vn/integrations/success", "/projects/123"));
	}

	@Test
	void frontendOriginPreservesExplicitPort() {
		assertEquals(
				"http://localhost:3000/foo/bar",
				IntegrationFrontendRedirects.successLocation(
						"http://localhost:3000/integrations/success", "/foo/bar"));
		assertEquals(
				"https://app.example.com/foo/bar",
				IntegrationFrontendRedirects.successLocation("https://app.example.com/integrations/success", "/foo/bar"));
	}

	@Test
	void unsafeReturnPathsFallBackToConfiguredSuccessUrl() {
		String success = "http://localhost:3000/integrations/success";
		assertEquals(success, IntegrationFrontendRedirects.successLocation(success, "//evil.example"));
		assertEquals(success, IntegrationFrontendRedirects.successLocation(success, "https://evil.example/x"));
		assertEquals(success, IntegrationFrontendRedirects.successLocation(success, "\\evil"));
		assertEquals(success, IntegrationFrontendRedirects.successLocation(success, "projects"));
		assertNull(IntegrationFrontendRedirects.safeReturnPath("//evil.example"));
		assertNull(IntegrationFrontendRedirects.safeReturnPath("https://evil.example/x"));
		assertNull(IntegrationFrontendRedirects.safeReturnPath("\\evil"));
	}

	@Test
	void backendPublicBaseUrlIsNotUsedForFrontendDestination() {
		assertEquals(
				"http://localhost:3000/integrations/success",
				IntegrationFrontendRedirects.successLocation(
						"http://localhost:3000/integrations/success", null));
		assertEquals(
				"http://localhost:3000/integrations/success",
				IntegrationFrontendRedirects.successLocation(
						"http://localhost:3000/integrations/success", "/integrations/success"));
	}

	@Test
	void failureLocationAppendsSafeErrorCodeOnly() {
		assertEquals(
				"http://localhost:3000/integrations/failure?code=OAUTH_STATE_EXPIRED",
				IntegrationFrontendRedirects.failureLocation(
						"http://localhost:3000/integrations/failure", IntegrationErrorCode.OAUTH_STATE_EXPIRED));
		ResponseEntity<Void> response = IntegrationFrontendRedirects.failure(
				"http://localhost:3000/integrations/failure",
				new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_EXPIRED, HttpStatus.BAD_REQUEST, "secret message"));
		assertEquals(302, response.getStatusCode().value());
		assertEquals(
				"http://localhost:3000/integrations/failure?code=OAUTH_STATE_EXPIRED",
				response.getHeaders().getFirst("Location"));
		assertEquals(false, String.valueOf(response.getHeaders().getFirst("Location")).contains("secret"));
	}
}
