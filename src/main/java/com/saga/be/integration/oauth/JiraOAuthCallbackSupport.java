package com.saga.be.integration.oauth;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Shared success/denial gate for Atlassian OAuth callbacks after state has been validated.
 * Never reflects {@code error_description} into redirects.
 */
public final class JiraOAuthCallbackSupport {

	private JiraOAuthCallbackSupport() {}

	/**
	 * After OAuth state is consumed: either allow the authorization code success path, or throw a
	 * typed failure (cancel / malformed callback). Does not perform token exchange.
	 */
	public static void requireAuthorizationCodeOrThrow(String code, String error) {
		if (StringUtils.hasText(error)) {
			if (isAccessDenied(error)) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_OAUTH_CANCELLED,
						HttpStatus.BAD_REQUEST,
						"Jira authorization was cancelled.");
			}
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_OAUTH_CALLBACK_INVALID,
					HttpStatus.BAD_REQUEST,
					"Jira authorization failed.");
		}
		if (!StringUtils.hasText(code)) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_OAUTH_CALLBACK_INVALID,
					HttpStatus.BAD_REQUEST,
					"Jira authorization code is missing.");
		}
	}

	public static boolean isAccessDenied(String error) {
		return StringUtils.hasText(error) && "access_denied".equalsIgnoreCase(error.trim());
	}
}
