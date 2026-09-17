package com.saga.be.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.persistence.TrackingPlatformTransactionManager;
import com.saga.be.repository.JiraIntegrationRepository;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class JiraTeamTokenServiceTest {

	@Mock
	private JiraIntegrationRepository integrations;
	@Mock
	private JiraOAuthClient oauth;

	private TokenEncryptor encryptor;
	private TrackingPlatformTransactionManager tm;
	private JiraTeamTokenService service;
	private UUID integrationId;
	private UUID userId;
	private String aad;
	private JiraIntegration row;

	@BeforeEach
	void setUp() {
		byte[] key = new byte[32];
		key[0] = 11;
		encryptor = new TokenEncryptor(Base64.getEncoder().encodeToString(key));
		tm = new TrackingPlatformTransactionManager();
		service = new JiraTeamTokenService(integrations, encryptor, oauth, tm);
		integrationId = UUID.randomUUID();
		userId = UUID.randomUUID();
		aad = TokenEncryptor.aad(integrationId.toString(), "JIRA", userId.toString());
		UserAccount connected = new UserAccount();
		connected.setId(userId);
		row = new JiraIntegration();
		row.setId(integrationId);
		row.setConnectedBy(connected);
		row.setConnectionStatus(IntegrationStatus.ACTIVE);
		row.setVersion(0L);
		row.setEncryptedAccessToken(encryptor.encrypt("access-live", aad));
		row.setEncryptedRefreshToken(encryptor.encrypt("refresh-live", aad));
		row.setTokenExpiresAt(LocalDateTime.now().plusMinutes(30));
		when(integrations.findById(integrationId)).thenReturn(Optional.of(row));
	}

	@AfterEach
	void clearTxFlag() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void validAccess_doesNotRefresh() {
		assertThat(service.accessToken(row)).isEqualTo("access-live");
		verify(oauth, never()).refresh(any());
		verify(integrations, never()).lockById(any());
	}

	@Test
	void expiredAccess_refreshesHttpOutsideTx_andPersistsInsideShortTx() {
		row.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
		when(oauth.refresh("refresh-live")).thenAnswer(inv -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive())
					.as("jira refresh HTTP must be outside JDBC TX")
					.isFalse();
			assertThat(tm.openCount()).isZero();
			return new JiraOAuthClient.TokenResponse("access-new", "refresh-new", 3600, "offline_access");
		});
		when(integrations.lockById(integrationId)).thenAnswer(inv -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive())
					.as("token persist must be inside short TX")
					.isTrue();
			return Optional.of(row);
		});
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.accessToken(row)).isEqualTo("access-new");
		assertThat(encryptor.decrypt(row.getEncryptedAccessToken(), aad)).isEqualTo("access-new");
		assertThat(encryptor.decrypt(row.getEncryptedRefreshToken(), aad)).isEqualTo("refresh-new");
		verify(oauth, times(1)).refresh("refresh-live");
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
	}

	@Test
	void persistAdoptsOtherReplicaFreshTokenAndDoesNotOverwrite() {
		row.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
		JiraIntegration alreadyRefreshed = new JiraIntegration();
		alreadyRefreshed.setId(integrationId);
		UserAccount connected = new UserAccount();
		connected.setId(userId);
		alreadyRefreshed.setConnectedBy(connected);
		alreadyRefreshed.setConnectionStatus(IntegrationStatus.ACTIVE);
		alreadyRefreshed.setVersion(1L);
		alreadyRefreshed.setEncryptedAccessToken(encryptor.encrypt("access-winner", aad));
		alreadyRefreshed.setEncryptedRefreshToken(encryptor.encrypt("refresh-winner", aad));
		alreadyRefreshed.setTokenExpiresAt(LocalDateTime.now().plusMinutes(30));
		when(integrations.lockById(integrationId)).thenReturn(Optional.of(alreadyRefreshed));

		assertThat(service.accessToken(row)).isEqualTo("access-winner");
		verify(oauth, never()).refresh(any());
		verify(integrations, never()).save(any());
		assertThat(encryptor.decrypt(alreadyRefreshed.getEncryptedAccessToken(), aad)).isEqualTo("access-winner");
	}

	@Test
	void overlappingHttp_persistCasAdoptsWinnerAndDoesNotOverwrite() {
		row.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
		JiraIntegration alreadyRefreshed = new JiraIntegration();
		alreadyRefreshed.setId(integrationId);
		UserAccount connected = new UserAccount();
		connected.setId(userId);
		alreadyRefreshed.setConnectedBy(connected);
		alreadyRefreshed.setConnectionStatus(IntegrationStatus.ACTIVE);
		alreadyRefreshed.setVersion(1L);
		alreadyRefreshed.setEncryptedAccessToken(encryptor.encrypt("access-winner", aad));
		alreadyRefreshed.setEncryptedRefreshToken(encryptor.encrypt("refresh-winner", aad));
		alreadyRefreshed.setTokenExpiresAt(LocalDateTime.now().plusMinutes(30));
		java.util.concurrent.atomic.AtomicInteger locks = new java.util.concurrent.atomic.AtomicInteger();
		when(integrations.lockById(integrationId)).thenAnswer(inv -> {
			if (locks.incrementAndGet() == 1) {
				return Optional.of(row);
			}
			return Optional.of(alreadyRefreshed);
		});
		when(oauth.refresh("refresh-live"))
				.thenReturn(new JiraOAuthClient.TokenResponse("access-loser", "refresh-loser", 3600, ""));

		assertThat(service.accessToken(row)).isEqualTo("access-winner");
		verify(oauth, times(1)).refresh("refresh-live");
		verify(integrations, never()).save(any());
	}

	@Test
	void missingRefresh_throwsRevokedWithoutHttp() {
		row.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
		row.setEncryptedRefreshToken(null);
		assertThatThrownBy(() -> service.accessToken(row))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_REVOKED);
		verify(oauth, never()).refresh(any());
	}
}
