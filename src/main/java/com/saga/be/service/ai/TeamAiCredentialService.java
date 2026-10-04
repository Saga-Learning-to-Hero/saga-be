package com.saga.be.service.ai;

import com.saga.be.ai.AiProviderBinding;
import com.saga.be.dto.ai.TeamAiKeyDtos;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiTeamCredential;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.enums.EnrollmentStatus;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A team's own AI key for commit reviews. Only the team leader saves or removes it; every member
 * (and the lecturer) sees whether one is set. The key is encrypted like a course key, never
 * returned, and decrypted only right before one provider call ({@link #buildEnvelope}).
 *
 * <p>Teams without a key are reviewed with the course key only when the lecturer turned on the
 * course's AI automation and a course key exists ({@link #courseFallbackAvailable}).
 */
@Service
@Profile("!test")
public class TeamAiCredentialService {

	private static final Logger log = LoggerFactory.getLogger(TeamAiCredentialService.class);
	/** OpenRouter is not offered for team keys. */
	static final Set<AiProvider> TEAM_PROVIDERS = Set.of(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.COHERE);

	private final AiTeamCredentialRepository credentials;
	private final ProjectRepository projects;
	private final UserAccountRepository users;
	private final ProjectDataAuthorization authorization;
	private final AiCredentialCipher cipher;
	private final AiCredentialTransportCipher transport;
	private final AiModelCatalog catalog;
	private final AiCredentialResolver courseResolver;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	/** Course-key checks run in their own read transaction: a resolver failure (e.g. a course model
	 * that left the catalog) must never mark the caller's transaction (a commit list) rollback-only. */
	private final TransactionTemplate isolated;
	private NotificationService notifications;
	private ApplicationEventPublisher events;

	/** First time this project has a usable team key (new, or replacing a revoked one). */
	public record TeamKeyFirstSaved(UUID userId, UUID projectId) {}

	public TeamAiCredentialService(
			AiTeamCredentialRepository credentials,
			ProjectRepository projects,
			UserAccountRepository users,
			ProjectDataAuthorization authorization,
			AiCredentialCipher cipher,
			AiCredentialTransportCipher transport,
			AiModelCatalog catalog,
			AiCredentialResolver courseResolver,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			PlatformTransactionManager transactionManager) {
		this.credentials = credentials;
		this.projects = projects;
		this.users = users;
		this.authorization = authorization;
		this.cipher = cipher;
		this.transport = transport;
		this.catalog = catalog;
		this.courseResolver = courseResolver;
		this.teams = teams;
		this.members = members;
		this.isolated = new TransactionTemplate(transactionManager);
		this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.isolated.setReadOnly(true);
	}

	@Autowired(required = false)
	public void setNotifications(NotificationService notifications) {
		this.notifications = notifications;
	}

	@Autowired(required = false)
	public void setEvents(ApplicationEventPublisher events) {
		this.events = events;
	}

	/** A usable team key (not INVALID / REVOKED): metadata only, never key material. */
	public record TeamKeyRef(UUID id, String fingerprint, AiProviderBinding binding) {
		public String identity() { return "TEAM|" + fingerprint + "@" + binding.identity(); }
	}

	// ---------------------------------------------------------------- people-facing

	@Transactional(readOnly = true)
	public TeamAiKeyDtos.Status status(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		boolean leader = isLeader(userId, projectId);
		Project project = projects.findById(projectId).orElseThrow();
		UUID courseId = project.getCourse() == null ? null : project.getCourse().getId();
		AiTeamCredential row = credentials.findByProject_Id(projectId)
				.filter(c -> c.getStatus() != AiCredentialStatus.REVOKED)
				.orElse(null);
		return new TeamAiKeyDtos.Status(
				row != null,
				row == null ? null : row.getProvider().name(),
				row == null ? null : row.getModelId(),
				row == null ? null : row.getStatus().name(),
				row == null || !leader ? null : row.getLastFour(),
				row == null ? null : row.getLastErrorCode(),
				row == null ? null : AiFailureMessages.describe(row.getLastErrorCode()),
				row == null ? null : row.getLastSuccessfulUseAt(),
				row == null ? null : row.getUpdatedAt(),
				leader,
				courseFallbackAvailable(projectId, courseId),
				options());
	}

	@Transactional
	public TeamAiKeyDtos.Status save(UUID userId, UUID projectId, TeamAiKeyDtos.SaveRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		if (request == null) throw invalidRequest("Thiếu thông tin key AI.");
		AiProvider provider = AiProvider.parse(request.provider())
				.filter(TEAM_PROVIDERS::contains)
				.orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_PROVIDER_NOT_SUPPORTED, HttpStatus.BAD_REQUEST,
						"Nhà cung cấp AI không được hỗ trợ cho key nhóm. Chọn Gemini, OpenAI hoặc Cohere."));
		AiProviderBinding binding = catalog.requireBindable(provider.name(), request.modelId());
		String rawKey = request.apiKey() == null ? "" : request.apiKey().trim();
		if (rawKey.isEmpty()) throw invalidRequest("Key AI không được để trống.");
		if (rawKey.length() > 512) throw invalidRequest("Key AI quá dài.");
		if (!cipher.isConfigured()) {
			throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
					"Máy chủ chưa cấu hình mã hoá key AI. Liên hệ quản trị viên.");
		}
		Project project = projects.findById(projectId).orElseThrow();
		UserAccount actor = users.findById(userId).orElseThrow();
		AiCredentialCipher.Encrypted encrypted = cipher.encrypt(rawKey);
		AiTeamCredential existing = credentials.findByProject_Id(projectId).orElse(null);
		boolean firstKey = existing == null
				|| existing.getStatus() == AiCredentialStatus.REVOKED
				|| existing.getEncryptedSecret() == null
				|| existing.getEncryptedSecret().isBlank();
		AiTeamCredential row = existing == null ? new AiTeamCredential() : existing;
		row.setProject(project);
		row.setProvider(binding.provider());
		row.setModelId(binding.modelId());
		row.setEncryptedSecret(encrypted.ciphertextBase64());
		row.setEncryptionNonce(encrypted.nonceBase64());
		row.setEncryptionKeyVersion(encrypted.keyVersion());
		row.setFingerprint(fingerprint(rawKey));
		row.setLastFour(rawKey.length() >= 4 ? rawKey.substring(rawKey.length() - 4) : rawKey);
		row.setStatus(AiCredentialStatus.UNVERIFIED);
		row.setLastErrorCode(null);
		row.setCreatedBy(actor);
		row.setRevokedAt(null);
		credentials.saveAndFlush(row);
		if (firstKey && events != null) {
			events.publishEvent(new TeamKeyFirstSaved(userId, projectId));
		}
		return status(userId, projectId);
	}

	/** Clears the secret material itself, not only the status. */
	@Transactional
	public TeamAiKeyDtos.Status revoke(UUID userId, UUID projectId) {
		authorization.requireStudentLeader(userId, projectId);
		credentials.findByProject_Id(projectId).ifPresent(row -> {
			row.setStatus(AiCredentialStatus.REVOKED);
			row.setEncryptedSecret("");
			row.setEncryptionNonce("");
			row.setRevokedAt(LocalDateTime.now());
			credentials.save(row);
		});
		return status(userId, projectId);
	}

	/** Providers and models a leader may pick (OpenRouter excluded). */
	public List<TeamAiKeyDtos.ModelOption> options() {
		return catalog.models().stream()
				.filter(model -> TEAM_PROVIDERS.contains(model.provider()) && model.supportsEveryAnalysisType())
				.map(model -> new TeamAiKeyDtos.ModelOption(model.provider().name(), model.modelId(), model.displayName(),
						model.freeTierEligible(), model.recommendedForAutomation()))
				.toList();
	}

	// ---------------------------------------------------------------- system-facing

	@Transactional(readOnly = true)
	public Optional<TeamKeyRef> usableKey(UUID projectId) {
		if (projectId == null) return Optional.empty();
		return credentials.findByProject_Id(projectId)
				.filter(c -> c.getStatus() != AiCredentialStatus.INVALID && c.getStatus() != AiCredentialStatus.REVOKED)
				.flatMap(c -> {
					try {
						return Optional.of(new TeamKeyRef(c.getId(), c.getFingerprint(),
								catalog.requireRuntimeCompatible(new AiProviderBinding(c.getProvider(), c.getModelId()))));
					} catch (IntegrationException ex) {
						// The stored model left the catalog: the leader must pick another one.
						return Optional.empty();
					}
				});
	}

	/** Without its own key this team is reviewed automatically with the course key: the lecturer picked
	 * the team, kept course AI automation on, and a course key exists. */
	public boolean courseFallbackAvailable(UUID projectId, UUID courseId) {
		return courseKeyUsable(projectId, courseId, AiInvocationOrigin.AUTOMATION);
	}

	/** The course PRIMARY key would serve this team's commit review for this origin (never throws). */
	public boolean courseKeyUsable(UUID projectId, UUID courseId, AiInvocationOrigin origin) {
		if (courseId == null) return false;
		try {
			return Boolean.TRUE.equals(isolated.execute(status -> courseResolver
					.resolveCourseForProject(projectId, courseId, AiAnalysisType.COMMIT_INTELLIGENCE, AiProviderRole.PRIMARY, origin)
					.outcome() == AiCredentialResolver.Outcome.COURSE));
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/** Decrypt-and-reseal for one dispatch only; never persist or log the result. */
	public AiCredentialEnvelope buildEnvelope(UUID teamCredentialId, UUID projectId) {
		AiTeamCredential row = credentials.findById(teamCredentialId)
				.filter(c -> c.getProject() != null && c.getProject().getId().equals(projectId))
				.filter(c -> c.getStatus() != AiCredentialStatus.REVOKED && c.getEncryptedSecret() != null && !c.getEncryptedSecret().isBlank())
				.orElseThrow(() -> new AiCredentialCryptoException("AI_CREDENTIAL_ENVELOPE_SOURCE_MISSING"));
		String raw = cipher.decrypt(row.getEncryptedSecret(), row.getEncryptionNonce(), row.getEncryptionKeyVersion());
		return transport.seal(raw);
	}

	public void markSuccessful(UUID teamCredentialId) {
		credentials.findById(teamCredentialId).ifPresent(c -> {
			if (c.getStatus() == AiCredentialStatus.REVOKED) return;
			c.setStatus(AiCredentialStatus.ACTIVE);
			c.setLastErrorCode(null);
			c.setLastSuccessfulUseAt(LocalDateTime.now());
			credentials.save(c);
		});
	}

	// The mark* methods run on the AI worker thread without a surrounding transaction: each
	// repository save commits on its own, and the leader notification happens after it, so a
	// notification failure can never roll the status back.

	/** Auth failure: the key is wrong. The leader is told once per key. */
	public void markInvalid(UUID teamCredentialId, String code) {
		credentials.findById(teamCredentialId).ifPresent(c -> {
			if (c.getStatus() == AiCredentialStatus.REVOKED) return;
			c.setStatus(AiCredentialStatus.INVALID);
			c.setLastErrorCode(code);
			credentials.save(c);
			notifyLeader(c, code);
		});
	}

	/** Quota / rate limit: the key may work again later; the leader is told once per key and code. */
	public void markDegraded(UUID teamCredentialId, String code) {
		credentials.findById(teamCredentialId).ifPresent(c -> {
			if (c.getStatus() != AiCredentialStatus.ACTIVE && c.getStatus() != AiCredentialStatus.UNVERIFIED && c.getStatus() != AiCredentialStatus.DEGRADED) return;
			c.setStatus(AiCredentialStatus.DEGRADED);
			c.setLastErrorCode(code);
			credentials.save(c);
			notifyLeader(c, code);
		});
	}

	public boolean isLeader(UUID userId, UUID projectId) {
		return members.findActiveRoleByProjectIdAndUserId(projectId, userId).orElse(null) == RoleInTeam.LEADER;
	}

	Optional<UUID> leaderUserId(UUID projectId) {
		return teams.findByProject_Id(projectId)
				.flatMap(team -> members.findFetchedByTeam_Id(team.getId()).stream()
						.filter(m -> m.getRoleInTeam() == RoleInTeam.LEADER
								&& m.getCourseEnrollment() != null
								&& m.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE
								&& m.getCourseEnrollment().getStudentProfile() != null
								&& m.getCourseEnrollment().getStudentProfile().getUserAccount() != null)
						.map(m -> m.getCourseEnrollment().getStudentProfile().getUserAccount().getId())
						.findFirst());
	}

	private void notifyLeader(AiTeamCredential credential, String code) {
		if (notifications == null || credential.getProject() == null) return;
		var failure = AiFailureMessages.describe(code);
		leaderUserId(credential.getProject().getId()).ifPresent(leader -> {
			try {
				notifications.createNotification(leader, NotificationType.WARNING,
						"Key AI của nhóm: " + failure.title(),
						failure.message() + " " + failure.hint(),
						null,
						"team-ai-key:" + credential.getId() + ":" + credential.getFingerprint() + ":" + code);
			} catch (RuntimeException ex) {
				log.warn("team ai key notification failed type={}", ex.getClass().getSimpleName());
			}
		});
	}

	private static IntegrationException invalidRequest(String message) {
		return new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST, HttpStatus.BAD_REQUEST, message);
	}

	static String fingerprint(String rawApiKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(64);
			for (byte b : digest) out.append(String.format("%02x", b));
			return out.toString();
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
