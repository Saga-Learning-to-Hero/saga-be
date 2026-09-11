package com.saga.be.service.identity;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.GithubReconnectCandidateResponse;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.integration.SelectGitHubRepositoryRequest;
import com.saga.be.dto.integration.SelectJiraIntegrationRequest;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.ConnectedRepo;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.GithubIntegrationSummary;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraBoardOption;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraIntegrationSummary;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraProjectOption;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.oauth.GithubReconnectCandidateStore;
import com.saga.be.integration.oauth.IntegrationFrontendRedirects;
import com.saga.be.integration.oauth.JiraOAuthCallbackSupport;
import com.saga.be.integration.oauth.OAuthState;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.integration.oauth.PendingJiraClaim;
import com.saga.be.integration.oauth.PendingJiraConnect;
import com.saga.be.integration.oauth.PendingJiraConnectStore;
import com.saga.be.integration.oauth.Pkce;
import com.saga.be.messaging.OutboxPublisher;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubInstallationRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.jira.JiraDynamicWebhookService;
import com.saga.be.service.sync.IntegrationInitialSyncLauncher;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
public class ProjectIntegrationService {

	private final UserAccountRepository users;
	private final ProjectRepository projects;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final IdentityMapRepository identities;
	private final GithubInstallationRepository installations;
	private final GithubProjectInstallationRepository projectInstallations;
	private final GitRepoRepository repos;
	private final JiraIntegrationRepository jiraIntegrations;
	private final SyncJobLogRepository syncJobs;
	private final OAuthStateService oauthStates;
	private final PendingJiraConnectStore pendingJira;
	private final GithubReconnectCandidateStore reconnectCandidates;
	private final IntegrationProperties properties;
	private final GitHubOAuthClient github;
	private final GitHubAppJwtService githubJwt;
	private final JiraOAuthClient jira;
	private final TokenEncryptor encryptor;
	private final AuditService audit;
	private final OutboxPublisher outbox;
	private final IntegrationInitialSyncLauncher initialSyncLauncher;
	private final JiraTaskProjectionHardReset taskProjectionReset;
	private final JiraDynamicWebhookService jiraWebhooks;
	private final TransactionTemplate writes;

	public ProjectIntegrationService(
			UserAccountRepository users,
			ProjectRepository projects,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			IdentityMapRepository identities,
			GithubInstallationRepository installations,
			GithubProjectInstallationRepository projectInstallations,
			GitRepoRepository repos,
			JiraIntegrationRepository jiraIntegrations,
			SyncJobLogRepository syncJobs,
			OAuthStateService oauthStates,
			PendingJiraConnectStore pendingJira,
			GithubReconnectCandidateStore reconnectCandidates,
			IntegrationProperties properties,
			GitHubOAuthClient github,
			GitHubAppJwtService githubJwt,
			JiraOAuthClient jira,
			TokenEncryptor encryptor,
			AuditService audit,
			OutboxPublisher outbox,
			IntegrationInitialSyncLauncher initialSyncLauncher,
			JiraTaskProjectionHardReset taskProjectionReset,
			JiraDynamicWebhookService jiraWebhooks,
			PlatformTransactionManager transactionManager) {
		this.users = users;
		this.projects = projects;
		this.teams = teams;
		this.members = members;
		this.identities = identities;
		this.installations = installations;
		this.projectInstallations = projectInstallations;
		this.repos = repos;
		this.jiraIntegrations = jiraIntegrations;
		this.syncJobs = syncJobs;
		this.oauthStates = oauthStates;
		this.pendingJira = pendingJira;
		this.reconnectCandidates = reconnectCandidates;
		this.properties = properties;
		this.github = github;
		this.githubJwt = githubJwt;
		this.jira = jira;
		this.encryptor = encryptor;
		this.audit = audit;
		this.outbox = outbox;
		this.initialSyncLauncher = initialSyncLauncher;
		this.taskProjectionReset = taskProjectionReset;
		this.jiraWebhooks = jiraWebhooks;
		this.writes = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
	}

	@Transactional(readOnly = true)
	public ProjectIntegrationsResponse summary(UUID userId, UUID projectId) {
		requireMember(userId, projectId);
		GithubInstallation installation = requireCurrentGithubInstallation(projectId).orElse(null);
		List<ConnectedRepo> connected = repos.findByProject_Id(projectId).stream()
				.map(repo -> new ConnectedRepo(
						repo.getId(),
						repo.getRepositoryId(),
						repo.getFullName(),
						repo.getRepositoryRole() == null ? null : repo.getRepositoryRole().name(),
						repo.getConnectionStatus() == null ? null : repo.getConnectionStatus().name()))
				.toList();
		GithubIntegrationSummary githubSummary = installation == null
				? null
				: new GithubIntegrationSummary(
						installation.getInstallationId(),
						installation.getAccountLogin(),
						installation.getInstallationStatus() == null ? null : installation.getInstallationStatus().name(),
						connected);
		JiraIntegration jiraRow = jiraIntegrations.findByProject_Id(projectId).orElse(null);
		JiraIntegrationSummary jiraSummary = jiraRow == null
				? null
				: new JiraIntegrationSummary(
						jiraRow.getCloudId(),
						jiraRow.getSiteName(),
						jiraRow.getProjectKey(),
						jiraRow.getJiraBoardId(),
						jiraRow.getConnectionStatus() == null ? null : jiraRow.getConnectionStatus().name());
		return new ProjectIntegrationsResponse(githubSummary, jiraSummary);
	}

	@Transactional(readOnly = true)
	public OAuthStartResponse startGithub(UUID userId, UUID projectId, String returnPath) {
		return startGithub(userId, projectId, returnPath, null, null);
	}

