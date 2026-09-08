package com.saga.be.integration.jira;

import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.repository.JiraIntegrationRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class JiraTeamTokenService {

	private final JiraIntegrationRepository integrations;
	private final TokenEncryptor encryptor;
	private final JiraOAuthClient oauth;
	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

	public JiraTeamTokenService(
			JiraIntegrationRepository integrations, TokenEncryptor encryptor, JiraOAuthClient oauth) {
		this.integrations = integrations;
		this.encryptor = encryptor;
		this.oauth = oauth;
	}

	@Transactional
	public String accessToken(JiraIntegration integration) {
		if (integration.getEncryptedAccessToken() != null
				&& integration.getTokenExpiresAt() != null
				&& integration.getTokenExpiresAt().isAfter(LocalDateTime.now().plusSeconds(60))) {
			return encryptor.decrypt(integration.getEncryptedAccessToken(), aad(integration));
		}
		if (integration.getEncryptedRefreshToken() == null || integration.getConnectedBy() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.FORBIDDEN, "Jira access token is not available.");
		}
		Object lock = locks.computeIfAbsent(integration.getId(), id -> new Object());
		synchronized (lock) {
			JiraIntegration locked = integrations.lockById(integration.getId()).orElse(integration);
			if (locked.getEncryptedAccessToken() != null
					&& locked.getTokenExpiresAt() != null
					&& locked.getTokenExpiresAt().isAfter(LocalDateTime.now().plusSeconds(60))) {
				return encryptor.decrypt(locked.getEncryptedAccessToken(), aad(locked));
			}
			String refresh = encryptor.decrypt(locked.getEncryptedRefreshToken(), aad(locked));
			JiraOAuthClient.TokenResponse tokens = oauth.refresh(refresh);
			String aad = aad(locked);
			if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
				locked.setEncryptedRefreshToken(encryptor.encrypt(tokens.refreshToken(), aad));
			}
			locked.setEncryptedAccessToken(encryptor.encrypt(tokens.accessToken(), aad));
			locked.setTokenExpiresAt(LocalDateTime.now().plusSeconds(Math.max(60, tokens.expiresInSeconds() - 60)));
			integrations.save(locked);
			return tokens.accessToken();
		}
	}

	private static String aad(JiraIntegration integration) {
		String userId = integration.getConnectedBy() == null ? "unknown" : integration.getConnectedBy().getId().toString();
		return TokenEncryptor.aad(integration.getId().toString(), "JIRA", userId);
	}
}
