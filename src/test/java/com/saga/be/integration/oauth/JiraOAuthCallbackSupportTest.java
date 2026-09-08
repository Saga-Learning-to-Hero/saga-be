package com.saga.be.integration.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import org.junit.jupiter.api.Test;

class JiraOAuthCallbackSupportTest {

	@Test
	void accessDeniedThrowsCancelled() {
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> JiraOAuthCallbackSupport.requireAuthorizationCodeOrThrow(null, "access_denied"));
		assertEquals(IntegrationErrorCode.JIRA_OAUTH_CANCELLED, ex.getCode());
	}

	@Test
	void missingCodeWithoutErrorThrowsCallbackInvalid() {
		IntegrationException ex = assertThrows(
				IntegrationException.class, () -> JiraOAuthCallbackSupport.requireAuthorizationCodeOrThrow(null, null));
		assertEquals(IntegrationErrorCode.JIRA_OAUTH_CALLBACK_INVALID, ex.getCode());
	}

	@Test
	void otherProviderErrorThrowsCallbackInvalid() {
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> JiraOAuthCallbackSupport.requireAuthorizationCodeOrThrow(null, "server_error"));
		assertEquals(IntegrationErrorCode.JIRA_OAUTH_CALLBACK_INVALID, ex.getCode());
	}

	@Test
	void authorizationCodePasses() {
		JiraOAuthCallbackSupport.requireAuthorizationCodeOrThrow("auth-code", null);
		assertTrue(JiraOAuthCallbackSupport.isAccessDenied("access_denied"));
	}
}
