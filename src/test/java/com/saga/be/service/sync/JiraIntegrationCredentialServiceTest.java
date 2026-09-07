package com.saga.be.service.sync;

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
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient.TokenResponse;
import com.saga.be.repository.JiraIntegrationRepository;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class JiraIntegrationCredentialServiceTest {

	@Mock
	private JiraIntegrationRepository integrations;
	@Mock
	private JiraOAuthClient jira;
	@Mock
	private PlatformTransactionManager transactionManager;

	private TokenEncryptor encryptor;
	private JiraIntegrationCredentialService service;
	private UUID projectId;
	private UUID integrationId;
	private UUID userId;
	private String aad;
	private JiraIntegration row;

	@BeforeEach
	void setUp() {
		byte[] key = new byte[32];
		key[0] = 11;
		encryptor = new TokenEncryptor(Base64.getEncoder().encodeToString(key));
		org.mockito.Mockito.lenient()
				.when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenAnswer(inv -> new SimpleTransactionStatus());
		service = new JiraIntegrationCredentialService(integrations, jira, encryptor, transactionManager);
		projectId = UUID.randomUUID();
		integrationId = UUID.randomUUID();
		userId = UUID.randomUUID();
		aad = TokenEncryptor.aad(integrationId.toString(), "JIRA", userId.toString());
		Project project = new Project();
		project.setId(projectId);
		UserAccount connected = new UserAccount();
		connected.setId(userId);
		row = new JiraIntegration();
		row.setId(integrationId);
		row.setProject(project);
		row.setConnectedBy(connected);
		row.setConnectionStatus(IntegrationStatus.ACTIVE);
		row.setEncryptedAccessToken(encryptor.encrypt("access-live", aad));
		row.setEncryptedRefreshToken(encryptor.encrypt("refresh-live", aad));
		row.setTokenExpiresAt(LocalDateTime.now().plusMinutes(30));
		when(integrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(row));
	}

	@Test
	void validAccess_doesNotRefresh() {
		String access = service.resolveAccessToken(projectId);
		assertThat(access).isEqualTo("access-live");
		verify(jira, never()).refresh(any());
	}

	@Test
	void expiredAccess_refreshesAndPersistsOutsideHttpTx() {
		row.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
		AtomicInteger openTx = new AtomicInteger();
		when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(inv -> {
			openTx.incrementAndGet();
			return new SimpleTransactionStatus();
		});
		org.mockito.Mockito.doAnswer(inv -> {
					openTx.decrementAndGet();
					return null;
				})
				.when(transactionManager)
				.commit(any());
		when(jira.refresh("refresh-live")).thenAnswer(inv -> {
			assertThat(openTx.get()).as("provider refresh HTTP must be outside JDBC TX").isZero();
			return new TokenResponse("access-new", "refresh-new", 3600, "offline_access");
		});
		when(integrations.lockById(integrationId)).thenAnswer(inv -> {
			assertThat(openTx.get()).as("persist must be inside short TX").isPositive();
			return Optional.of(row);
		});
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String access = service.resolveAccessToken(projectId);

		assertThat(access).isEqualTo("access-new");
		assertThat(row.getEncryptedAccessToken()).doesNotContain("access-new");
		assertThat(row.getEncryptedRefreshToken()).doesNotContain("refresh-new");
		assertThat(encryptor.decrypt(row.getEncryptedAccessToken(), aad)).isEqualTo("access-new");
		verify(jira, times(1)).refresh("refresh-live");
	}

	@Test
	void forceRefreshAfter401_refreshesEvenWhenExpiryLooksValid() {
		when(jira.refresh("refresh-live")).thenReturn(new TokenResponse("access-rotated", null, 3600, ""));
		when(integrations.lockById(integrationId)).thenReturn(Optional.of(row));
		when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String access = service.forceRefresh(projectId, "access-live");

		assertThat(access).isEqualTo("access-rotated");
		verify(jira, times(1)).refresh("refresh-live");
	}

	@Test
	void refreshRejected_throwsTypedFailure() {
		row.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
		when(jira.refresh("refresh-live"))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED,
						org.springframework.http.HttpStatus.BAD_GATEWAY,
						"denied"));

		assertThatThrownBy(() -> service.resolveAccessToken(projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED);
		verify(integrations, never()).lockById(any());
	}

	@Test
	void hasCredential_whenRefreshOnly() {
		row.setEncryptedAccessToken(null);
		assertThat(service.hasRefreshOrAccessCredential(projectId)).isTrue();
	}
}
