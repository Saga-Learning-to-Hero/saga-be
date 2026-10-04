package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.ai.TeamAiKeyDtos;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiTeamCredential;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AiTeamCredentialRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.notification.NotificationService;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TeamAiCredentialServiceTest {

	private final UUID projectId = UUID.randomUUID();
	private final UUID courseId = UUID.randomUUID();
	private final UUID leaderId = UUID.randomUUID();
	private final UUID memberId = UUID.randomUUID();

	private AiTeamCredentialRepository credentials;
	private ProjectDataAuthorization authorization;
	private TeamMemberRepository members;
	private AiCredentialResolver courseResolver;
	private NotificationService notifications;
	private AiCredentialCipher cipher;
	private AiCredentialTransportCipher transport;
	private TeamAiCredentialService service;
	private final AtomicReference<AiTeamCredential> stored = new AtomicReference<>();

	private static String key() {
		byte[] raw = new byte[32];
		new SecureRandom().nextBytes(raw);
		return Base64.getEncoder().encodeToString(raw);
	}

	@BeforeEach
	void setUp() {
		credentials = mock(AiTeamCredentialRepository.class);
		ProjectRepository projects = mock(ProjectRepository.class);
		UserAccountRepository users = mock(UserAccountRepository.class);
		authorization = mock(ProjectDataAuthorization.class);
		members = mock(TeamMemberRepository.class);
		courseResolver = mock(AiCredentialResolver.class);
		notifications = mock(NotificationService.class);
		PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
		when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		cipher = new AiCredentialCipher(key());
		transport = new AiCredentialTransportCipher(key());
		Project project = new Project();
		project.setId(projectId);
		Course course = new Course();
		course.setId(courseId);
		project.setCourse(course);
		when(projects.findById(projectId)).thenReturn(Optional.of(project));
		UserAccount leader = new UserAccount();
		leader.setId(leaderId);
		when(users.findById(leaderId)).thenReturn(Optional.of(leader));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, leaderId)).thenReturn(Optional.of(RoleInTeam.LEADER));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, memberId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		doThrow(new IntegrationException(IntegrationErrorCode.NOT_TEAM_LEADER, HttpStatus.FORBIDDEN, "leader only"))
				.when(authorization).requireStudentLeader(memberId, projectId);
		when(credentials.findByProject_Id(projectId)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
		when(credentials.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get()).filter(c -> c.getId().equals(inv.getArgument(0))));
		when(credentials.saveAndFlush(any())).thenAnswer(inv -> {
			AiTeamCredential row = inv.getArgument(0);
			if (row.getId() == null) row.setId(UUID.randomUUID());
			stored.set(row);
			return row;
		});
		lenient().when(credentials.save(any())).thenAnswer(inv -> { stored.set(inv.getArgument(0)); return inv.getArgument(0); });
		service = new TeamAiCredentialService(credentials, projects, users, authorization, cipher, transport, new AiModelCatalog(),
				courseResolver, mock(TeamByProjectRepository.class), members, tx);
		service.setNotifications(notifications);
	}

	private TeamAiKeyDtos.Status saveGemini(String rawKey) {
		return service.save(leaderId, projectId, new TeamAiKeyDtos.SaveRequest("GEMINI", "gemini-3.6-flash", rawKey));
	}

	// ---------------- save / revoke / status

	@Test
	void leaderSavesAKey_itIsEncryptedUnverifiedAndOnlyItsLastFourIsShownToTheLeader() {
		TeamAiKeyDtos.Status status = saveGemini("  AIza-team-secret-1234  ");

		AiTeamCredential row = stored.get();
		assertThat(row.getEncryptedSecret()).doesNotContain("AIza-team-secret-1234");
		assertThat(cipher.decrypt(row.getEncryptedSecret(), row.getEncryptionNonce(), row.getEncryptionKeyVersion())).isEqualTo("AIza-team-secret-1234");
		assertThat(row.getStatus()).isEqualTo(AiCredentialStatus.UNVERIFIED);
		assertThat(row.getProvider()).isEqualTo(AiProvider.GEMINI);
		assertThat(row.getModelId()).isEqualTo("gemini-3.6-flash");
		assertThat(status.configured()).isTrue();
		assertThat(status.lastFour()).isEqualTo("1234");
		assertThat(status.canManage()).isTrue();
		assertThat(status.toString()).doesNotContain("AIza-team-secret");

		TeamAiKeyDtos.Status asMember = service.status(memberId, projectId);
		assertThat(asMember.configured()).isTrue();
		assertThat(asMember.lastFour()).isNull();
		assertThat(asMember.canManage()).isFalse();
	}

	@Test
	void onlyTheLeaderMaySaveOrRemove() {
		assertThatThrownBy(() -> service.save(memberId, projectId, new TeamAiKeyDtos.SaveRequest("GEMINI", "gemini-3.6-flash", "k")))
				.isInstanceOf(IntegrationException.class);
		doThrow(new IntegrationException(IntegrationErrorCode.NOT_TEAM_LEADER, HttpStatus.FORBIDDEN, "leader only"))
				.when(authorization).requireStudentLeader(memberId, projectId);
		assertThatThrownBy(() -> service.revoke(memberId, projectId)).isInstanceOf(IntegrationException.class);
		verify(credentials, never()).saveAndFlush(any());
	}

	@Test
	void openRouterIsNotOfferedNorAccepted() {
		assertThatThrownBy(() -> service.save(leaderId, projectId, new TeamAiKeyDtos.SaveRequest("OPENROUTER", "openrouter/free", "k")))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.AI_PROVIDER_NOT_SUPPORTED))
				.hasMessageContaining("Gemini, OpenAI hoặc Cohere");
		assertThat(service.options()).isNotEmpty()
				.allSatisfy(option -> assertThat(option.provider()).isIn("GEMINI", "OPENAI", "COHERE"));
		assertThat(service.options()).extracting(TeamAiKeyDtos.ModelOption::provider).contains("GEMINI", "OPENAI", "COHERE");
	}

	@Test
	void aModelOfAnotherProviderOrABlankKeyIsRejected() {
		assertThatThrownBy(() -> service.save(leaderId, projectId, new TeamAiKeyDtos.SaveRequest("OPENAI", "gemini-3.6-flash", "k")))
				.isInstanceOf(IntegrationException.class);
		assertThatThrownBy(() -> saveGemini("   "))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST));
		assertThatThrownBy(() -> service.save(leaderId, projectId, null)).isInstanceOf(IntegrationException.class);
		verify(credentials, never()).saveAndFlush(any());
	}

	@Test
	void replacingTheKeyResetsStatusAndError() {
		saveGemini("first-key-0001");
		stored.get().setStatus(AiCredentialStatus.INVALID);
		stored.get().setLastErrorCode("AI_PROVIDER_AUTH_FAILED");
		UUID id = stored.get().getId();

		saveGemini("second-key-0002");

		assertThat(stored.get().getId()).isEqualTo(id);
		assertThat(stored.get().getStatus()).isEqualTo(AiCredentialStatus.UNVERIFIED);
		assertThat(stored.get().getLastErrorCode()).isNull();
		assertThat(stored.get().getLastFour()).isEqualTo("0002");
	}

	@Test
	void revokeClearsTheSecretMaterialItself() {
		saveGemini("secret-key-9999");
		TeamAiKeyDtos.Status status = service.revoke(leaderId, projectId);
		assertThat(stored.get().getStatus()).isEqualTo(AiCredentialStatus.REVOKED);
		assertThat(stored.get().getEncryptedSecret()).isEmpty();
		assertThat(stored.get().getEncryptionNonce()).isEmpty();
		assertThat(status.configured()).isFalse();
		assertThat(service.usableKey(projectId)).isEmpty();
	}

	@Test
	void serverWithoutTheMasterKeyRefusesToStoreAKey() {
		service = new TeamAiCredentialService(credentials, mock(ProjectRepository.class), mock(UserAccountRepository.class), authorization,
				new AiCredentialCipher(""), transport, new AiModelCatalog(), courseResolver, mock(TeamByProjectRepository.class), members,
				mock(PlatformTransactionManager.class));
		assertThatThrownBy(() -> saveGemini("k-1234"))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED));
	}

	// ---------------- system use

	@Test
	void usableKeyExcludesInvalidAndRevoked_andCarriesTheTeamBinding() {
		saveGemini("key-ok-1111");
		var ref = service.usableKey(projectId).orElseThrow();
		assertThat(ref.binding().provider()).isEqualTo(AiProvider.GEMINI);
		assertThat(ref.identity()).startsWith("TEAM|").endsWith("@GEMINI:gemini-3.6-flash");

		stored.get().setStatus(AiCredentialStatus.DEGRADED);
		assertThat(service.usableKey(projectId)).isPresent();
		stored.get().setStatus(AiCredentialStatus.INVALID);
		assertThat(service.usableKey(projectId)).isEmpty();
	}

	@Test
	void aStoredModelThatLeftTheCatalogIsNotUsable() {
		saveGemini("key-ok-1111");
		stored.get().setModelId("gemini-0.1-retired");
		assertThat(service.usableKey(projectId)).isEmpty();
	}

	@Test
	void theEnvelopeIsBuiltOnlyForTheOwningProject() {
		saveGemini("key-ok-1111");
		UUID id = stored.get().getId();
		assertThat(service.buildEnvelope(id, projectId)).isNotNull();
		assertThatThrownBy(() -> service.buildEnvelope(id, UUID.randomUUID())).isInstanceOf(AiCredentialCryptoException.class);
		service.revoke(leaderId, projectId);
		assertThatThrownBy(() -> service.buildEnvelope(id, projectId)).isInstanceOf(AiCredentialCryptoException.class);
	}

	@Test
	void authFailureMarksInvalidAndTellsTheLeaderOnce_quotaMarksDegraded() {
		saveGemini("key-ok-1111");
		UUID id = stored.get().getId();
		UUID leader = UUID.randomUUID();
		service = org.mockito.Mockito.spy(service);
		org.mockito.Mockito.doReturn(Optional.of(leader)).when(service).leaderUserId(projectId);

		service.markDegraded(id, "AI_PROVIDER_QUOTA_EXHAUSTED");
		assertThat(stored.get().getStatus()).isEqualTo(AiCredentialStatus.DEGRADED);
		assertThat(stored.get().getLastErrorCode()).isEqualTo("AI_PROVIDER_QUOTA_EXHAUSTED");

		service.markInvalid(id, "AI_PROVIDER_AUTH_FAILED");
		assertThat(stored.get().getStatus()).isEqualTo(AiCredentialStatus.INVALID);
		verify(notifications).createNotification(eq(leader), eq(NotificationType.WARNING), eq("Key AI của nhóm: Key AI không hợp lệ"),
				anyString(), isNull(), eq("team-ai-key:" + id + ":" + stored.get().getFingerprint() + ":AI_PROVIDER_AUTH_FAILED"));
		verify(notifications).createNotification(eq(leader), eq(NotificationType.WARNING), eq("Key AI của nhóm: Key AI đã hết hạn mức"),
				anyString(), isNull(), anyString());

		service.markSuccessful(id);
		assertThat(stored.get().getStatus()).isEqualTo(AiCredentialStatus.ACTIVE);
		assertThat(stored.get().getLastErrorCode()).isNull();
		assertThat(stored.get().getLastSuccessfulUseAt()).isNotNull();
	}

	@Test
	void aNotificationFailureNeverBlocksTheStatusChange() {
		saveGemini("key-ok-1111");
		UUID id = stored.get().getId();
		service = org.mockito.Mockito.spy(service);
		org.mockito.Mockito.doReturn(Optional.of(UUID.randomUUID())).when(service).leaderUserId(projectId);
		when(notifications.createNotification(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("down"));

		service.markInvalid(id, "AI_PROVIDER_AUTH_FAILED");

		assertThat(stored.get().getStatus()).isEqualTo(AiCredentialStatus.INVALID);
	}

	@Test
	void aRevokedKeyIsNeverRevivedByARunThatFinishesLate() {
		saveGemini("key-ok-1111");
		UUID id = stored.get().getId();
		service.revoke(leaderId, projectId);
		service.markSuccessful(id);
		service.markInvalid(id, "AI_PROVIDER_AUTH_FAILED");
		assertThat(stored.get().getStatus()).isEqualTo(AiCredentialStatus.REVOKED);
		verify(notifications, never()).createNotification(any(), any(), any(), any(), any(), any());
	}

	@Test
	void courseKeyChecksNeverThrow_aResolverFailureMeansNoCourseKey() {
		when(courseResolver.resolve(eq(courseId), any(), any(), eq(AiInvocationOrigin.AUTOMATION)))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, UUID.randomUUID(), "fp"));
		assertThat(service.courseFallbackAvailable(courseId)).isTrue();

		when(courseResolver.resolve(eq(courseId), any(), any(), eq(AiInvocationOrigin.USER_REQUEST)))
				.thenThrow(new IntegrationException(IntegrationErrorCode.AI_MODEL_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, "gone"));
		assertThat(service.courseKeyUsable(courseId, AiInvocationOrigin.USER_REQUEST)).isFalse();
		assertThat(service.courseKeyUsable(null, AiInvocationOrigin.USER_REQUEST)).isFalse();

		when(courseResolver.resolve(eq(courseId), any(), any(), eq(AiInvocationOrigin.AUTOMATION))).thenReturn(AiCredentialResolver.Resolution.UNAVAILABLE);
		assertThat(service.courseFallbackAvailable(courseId)).isFalse();
		assertThat(service.status(memberId, projectId).courseFallbackAvailable()).isFalse();
		verify(courseResolver, times(4)).resolve(any(), any(), any(), any());
	}
}
