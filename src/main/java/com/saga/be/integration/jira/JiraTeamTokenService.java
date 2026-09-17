package com.saga.be.integration.jira;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.persistence.JdbcTransactionGuard;
import com.saga.be.repository.JiraIntegrationRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a usable Jira access token. Provider refresh HTTP never runs while a JDBC transaction is
 * open.
 *
 * <p>Atlassian 3LO uses <em>rotating</em> refresh tokens ({@code offline_access}): each successful
 * {@code grant_type=refresh_token} returns a new refresh token and disables the one that was used.
 * Concurrent reuse of the same parent refresh token is tolerated for a 10-minute leeway (breach
 * detection is suppressed) so overlapping HTTP is provider-legal. After that window, reuse can
 * revoke the token family.
 *
 * <p>Multi-instance algorithm (no Java {@code synchronized}, no Redis, no extra column):
 *
 * <ol>
 *   <li>short TX: snapshot via {@code findById}
 *   <li>if still fresh, return
 *   <li>short TX: {@code lockById} and adopt if another replica already persisted a fresh pair
 *       (skips HTTP when we lost the race before calling Atlassian)
 *   <li>HTTP refresh with no JDBC TX
 *   <li>short TX: {@code lockById}; persist only if the encrypted refresh ciphertext is still the
 *       snapshot parent (CAS). If another replica already rotated, adopt their tokens and do not
 *       overwrite.
 * </ol>
 *
 * DB-winner tokens remain the unused child of the shared parent RT, which Atlassian keeps valid
 * inside the documented leeway. Provider rotation itself is not simulated in tests.
 */
@Service
@Profile("!test")
public class JiraTeamTokenService {

	private static final Logger log = LoggerFactory.getLogger(JiraTeamTokenService.class);
	private static final long EXPIRY_SKEW_SECONDS = 60;

	private final JiraIntegrationRepository integrations;
	private final TokenEncryptor encryptor;
	private final JiraOAuthClient oauth;
	private final TransactionTemplate writes;

	public JiraTeamTokenService(
			JiraIntegrationRepository integrations, TokenEncryptor encryptor, JiraOAuthClient oauth) {
		this(integrations, encryptor, oauth, null);
	}

	@Autowired
	public JiraTeamTokenService(
			JiraIntegrationRepository integrations,
			TokenEncryptor encryptor,
			JiraOAuthClient oauth,
			PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.encryptor = encryptor;
		this.oauth = oauth;
		this.writes = transactionManager == null ? null : new TransactionTemplate(transactionManager);
	}

	public String accessToken(JiraIntegration integration) {
		if (integration == null || integration.getId() == null) {
			throw revoked();
		}
		UUID integrationId = integration.getId();
		TokenSnapshot snapshot = readSnapshot(integrationId);
		String live = decryptIfFresh(snapshot);
		if (live != null) {
			return live;
		}
		if (snapshot.encryptedRefresh() == null
				|| snapshot.encryptedRefresh().isBlank()
				|| snapshot.connectedById() == null) {
			throw revoked();
		}
		return refreshAndPersist(snapshot);
	}

	private String refreshAndPersist(TokenSnapshot snapshot) {
		String already = adoptIfFreshLocked(snapshot.integrationId());
		if (already != null) {
			return already;
		}
		JdbcTransactionGuard.requireInactive("jira token refresh");
		String aad = TokenEncryptor.aad(
				snapshot.integrationId().toString(), "JIRA", snapshot.connectedById().toString());
		String refreshPlain = encryptor.decrypt(snapshot.encryptedRefresh(), aad);
		JiraOAuthClient.TokenResponse tokens = oauth.refresh(refreshPlain);
		String nextRefresh = tokens.refreshToken() == null || tokens.refreshToken().isBlank()
				? refreshPlain
				: tokens.refreshToken();
		String encryptedAccess = encryptor.encrypt(tokens.accessToken(), aad);
		String encryptedRefresh = encryptor.encrypt(nextRefresh, aad);
		LocalDateTime expiresAt =
				LocalDateTime.now().plusSeconds(Math.max(60, tokens.expiresInSeconds() - EXPIRY_SKEW_SECONDS));
		try {
			String persisted = persistRefreshed(
					snapshot, encryptedAccess, encryptedRefresh, expiresAt, tokens.accessToken(), aad);
			if (persisted != null) {
				return persisted;
			}
		} catch (ObjectOptimisticLockingFailureException ex) {
			log.info("jira token refresh lost race integrationIdPresent=true");
		}
		TokenSnapshot latest = readSnapshot(snapshot.integrationId());
		String adopted = decryptIfFresh(latest);
		if (adopted != null) {
			return adopted;
		}
		throw new IntegrationException(
				IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED,
				HttpStatus.BAD_GATEWAY,
				"Jira access token could not be refreshed.");
	}