	@Transactional(readOnly = true)
	public OAuthStartResponse startGithub(UUID userId, UUID projectId, String returnPath, Long selectedInstallationId) {
		return startGithub(userId, projectId, returnPath, selectedInstallationId, null);
	}

	@Transactional(readOnly = true)
	public OAuthStartResponse startGithub(
			UUID userId, UUID projectId, String returnPath, Long selectedInstallationId, String mode) {
		requireLeader(userId, projectId);
		String verifier = Pkce.newVerifier();
		Team team = requireTeamForProject(projectId);
		String safeReturn = safeReturnPath(returnPath);
		if (isInstallNewMode(mode)) {
			return startGithubInstallVerify(userId, projectId, team.getId(), safeReturn, verifier);
		}
		Set<Long> historical = recoverGithubReconnectCandidates(projectId);
		if (selectedInstallationId != null) {
			if (!historical.isEmpty() && !historical.contains(selectedInstallationId)) {
				throw new IntegrationException(
						IntegrationErrorCode.GITHUB_INSTALLATION_INVALID,
						HttpStatus.BAD_REQUEST,
						"Selected installation is not part of this project's GitHub provenance.");
			}
			return startGithubReconnectOAuth(userId, projectId, team.getId(), safeReturn, verifier, selectedInstallationId);
		}
		if (historical.size() == 1) {
			Long installationId = historical.iterator().next();
			return startGithubReconnectOAuth(userId, projectId, team.getId(), safeReturn, verifier, installationId);
		}
		if (historical.size() > 1) {
			// Ambiguous historical provenance: FE must pick a candidate or use mode=install_new — never guess, no OAuth loop.
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_SELECTION_REQUIRED,
					HttpStatus.CONFLICT,
					"Multiple historical GitHub App installations exist; Team Leader must choose one or install on another account.");
		}
		// Zero provenance: discover existing App installations via user OAuth (avoid stuck Configure page).
		return startGithubReconnectOAuth(userId, projectId, team.getId(), safeReturn, verifier, null);
	}

	@Transactional(readOnly = true)
	public List<GithubReconnectCandidateResponse> listGithubReconnectCandidates(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		List<GithubReconnectCandidateResponse> pending = reconnectCandidates.find(userId, projectId);
		if (!pending.isEmpty()) {
			return pending;
		}
		List<GithubReconnectCandidateResponse> out = new java.util.ArrayList<>();
		for (Long installationId : recoverGithubReconnectCandidates(projectId)) {
			GithubInstallation row = installations.findByInstallationId(installationId).orElse(null);
			out.add(new GithubReconnectCandidateResponse(
					installationId,
					row == null ? null : row.getAccountLogin(),
					row == null ? null : row.getAccountType()));
		}
		return out;
	}

	public String completeGithubInstallation(UUID userId, String rawState, Long installationId, String userCode) {
		OAuthState state = oauthStates.consumeForUser(rawState, userId, OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY);
		requireLeader(userId, state.projectId());
		if (installationId == null) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_REQUEST, "installation_id is required.");
		}
		GitHubOAuthClient.GitHubInstallationResponse installation = verifyAppInstallation(installationId);
		if (userCode != null && !userCode.isBlank()) {
			String userToken = github.exchangeUserToken(
					userCode,
					state.pkceVerifier(),
					callback(properties.getGithub().getOauthCallbackUrl(), "/api/integrations/github/oauth/callback"));
			requireUserInstallationMembership(userToken, installationId);
		}
		persistAtomically(() -> persistVerifiedInstallation(userId, state.projectId(), installationId, installation));
		return redirect(state.frontendReturnPath());
	}

	/**
	 * Completes existing-installation reconnect after GitHub user OAuth (same callback URL as personal link).
	 * Installation id comes from OAuth state when known, otherwise from historical provenance filtered by
	 * /user/installations — never from an unverified client query param alone.
	 */
	public String completeGithubReconnect(UUID userId, String code, OAuthState state) {
		if (state.flowType() != OAuthFlowType.GITHUB_TEAM_RECONNECT) {
			throw new IntegrationException(
					IntegrationErrorCode.OAUTH_STATE_INVALID, HttpStatus.BAD_REQUEST, "OAuth state is invalid.");
		}
		requireLeader(userId, state.projectId());
		if (code == null || code.isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_NOT_AUTHORIZED,
					HttpStatus.BAD_REQUEST,
					"GitHub authorization code is required to reconnect.");
		}
		if (state.pkceVerifier() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.OAUTH_STATE_INVALID, HttpStatus.BAD_REQUEST, "PKCE verifier is missing.");
		}
		String userToken = github.exchangeUserToken(
				code,
				state.pkceVerifier(),
				callback(properties.getGithub().getOauthCallbackUrl(), "/api/integrations/github/oauth/callback"));
		ReconnectResolution resolution = resolveReconnectInstallationId(state, userToken);
		if (resolution.outcome() == ReconnectOutcome.INSTALL) {
			reconnectCandidates.clear(userId, state.projectId());
			String installVerifier = Pkce.newVerifier();
			Team team = requireTeamForProject(state.projectId());
			OAuthState installState = oauthStates.start(
					userId,
					OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY,
					state.frontendReturnPath(),
					state.projectId(),
					team.getId(),
					installVerifier);
			return github.installationUrl(installState.state());
		}
		if (resolution.outcome() == ReconnectOutcome.SELECT) {
			List<GithubReconnectCandidateResponse> choices = resolution.candidates() == null
					? List.of()
					: resolution.candidates();
			reconnectCandidates.save(userId, state.projectId(), choices, properties.getOauthStateTtl());
			return IntegrationFrontendRedirects.successLocationWithCode(
					properties.getSuccessUrl(),
					state.frontendReturnPath(),
					IntegrationErrorCode.GITHUB_INSTALLATION_SELECTION_REQUIRED);
		}
		Long installationId = resolution.installationId();
		GitHubOAuthClient.GitHubInstallationResponse installation = verifyAppInstallation(installationId);
		requireUserInstallationMembership(userToken, installationId);
		persistAtomically(() -> persistVerifiedInstallation(userId, state.projectId(), installationId, installation));
		reconnectCandidates.clear(userId, state.projectId());
		return redirect(state.frontendReturnPath());
	}

	private OAuthStartResponse startGithubInstallVerify(
			UUID userId, UUID projectId, UUID teamId, String safeReturn, String verifier) {
		OAuthState state = oauthStates.start(
				userId, OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY, safeReturn, projectId, teamId, verifier);
		return new OAuthStartResponse(github.installationUrl(state.state()), state.state());
	}

	private OAuthStartResponse startGithubReconnectOAuth(
			UUID userId,
			UUID projectId,
			UUID teamId,
			String safeReturn,
			String verifier,
			Long installationId) {
		OAuthState state = oauthStates.start(
				userId,
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				safeReturn,
				projectId,
				teamId,
				verifier,
				installationId);
		String oauthCallback =
				callback(properties.getGithub().getOauthCallbackUrl(), "/api/integrations/github/oauth/callback");
		return new OAuthStartResponse(
				github.authorizationUrl(state.state(), Pkce.challengeS256(verifier), oauthCallback), state.state());
	}

	protected void persistVerifiedInstallation(
			UUID userId, UUID projectId, Long installationId, GitHubOAuthClient.GitHubInstallationResponse installation) {
		requireLeader(userId, projectId);
		Project project = requireFetchedProject(projectId);
		// Soft-replace this project's other memberships; never suspend a shared provider installation.
		List<GithubProjectInstallation> priorMemberships = projectInstallations.findByProject_IdWithInstallation(projectId);
		for (GithubProjectInstallation prior : priorMemberships) {
			if (prior.getInstallation() != null
					&& !installationId.equals(prior.getInstallation().getInstallationId())) {
				projectInstallations.delete(prior);
			}
		}
		GithubInstallation entity = installations
				.findByInstallationIdForUpdate(installationId)
				.orElseGet(GithubInstallation::new);
		entity.setInstallationId(installationId);
		entity.setAppId(installation.appId() == null ? 0L : installation.appId());
		entity.setAccountLogin(installation.account() == null ? null : installation.account().login());
		entity.setAccountType(installation.account() == null ? null : installation.account().type());
		entity.setHtmlUrl(installation.htmlUrl());
		entity.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		entity.setInstalledBy(users.findById(userId).orElseThrow());
		// Do not write legacy github_installation.project_id — membership table is authoritative.
		entity.setLastVerifiedAt(LocalDateTime.now());
		entity.setConsecutiveFailures(0);
		GithubInstallation saved = installations.save(entity);
		ensureProjectInstallationMembership(project, saved);
		audit.record(
				users.findById(userId).orElseThrow(),
				project,
				teams.findByProject_Id(projectId).orElse(null),
				"GITHUB_INSTALLATION_CONNECTED",
				"github_installation",
				saved.getId(),
				Map.of(),
				Map.of("installationId", installationId),
				Map.of(),
				AuditSource.OAUTH,
				null,
				null,
				null);
		outbox.publish("github_installation", saved.getId(), "GITHUB_REPOSITORY_CONNECTED", Map.of("projectId", projectId.toString()));
	}

	/**
	 * Recovers distinct prior installation ids from membership first, then git_repo provenance.
	 * Empty when neither source exists — callers must not invent a SUSPENDED installation.
	 * Multiple ids are ambiguous hints, not a terminal conflict.
	 */
	Set<Long> recoverGithubReconnectCandidates(UUID projectId) {
		Set<Long> installationIds = new java.util.LinkedHashSet<>();
		for (GithubProjectInstallation membership : projectInstallations.findByProject_IdWithInstallation(projectId)) {
			if (membership.getInstallation() != null && membership.getInstallation().getInstallationId() != null) {
				installationIds.add(membership.getInstallation().getInstallationId());
			}
		}
		List<GitRepo> projectRepos = repos.findByProject_IdWithInstallation(projectId);
		for (GitRepo repo : projectRepos) {
			if (repo.getInstallation() == null || repo.getInstallation().getInstallationId() == null) {
				continue;
			}
			installationIds.add(repo.getInstallation().getInstallationId());
		}
		return installationIds;
	}

	/** @deprecated use {@link #recoverGithubReconnectCandidates(UUID)}; retained for single-candidate callers/tests. */
	Optional<Long> recoverGithubReconnectCandidate(UUID projectId) {
		Set<Long> ids = recoverGithubReconnectCandidates(projectId);
		if (ids.isEmpty()) {
			return Optional.empty();
		}
		if (ids.size() > 1) {
			return Optional.empty();
		}
		return Optional.of(ids.iterator().next());
	}

	private ReconnectResolution resolveReconnectInstallationId(OAuthState state, String userToken) {
		Set<Long> historical = recoverGithubReconnectCandidates(state.projectId());
		if (state.githubInstallationId() != null) {
			Long installationId = state.githubInstallationId();
			if (!historical.isEmpty() && !historical.contains(installationId)) {
				throw new IntegrationException(
						IntegrationErrorCode.GITHUB_INSTALLATION_INVALID,
						HttpStatus.BAD_REQUEST,
						"Selected installation is not part of this project's GitHub provenance.");
			}
			return ReconnectResolution.bind(installationId);
		}
		List<GithubReconnectCandidateResponse> eligible = eligibleUserInstallations(userToken);
		if (!historical.isEmpty()) {
			eligible = eligible.stream().filter(c -> historical.contains(c.installationId())).toList();
		}
		if (eligible.isEmpty()) {
			return ReconnectResolution.install();
		}
		if (eligible.size() > 1) {
			return ReconnectResolution.select(eligible);
		}
		return ReconnectResolution.bind(eligible.getFirst().installationId());
	}

	private List<GithubReconnectCandidateResponse> eligibleUserInstallations(String userToken) {
		GitHubOAuthClient.GitHubUserInstallationsResponse userInstalls = github.listUserInstallations(userToken);
		if (userInstalls == null || userInstalls.installations() == null) {
			return List.of();
		}
		String configuredAppId = properties.getGithub().getAppId();
		List<GithubReconnectCandidateResponse> eligible = new java.util.ArrayList<>();
		for (GitHubOAuthClient.GitHubInstallationIdResponse node : userInstalls.installations()) {
			if (node == null || node.id() == null) {
				continue;
			}
			GitHubOAuthClient.GitHubInstallationResponse verified;
			try {
				verified = verifyAppInstallation(node.id());
			} catch (IntegrationException ex) {
				continue;
			}
			if (configuredAppId != null
					&& !configuredAppId.isBlank()
					&& verified.appId() != null
					&& !String.valueOf(verified.appId()).equals(configuredAppId)) {
				continue;
			}
			Optional<GithubInstallation> existing = installations.findByInstallationId(node.id());
			String login = verified.account() == null ? null : verified.account().login();
			String type = verified.account() == null ? null : verified.account().type();
			if ((login == null || login.isBlank()) && existing.isPresent()) {
				login = existing.get().getAccountLogin();
				type = existing.get().getAccountType();
			}
			eligible.add(new GithubReconnectCandidateResponse(node.id(), login, type));
		}
		return eligible;
	}

	private static boolean isInstallNewMode(String mode) {
		if (mode == null || mode.isBlank()) {
			return false;
		}
		String normalized = mode.trim().toLowerCase().replace('-', '_');
		return "install_new".equals(normalized) || "installnew".equals(normalized);
	}

	private enum ReconnectOutcome {
		BIND,
		SELECT,
		INSTALL
	}

	private record ReconnectResolution(
			ReconnectOutcome outcome, Long installationId, List<GithubReconnectCandidateResponse> candidates) {
		static ReconnectResolution bind(Long installationId) {
			return new ReconnectResolution(ReconnectOutcome.BIND, installationId, List.of());
		}

		static ReconnectResolution select(List<GithubReconnectCandidateResponse> candidates) {
			return new ReconnectResolution(ReconnectOutcome.SELECT, null, candidates);
		}

		static ReconnectResolution install() {
			return new ReconnectResolution(ReconnectOutcome.INSTALL, null, List.of());
		}
	}

	private GitHubOAuthClient.GitHubInstallationResponse verifyAppInstallation(Long installationId) {
		String jwt = githubJwt.createJwt();
		GitHubOAuthClient.GitHubInstallationResponse installation = github.getInstallation(jwt, installationId);
		if (installation == null || installation.id() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID,
					HttpStatus.BAD_REQUEST,
					"GitHub installation could not be verified.");
		}
		if (properties.getGithub().getAppId() != null
				&& !properties.getGithub().getAppId().isBlank()
				&& installation.appId() != null
				&& !String.valueOf(installation.appId()).equals(properties.getGithub().getAppId())) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID,
					HttpStatus.FORBIDDEN,
					"Installation does not belong to this GitHub App.");
		}
		return installation;
	}

	private void requireUserInstallationMembership(String userToken, Long installationId) {
		GitHubOAuthClient.GitHubUserInstallationsResponse userInstalls = github.listUserInstallations(userToken);
		boolean authorized = false;
		if (userInstalls != null && userInstalls.installations() != null) {
			for (GitHubOAuthClient.GitHubInstallationIdResponse node : userInstalls.installations()) {
				if (node.id() != null && node.id().equals(installationId)) {
					authorized = true;
					break;
				}
			}
		}
		if (!authorized) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_NOT_AUTHORIZED,
					HttpStatus.FORBIDDEN,
					"Current GitHub user is not authorized for this installation.");
		}
	}

	private void ensureProjectInstallationMembership(Project project, GithubInstallation installation) {
		if (projectInstallations.existsByProject_IdAndInstallation_Id(project.getId(), installation.getId())) {
			return;
		}
		GithubProjectInstallation membership = new GithubProjectInstallation();
		membership.setProject(project);
		membership.setInstallation(installation);
		try {
			projectInstallations.save(membership);
		} catch (DataIntegrityViolationException ignored) {
			// Concurrent insert of the same (project, installation) pair — idempotent success.
		}
	}

	Optional<GithubInstallation> requireCurrentGithubInstallation(UUID projectId) {
		List<GithubProjectInstallation> memberships = projectInstallations.findByProject_IdWithInstallation(projectId);
		if (memberships.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(memberships.getFirst().getInstallation());
	}

	private GithubInstallation requireActiveGithubInstallationForProject(UUID projectId) {
		GithubInstallation installation = requireCurrentGithubInstallation(projectId).orElseThrow(() -> new IntegrationException(
				IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_REQUEST, "No verified GitHub installation."));
		if (installation.getInstallationStatus() != GitHubInstallationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.CONFLICT, "GitHub installation is not active.");
		}
		return installation;
	}

	public List<GitHubOAuthClient.RepoSummary> listGithubRepos(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		GithubInstallation installation = requireActiveGithubInstallationForProject(projectId);
		String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
		return github.parseRepos(github.listInstallationRepos(token));
	}

	public void selectGithubRepos(UUID userId, UUID projectId, List<SelectGitHubRepositoryRequest> selected) {
		requireLeader(userId, projectId);
		GithubInstallation installation = requireActiveGithubInstallationForProject(projectId);
		String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
		List<GitHubOAuthClient.RepoSummary> accessible = github.parseRepos(github.listInstallationRepos(token));
		persistAtomically(() -> persistSelectedRepos(userId, projectId, installation, selected, accessible));
		triggerGithubInitialSync(projectId);
	}

	protected void persistSelectedRepos(
			UUID userId,
			UUID projectId,
			GithubInstallation installation,
			List<SelectGitHubRepositoryRequest> selected,
			List<GitHubOAuthClient.RepoSummary> accessible) {
		requireLeader(userId, projectId);
		Project project = requireFetchedProject(projectId);
		UserAccount actor = users.findById(userId).orElseThrow();
		for (SelectGitHubRepositoryRequest item : selected) {
			long repositoryId = item.repositoryId();
			GitHubOAuthClient.RepoSummary match = accessible.stream()
					.filter(repo -> repo.id() == repositoryId)
					.findFirst()
					.orElseThrow(() -> new IntegrationException(
							IntegrationErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE,
							HttpStatus.FORBIDDEN,
							"Repository is not accessible to the installation."));
			GitRepo repo = repos.findByProviderAndRepositoryId(GitProvider.GITHUB, repositoryId).orElse(null);
			if (repo != null
					&& repo.getProject() != null
					&& !repo.getProject().getId().equals(projectId)) {
				throw repositoryInUse();
			}
			if (repo == null) {
				repo = new GitRepo();
			}
			repo.setProject(project);
			repo.setInstallation(installation);
			repo.setProvider(GitProvider.GITHUB);
			repo.setRepositoryId(match.id());
			repo.setName(match.name());
			repo.setFullName(match.fullName());
			repo.setOwnerLogin(match.owner());
			repo.setDefaultBranch(match.defaultBranch());
			repo.setPrivateRepository(match.privateRepo());
			repo.setConnectionStatus(IntegrationStatus.ACTIVE);
			repo.setConsecutiveFailures(0);
			if (item.role() != null) {
				repo.setRepositoryRole(item.role());
			}
			GitRepo saved;
			try {
				saved = repos.save(repo);
			} catch (DataIntegrityViolationException ex) {
				throw repositoryInUse();
			}
			audit.record(
					actor,
					project,
					teams.findByProject_Id(projectId).orElse(null),
					"GITHUB_REPOSITORY_CONNECTED",
					"git_repo",
					saved.getId(),
					Map.of(),
					Map.of("repositoryId", repositoryId, "fullName", match.fullName()),
					Map.of(),
					AuditSource.API,
					null,
					null,
					null);
			outbox.publish("git_repo", saved.getId(), "GITHUB_REPOSITORY_CONNECTED", Map.of("projectId", projectId.toString()));
		}
	}

	private static IntegrationException repositoryInUse() {
		return new IntegrationException(
				IntegrationErrorCode.GITHUB_REPOSITORY_IN_USE,
				HttpStatus.CONFLICT,
				"GitHub repository is already bound to another SAGA project.");
	}

	@Transactional
	public void disconnectGithub(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		Project project = requireFetchedProject(projectId);
		repos.findByProject_Id(projectId).forEach(repo -> {
			repo.setConnectionStatus(IntegrationStatus.REVOKED);
			repos.save(repo);
		});
		// Remove only this project's memberships; leave shared installation ACTIVE for other projects.
		projectInstallations.deleteByProject_Id(projectId);
		audit.record(
				users.findById(userId).orElseThrow(),
				project,
				teams.findByProject_Id(projectId).orElse(null),
				"GITHUB_INTEGRATION_DISCONNECTED",
				"github_installation",
				projectId,
				Map.of("status", "ACTIVE"),
				Map.of("status", "REVOKED"),
				Map.of(),
				AuditSource.API,
				null,
				null,
				null);
	}

	@Transactional(readOnly = true)
	public OAuthStartResponse startJira(UUID userId, UUID projectId, String returnPath) {
		requireLeader(userId, projectId);
		Team team = requireTeamForProject(projectId);
		String verifier = Pkce.newVerifier();
		OAuthState state = oauthStates.start(
				userId, OAuthFlowType.JIRA_TEAM_CONNECT, safeReturnPath(returnPath), projectId, team.getId(), verifier);
		return new OAuthStartResponse(
				jira.authorizationUrl(
						state.state(),
						Pkce.challengeS256(verifier),
						callback(properties.getJira().getTeamOauthCallbackUrl(), "/api/integrations/jira/team/callback"),
						true),
				state.state());
	}

	public String completeJiraTeamCallback(UUID userId, String code, String rawState) {
		return completeJiraTeamCallback(userId, code, rawState, null);
	}

	public String completeJiraTeamCallback(UUID userId, String code, String rawState, String error) {
		OAuthState state = oauthStates.consumeForUser(rawState, userId, OAuthFlowType.JIRA_TEAM_CONNECT);
		JiraOAuthCallbackSupport.requireAuthorizationCodeOrThrow(code, error);
		requireLeader(userId, state.projectId());
		JiraOAuthClient.TokenResponse tokens = jira.exchange(
				code,
				state.pkceVerifier(),
				callback(properties.getJira().getTeamOauthCallbackUrl(), "/api/integrations/jira/team/callback"));
		List<JiraOAuthClient.AccessibleResource> sites = jira.accessibleResources(tokens.accessToken());
		if (sites.isEmpty()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SITE_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "No accessible Jira site was found.");
		}
		JiraOAuthClient.Myself me = jira.myself(tokens.accessToken(), sites.getFirst().id());
		boolean linked = identities.findByUserAccount_IdAndProvider(userId, IntegrationProvider.JIRA).stream()
				.anyMatch(map -> map.getMappingStatus().isActiveLink() && me.accountId().equals(map.getExternalAccountId()));
		if (!linked) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_ACCOUNT_NOT_LINKED_TO_CURRENT_USER,
					HttpStatus.FORBIDDEN,
					"Link this Jira account in your SAGA profile first.");
		}
		pendingJira.save(
				new PendingJiraConnect(
						userId, state.projectId(), tokens.accessToken(), tokens.refreshToken(), tokens.scope(), Instant.now()),
				properties.getOauthStateTtl());
		return redirect(state.frontendReturnPath());
	}

	public List<JiraOAuthClient.AccessibleResource> listJiraSites(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		return jira.accessibleResources(pending(userId, projectId).accessToken());
	}

	public List<JiraProjectOption> listJiraProjects(UUID userId, UUID projectId, String cloudId) {
		requireLeader(userId, projectId);
		PendingJiraConnect pending = pending(userId, projectId);
		requireAccessibleSite(pending.accessToken(), cloudId);
		return jira.listProjects(pending.accessToken(), cloudId).stream()
				.map(item -> new JiraProjectOption(item.id(), item.key(), item.name()))
				.toList();
	}

	public List<JiraBoardOption> listJiraBoards(UUID userId, UUID projectId, String cloudId, String jiraProjectId) {
		requireLeader(userId, projectId);
		PendingJiraConnect pending = pending(userId, projectId);
		requireAccessibleSite(pending.accessToken(), cloudId);
		JiraOAuthClient.JiraProjectResponse projectNode = jira.getProject(pending.accessToken(), cloudId, jiraProjectId);
		if (projectNode == null || projectNode.id() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira project is not accessible.");
		}
		String key = projectNode.key() == null || projectNode.key().isBlank() ? jiraProjectId : projectNode.key();
		return jira.listBoards(pending.accessToken(), cloudId, key).stream()
				.map(item -> new JiraBoardOption(item.id(), item.name(), item.type()))
				.toList();
	}

	public void saveJiraSelection(UUID userId, UUID projectId, SelectJiraIntegrationRequest selection) {
		requireLeader(userId, projectId);
		// Peek pending for provider validation so a failed site/project/board check does not burn the
		// one-shot OAuth grant and leave a REVOKED row stuck without credentials.
		PendingJiraConnect pending = pendingJira
				.get(userId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_EXPIRED,
						HttpStatus.BAD_REQUEST,
						"Jira team authorization has expired. Start the connection again."));
		JiraOAuthClient.AccessibleResource site = requireAccessibleSite(pending.accessToken(), selection.cloudId());
		JiraOAuthClient.JiraProjectResponse projectNode =
				jira.getProject(pending.accessToken(), site.id(), selection.jiraProjectId());
		if (projectNode == null || projectNode.id() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira project is not accessible.");
		}
		if (selection.boardId() != null && !selection.boardId().isBlank()) {
			JiraOAuthClient.JiraBoardResponse board = jira.getBoard(pending.accessToken(), site.id(), selection.boardId());
			if (board == null || board.id() == null) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_BOARD_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira board is not accessible.");
			}
		}
		JiraIntegration existing = jiraIntegrations.findByProject_Id(projectId).orElse(null);
		String priorCloudId = existing == null ? null : existing.getCloudId();
		String priorWebhookId = existing == null ? null : existing.getWebhookId();
		boolean priorSource = priorCloudId != null && existing.getJiraProjectId() != null;
		boolean sameSource = priorSource
				&& Objects.equals(priorCloudId, site.id())
				&& Objects.equals(existing.getJiraProjectId(), projectNode.id());
		boolean sourceReplacement = priorSource && !sameSource;
		// Consume pending only after source-replace gates inside the write transaction.
		persistAtomically(() -> persistJiraIntegration(userId, projectId, site, projectNode, selection.boardId()));
		if (sourceReplacement && priorCloudId != null && priorWebhookId != null) {
			jiraWebhooks.unregisterRemote(priorCloudId, priorWebhookId, pending.accessToken());
		}
		// Provider HTTP outside JDBC: register/reuse dynamic OAuth webhook for realtime delivery.
		jiraWebhooks.ensureRegistered(projectId, pending.accessToken());
		triggerJiraInitialSync(projectId, pending.accessToken());
	}

	/**
	 * Persists or reactivates the single {@code jira_integration} row for this SAGA project.
	 * Soft-revoked rows are reused: {@code REVOKED -> ACTIVE} with refreshed credentials and selection.
	 * Different Jira source (cloudId / jiraProjectId) hard-resets Task projection when safe; same
	 * projectKey across sources and protected evidence block replacement without consuming pending OAuth.
	 */
	protected void persistJiraIntegration(
			UUID userId,
			UUID projectId,
			JiraOAuthClient.AccessibleResource site,
			JiraOAuthClient.JiraProjectResponse projectNode,
			String boardId) {
		requireLeader(userId, projectId);
		Project project = requireFetchedProject(projectId);
		UserAccount actor = users.findById(userId).orElseThrow();
		JiraIntegration integration = jiraIntegrations.findByProject_Id(project.getId()).orElse(null);
		if (integration != null && integration.getId() != null) {
			integration = jiraIntegrations.lockById(integration.getId()).orElse(integration);
		} else {
			integration = new JiraIntegration();
			if (integration.getConsecutiveFailures() == null) {
				integration.setConsecutiveFailures(0);
			}
		}

		String oldCloudId = integration.getCloudId();
		String oldJiraProjectId = integration.getJiraProjectId();
		String oldProjectKey = integration.getProjectKey();
		String newCloudId = site.id();
		String newJiraProjectId = projectNode.id();
		String newProjectKey = projectNode.key();
		boolean hasExistingSource = oldCloudId != null && oldJiraProjectId != null;
		boolean sameSource = hasExistingSource
				&& Objects.equals(oldCloudId, newCloudId)
				&& Objects.equals(oldJiraProjectId, newJiraProjectId);
		boolean sourceReplacement = hasExistingSource && !sameSource;

		// Ownership is cloudId + jiraProjectId (not projectKey). Reject before claim so a conflict
		// does not burn the pending OAuth grant or mutate this project's integration/tasks.
		assertJiraProviderProjectAvailableForSagaProject(newCloudId, newJiraProjectId, projectId);

		if (sourceReplacement) {
			if (oldProjectKey != null
					&& newProjectKey != null
					&& oldProjectKey.equalsIgnoreCase(newProjectKey)) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_PROJECT_KEY_AMBIGUOUS,
						HttpStatus.CONFLICT,
						"Cannot switch to a different Jira site or project that reuses the same project key.");
			}
			if (taskProjectionReset.protectedEvidenceExists(projectId)) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE,
						HttpStatus.CONFLICT,
						"Cannot replace Jira source while work sessions or contribution confirmations exist for this project.");
			}
			try {
				// Reset before consume so FK RESTRICT / concurrent evidence keeps the pending grant usable.
				taskProjectionReset.hardDeleteAllTasksForProject(projectId);
			} catch (DataIntegrityViolationException ex) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE,
						HttpStatus.CONFLICT,
						"Cannot replace Jira source while work sessions or contribution confirmations exist for this project.");
			}
		}

		PendingJiraClaim claim = pendingJira
				.claim(userId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_EXPIRED,
						HttpStatus.BAD_REQUEST,
						"Jira team authorization has expired. Start the connection again."));
		registerPendingClaimCompensation(claim);
		PendingJiraConnect pending = claim.pending();
		try {
			if (pending.refreshToken() != null && !encryptor.isReady()) {
				throw new IntegrationException(
						IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
						HttpStatus.SERVICE_UNAVAILABLE,
						"Integration token encryption key is not configured.");
			}

			integration.setProject(project);
			integration.setCloudId(newCloudId);
			integration.setSiteUrl(site.url());
			integration.setSiteName(site.name());
			integration.setJiraProjectId(newJiraProjectId);
			integration.setProjectKey(newProjectKey);
			integration.setName(projectNode.name());
			integration.setJiraBoardId(boardId);
			integration.setGrantedScopes(pending.scope());
			integration.setConnectedBy(actor);
			// Explicit reactivation: soft-revoke retains the row; reconnect must flip status back to ACTIVE.
			integration.setConnectionStatus(IntegrationStatus.ACTIVE);
			integration.setConsecutiveFailures(0);
			integration.setLastErrorCode(null);
			JiraIntegration saved;
			try {
				saved = jiraIntegrations.save(integration);
			} catch (DataIntegrityViolationException ex) {
				throw mapJiraPersistenceConflict(ex);
			}
			if (pending.refreshToken() != null) {
				saved.setEncryptedRefreshToken(
						encryptor.encrypt(
								pending.refreshToken(), TokenEncryptor.aad(saved.getId().toString(), "JIRA", userId.toString())));
				jiraIntegrations.save(saved);
			}
			if (pending.accessToken() != null && encryptor.isReady()) {
				saved.setEncryptedAccessToken(
						encryptor.encrypt(
								pending.accessToken(), TokenEncryptor.aad(saved.getId().toString(), "JIRA", userId.toString())));
				saved.setTokenExpiresAt(LocalDateTime.now().plusMinutes(50));
				jiraIntegrations.save(saved);
			}
			if (saved.getConnectionStatus() != IntegrationStatus.ACTIVE) {
				throw new IntegrationException(
						IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
						HttpStatus.INTERNAL_SERVER_ERROR,
						"Jira integration failed to activate.");
			}
			audit.record(
					actor,
					project,
					teams.findByProject_Id(projectId).orElse(null),
					"JIRA_INTEGRATION_CONNECTED",
					"jira_integration",
					saved.getId(),
					Map.of(),
					Map.of(
							"cloudId",
							site.id(),
							"projectKey",
							saved.getProjectKey() == null ? "" : saved.getProjectKey(),
							"status",
							IntegrationStatus.ACTIVE.name()),
					Map.of(),
					AuditSource.OAUTH,
					null,
					null,
					null);
			outbox.publish(
					"jira_integration", saved.getId(), "JIRA_INTEGRATION_CONNECTED", Map.of("projectId", projectId.toString()));
		} catch (RuntimeException ex) {
			// When JDBC sync is inactive (e.g. unit tests), restore immediately. When active,
			// afterCompletion(STATUS_ROLLED_BACK) also restores; restoreIfAbsent is idempotent NX.
			if (!TransactionSynchronizationManager.isSynchronizationActive()) {
				pendingJira.restoreIfAbsent(claim);
			}
			throw ex;
		}
	}

	/**
	 * One Jira provider project ({@code cloudId} + {@code jiraProjectId}) may bind to at most one
	 * SAGA project. Matching {@code projectKey} alone across different clouds is not a conflict.
	 */
	private void assertJiraProviderProjectAvailableForSagaProject(
			String cloudId, String jiraProjectId, UUID sagaProjectId) {
		if (cloudId == null || cloudId.isBlank() || jiraProjectId == null || jiraProjectId.isBlank()) {
			return;
		}
		jiraIntegrations.findByCloudIdAndJiraProjectId(cloudId, jiraProjectId).ifPresent(existing -> {
			if (existing.getProject() != null && !existing.getProject().getId().equals(sagaProjectId)) {
				throw jiraProjectInUse();
			}
		});
	}

	private static IntegrationException mapJiraPersistenceConflict(DataIntegrityViolationException ex) {
		if (isJiraCloudProjectUniqueViolation(ex)) {
			return jiraProjectInUse();
		}
		throw ex;
	}

	private static boolean isJiraCloudProjectUniqueViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex.getMostSpecificCause();
		String message = cause == null ? ex.getMessage() : cause.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("uk_jira_cloud_project");
	}

	private static IntegrationException jiraProjectInUse() {
		return new IntegrationException(
				IntegrationErrorCode.JIRA_PROJECT_IN_USE,
				HttpStatus.CONFLICT,
				"Jira project này đang được liên kết với một dự án SAGA khác.");
	}

	/**
	 * On JDBC rollback, restore the claimed pending grant only if still valid and no newer grant
	 * replaced it. Successful commit leaves the claim consumed.
	 */
	private void registerPendingClaimCompensation(PendingJiraClaim claim) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_ROLLED_BACK) {
					pendingJira.restoreIfAbsent(claim);
				}
			}
		});
	}

	@Transactional
	public void disconnectJira(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		JiraIntegration integration = jiraIntegrations.findByProject_Id(projectId).orElseThrow(() -> new IntegrationException(
				IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.NOT_FOUND, "Jira is not connected."));
		// Delete remote dynamic webhook while credentials still work; ignore remote failures.
		jiraWebhooks.unregisterIfPresent(integration, null);
		integration.setConnectionStatus(IntegrationStatus.REVOKED);
		// Soft-revoke: retain cloud/project/board selection metadata for UX, but drop all credentials so
		// revoked tokens cannot win over a later reconnect and sync cannot run until ACTIVE again.
		integration.setEncryptedRefreshToken(null);
		integration.setEncryptedAccessToken(null);
		integration.setTokenExpiresAt(null);
		jiraIntegrations.save(integration);
		audit.record(
				users.findById(userId).orElseThrow(),
				requireFetchedProject(projectId),
				teams.findByProject_Id(projectId).orElse(null),
				"JIRA_INTEGRATION_DISCONNECTED",
				"jira_integration",
				integration.getId(),
				Map.of("status", "ACTIVE"),
				Map.of("status", "REVOKED"),
				Map.of(),
				AuditSource.API,
				null,
				null,
				null);
	}

	void persistAtomically(Runnable action) {
		writes.executeWithoutResult(status -> action.run());
	}

	private void triggerJiraInitialSync(UUID projectId, String accessToken) {
		initialSyncLauncher.enqueueJiraInitialSync(projectId, accessToken);
	}

	private void triggerGithubInitialSync(UUID projectId) {
		initialSyncLauncher.enqueueGithubInitialSync(projectId);
	}

	private PendingJiraConnect pending(UUID userId, UUID projectId) {
		return pendingJira
				.get(userId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_EXPIRED,
						HttpStatus.BAD_REQUEST,
						"Jira team authorization has expired. Start the connection again."));
	}

	private JiraOAuthClient.AccessibleResource requireAccessibleSite(String accessToken, String cloudId) {
		return jira.accessibleResources(accessToken).stream()
				.filter(item -> item.id().equals(cloudId))
				.findFirst()
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.JIRA_SITE_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira site is not accessible."));
	}

	private TeamAuthorization.Membership requireLeader(UUID userId, UUID projectId) {
		TeamAuthorization.Membership membership = membership(userId, projectId);
		TeamAuthorization.requireLeaderOrAdmin(membership, users.findById(userId).orElseThrow().getAccountRole());
		return membership;
	}

	private void requireMember(UUID userId, UUID projectId) {
		UserAccount account = users.findById(userId).orElseThrow();
		if (account.getAccountRole() == com.saga.be.entity.enums.AccountRole.ADMIN) {
			return;
		}
		TeamAuthorization.requireMember(membership(userId, projectId));
	}

	private Project requireFetchedProject(UUID projectId) {
		return projects.findFetchedById(projectId).orElseThrow();
	}

	private Team requireTeamForProject(UUID projectId) {
		return teams.findByProject_Id(projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.INTEGRATION_FORBIDDEN,
						HttpStatus.FORBIDDEN,
						"Team was not found for this project."));
	}

	private TeamAuthorization.Membership membership(UUID userId, UUID projectId) {
		Team team = requireTeamForProject(projectId);
		TeamMember member = members.findFetchedByTeam_Id(team.getId()).stream()
				.filter(item -> isActiveCourseMember(item, userId))
				.findFirst()
				.orElse(null);
		return member == null
				? null
				: new TeamAuthorization.Membership(
						team.getId(), projectId, team.getCourse().getId(), member.getRoleInTeam(), userId);
	}

	private static boolean isActiveCourseMember(TeamMember item, UUID userId) {
		if (item == null || item.getCourseEnrollment() == null) {
			return false;
		}
		CourseEnrollment enrollment = item.getCourseEnrollment();
		if (enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
			return false;
		}
		if (enrollment.getStudentProfile() == null || enrollment.getStudentProfile().getUserAccount() == null) {
			return false;
		}
		return userId.equals(enrollment.getStudentProfile().getUserAccount().getId());
	}

	private String callback(String configured, String path) {
		if (configured != null && !configured.isBlank()) {
			return configured;
		}
		return properties.getPublicBaseUrl() + path;
	}

	private String redirect(String returnPath) {
		return IntegrationFrontendRedirects.successLocation(properties.getSuccessUrl(), returnPath);
	}

	static String safeReturnPath(String returnPath) {
		return IntegrationFrontendRedirects.safeReturnPath(returnPath);
	}
}
