package com.saga.be.service.sync;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient.TokenResponse;
import com.saga.be.repository.JiraIntegrationRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves usable Jira access tokens for background sync. Refresh HTTP stays outside JDBC TX;
 * credential persistence uses a short TX.
 */
@Service
@Profile("!test")
public class JiraIntegrationCredentialService {

	private static final Logger log = LoggerFactory.getLogger(JiraIntegrationCredentialService.class);
	private static final long EXPIRY_SKEW_SECONDS = 60;

	private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();
	private final JiraIntegrationRepository integrations;
	private final JiraOAuthClient jira;
	private final TokenEncryptor encryptor;
	private final TransactionTemplate writes;

	public JiraIntegrationCredentialService(
			JiraIntegrationRepository integrations,
			JiraOAuthClient jira,
			TokenEncryptor encryptor,
			PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.jira = jira;
		this.encryptor = encryptor;
		this.writes = new TransactionTemplate(transactionManager);
	}

	public boolean hasRefreshOrAccessCredential(UUID projectId) {
		JiraIntegration row = integrations.findFetchedByProject_Id(projectId).orElse(null);
		return row != null
				&& row.getConnectionStatus() == IntegrationStatus.ACTIVE
				&& (hasText(row.getEncryptedAccessToken()) || hasText(row.getEncryptedRefreshToken()));
	}

	/**
	 * Prefer non-expired access token; otherwise refresh once via offline_access refresh token.
	 */
	public String resolveAccessToken(UUID projectId) {
		JiraIntegration integration = loadActive(projectId);
		String access = decryptAccess(integration);
		if (hasText(access) && !isExpired(integration.getTokenExpiresAt())) {
			return access;
		}
		if (!hasText(integration.getEncryptedRefreshToken())) {
			if (hasText(access)) {
				return access; // expiry unknown/null — try access; caller may refresh on 401
			}
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED,
					HttpStatus.BAD_GATEWAY,
					"Jira credentials are unavailable.");
		}
		return refreshUnderLock(projectId, true, null);
	}

	/** Always refresh using persisted refresh token. Provider HTTP outside JDBC TX. */
	public String forceRefresh(UUID projectId) {
		return refreshUnderLock(projectId, false, null);
	}

	/**
	 * Refresh after a rejected access token. Skips HTTP only when another thread already rotated
	 * away from {@code rejectedAccess}.
	 */
	public String forceRefresh(UUID projectId, String rejectedAccess) {
		return refreshUnderLock(projectId, false, rejectedAccess);
	}

	private String refreshUnderLock(UUID projectId, boolean skipIfFreshAccess, String rejectedAccess) {
		JiraIntegration snapshot = loadActive(projectId);
		UUID integrationId = snapshot.getId();
		Object lock = locks.computeIfAbsent(integrationId, id -> new Object());
		synchronized (lock) {
			JiraIntegration current = loadActive(projectId);
			String stillValid = decryptAccess(current);
			if (skipIfFreshAccess) {
				if (hasText(stillValid) && !isExpired(current.getTokenExpiresAt())) {
					return stillValid;
				}
			} else if (rejectedAccess != null
					&& hasText(stillValid)
					&& !stillValid.equals(rejectedAccess)
					&& !isExpired(current.getTokenExpiresAt())) {
				return stillValid;
			}
			UUID connectedById = current.getConnectedBy() == null ? null : current.getConnectedBy().getId();
			if (connectedById == null || !encryptor.isReady() || !hasText(current.getEncryptedRefreshToken())) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED,
						HttpStatus.BAD_GATEWAY,
						"Jira refresh credential is unavailable.");
			}
			String aad = TokenEncryptor.aad(integrationId.toString(), "JIRA", connectedById.toString());
			String refreshPlain = encryptor.decrypt(current.getEncryptedRefreshToken(), aad);
			TokenResponse tokens = jira.refresh(refreshPlain);
			String nextRefresh = tokens.refreshToken() == null || tokens.refreshToken().isBlank()
					? refreshPlain
					: tokens.refreshToken();
			String encryptedAccess = encryptor.encrypt(tokens.accessToken(), aad);
			String encryptedRefresh = encryptor.encrypt(nextRefresh, aad);
			LocalDateTime expiresAt =
					LocalDateTime.now().plusSeconds(Math.max(60, tokens.expiresInSeconds() - EXPIRY_SKEW_SECONDS));
			writes.executeWithoutResult(status -> {
				JiraIntegration row = integrations.lockById(integrationId).orElseThrow();
				row.setEncryptedAccessToken(encryptedAccess);
				row.setEncryptedRefreshToken(encryptedRefresh);
				row.setTokenExpiresAt(expiresAt);
				integrations.save(row);
			});
			log.info("jira credential refreshed projectId={} integrationIdPresent=true", projectId);
			return tokens.accessToken();
		}
	}

	private JiraIntegration loadActive(UUID projectId) {
		JiraIntegration integration = integrations.findFetchedByProject_Id(projectId).orElse(null);
		if (integration == null || integration.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.CONFLICT, "Jira is not active.");
		}
		return integration;
	}

	private String decryptAccess(JiraIntegration integration) {
		if (!hasText(integration.getEncryptedAccessToken()) || !encryptor.isReady()) {
			return null;
		}
		UUID connectedById = integration.getConnectedBy() == null ? null : integration.getConnectedBy().getId();
		if (connectedById == null) {
			return null;
		}
		try {
			return encryptor.decrypt(
					integration.getEncryptedAccessToken(),
					TokenEncryptor.aad(integration.getId().toString(), "JIRA", connectedById.toString()));
		} catch (RuntimeException ex) {
			log.warn(
					"jira access decrypt failed projectId={} type={}",
					projectIdOf(integration),
					ex.getClass().getSimpleName());
			return null;
		}
	}

	private static boolean isExpired(LocalDateTime expiresAt) {
		return expiresAt != null && !expiresAt.isAfter(LocalDateTime.now());
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static UUID projectIdOf(JiraIntegration integration) {
		return integration.getProject() == null ? null : integration.getProject().getId();
	}
}