	private String adoptIfFreshLocked(UUID integrationId) {
		return inTx(() -> {
			JiraIntegration row = integrations.lockById(integrationId).orElseThrow(JiraTeamTokenService::revoked);
			if (row.getConnectionStatus() == IntegrationStatus.REVOKED) {
				throw revoked();
			}
			if (row.getConnectedBy() != null) {
				row.getConnectedBy().getId();
			}
			return decryptIfFresh(snapshotOf(row));
		});
	}

	private String persistRefreshed(
			TokenSnapshot snapshot,
			String encryptedAccess,
			String encryptedRefresh,
			LocalDateTime expiresAt,
			String plaintextAccess,
			String snapshotAad) {
		return inTx(() -> {
			JiraIntegration row = integrations.lockById(snapshot.integrationId()).orElseThrow(JiraTeamTokenService::revoked);
			if (row.getConnectionStatus() == IntegrationStatus.REVOKED) {
				throw revoked();
			}
			UUID connectedById = row.getConnectedBy() == null ? null : row.getConnectedBy().getId();
			if (connectedById == null || !connectedById.equals(snapshot.connectedById())) {
				throw revoked();
			}
			TokenSnapshot current = snapshotOf(row);
			String alreadyFresh = decryptIfFresh(current);
			if (alreadyFresh != null) {
				return alreadyFresh;
			}
			if (!java.util.Objects.equals(row.getEncryptedRefreshToken(), snapshot.encryptedRefresh())) {
				return decryptIfFresh(current);
			}
			String aad = TokenEncryptor.aad(row.getId().toString(), "JIRA", connectedById.toString());
			if (!aad.equals(snapshotAad)) {
				throw revoked();
			}
			row.setEncryptedAccessToken(encryptedAccess);
			row.setEncryptedRefreshToken(encryptedRefresh);
			row.setTokenExpiresAt(expiresAt);
			integrations.save(row);
			return plaintextAccess;
		});
	}

	private TokenSnapshot readSnapshot(UUID integrationId) {
		return inTx(() -> {
			JiraIntegration row = integrations.findById(integrationId).orElseThrow(JiraTeamTokenService::revoked);
			if (row.getConnectedBy() != null) {
				row.getConnectedBy().getId();
			}
			return snapshotOf(row);
		});
	}

	private <T> T inTx(java.util.function.Supplier<T> action) {
		if (writes == null) {
			return action.get();
		}
		return writes.execute(status -> action.get());
	}

	private String decryptIfFresh(TokenSnapshot snapshot) {
		if (snapshot.encryptedAccess() == null
				|| snapshot.encryptedAccess().isBlank()
				|| snapshot.connectedById() == null
				|| !isFresh(snapshot.expiresAt())) {
			return null;
		}
		String aad = TokenEncryptor.aad(
				snapshot.integrationId().toString(), "JIRA", snapshot.connectedById().toString());
		return encryptor.decrypt(snapshot.encryptedAccess(), aad);
	}

	private static boolean isFresh(LocalDateTime expiresAt) {
		return expiresAt != null && expiresAt.isAfter(LocalDateTime.now().plusSeconds(EXPIRY_SKEW_SECONDS));
	}

	private static TokenSnapshot snapshotOf(JiraIntegration row) {
		UUID connectedById = row.getConnectedBy() == null ? null : row.getConnectedBy().getId();
		return new TokenSnapshot(
				row.getId(),
				row.getVersion(),
				row.getEncryptedAccessToken(),
				row.getEncryptedRefreshToken(),
				row.getTokenExpiresAt(),
				connectedById,
				row.getConnectionStatus());
	}

	private static IntegrationException revoked() {
		return new IntegrationException(
				IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.FORBIDDEN, "Jira access token is not available.");
	}

	private record TokenSnapshot(
			UUID integrationId,
			Long version,
			String encryptedAccess,
			String encryptedRefresh,
			LocalDateTime expiresAt,
			UUID connectedById,
			IntegrationStatus connectionStatus) {}
}
