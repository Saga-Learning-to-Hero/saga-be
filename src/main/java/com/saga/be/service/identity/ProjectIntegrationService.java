package com.saga.be.service.identity;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.integration.SelectGitHubRepositoryRequest;
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
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.integration.SyncJobLog;
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
import com.saga.be.integration.oauth.OAuthState;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.integration.oauth.PendingJiraConnect;
import com.saga.be.integration.oauth.PendingJiraConnectStore;
import com.saga.be.integration.oauth.Pkce;
import com.saga.be.messaging.OutboxPublisher;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubInstallationRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.audit.AuditService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Profile("!test")
public class ProjectIntegrationService {

	private final UserAccountRepository users;
	private final ProjectRepository projects;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final IdentityMapRepository identities;
	private final GithubInstallationRepository installations;
	private final GitRepoRepository repos;
	private final JiraIntegrationRepository jiraIntegrations;
	private final SyncJobLogRepository syncJobs;
	private final OAuthStateService oauthStates;
	private final PendingJiraConnectStore pendingJira;
	private final IntegrationProperties properties;
	private final GitHubOAuthClient github;
	private final GitHubAppJwtService githubJwt;
	private final JiraOAuthClient jira;
	private final TokenEncryptor encryptor;
	private final AuditService audit;
	private final OutboxPublisher outbox;

	public ProjectIntegrationService(
			UserAccountRepository users,
			ProjectRepository projects,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			IdentityMapRepository identities,
			GithubInstallationRepository installations,
			GitRepoRepository repos,
			JiraIntegrationRepository jiraIntegrations,
			SyncJobLogRepository syncJobs,
			OAuthStateService oauthStates,
			PendingJiraConnectStore pendingJira,
			IntegrationProperties properties,
			GitHubOAuthClient github,
			GitHubAppJwtService githubJwt,
			JiraOAuthClient jira,
			TokenEncryptor encryptor,
			AuditService audit,
			OutboxPublisher outbox) {
		this.users = users;
		this.projects = projects;
		this.teams = teams;
		this.members = members;
		this.identities = identities;
		this.installations = installations;
		this.repos = repos;
		this.jiraIntegrations = jiraIntegrations;
		this.syncJobs = syncJobs;
		this.oauthStates = oauthStates;
		this.pendingJira = pendingJira;
		this.properties = properties;
		this.github = github;
		this.githubJwt = githubJwt;
		this.jira = jira;
		this.encryptor = encryptor;
		this.audit = audit;
		this.outbox = outbox;
	}

	@Transactional(readOnly = true)
	public ProjectIntegrationsResponse summary(UUID userId, UUID projectId) {
		requireMember(userId, projectId);
		GithubInstallation installation = installations.findByProject_Id(projectId).orElse(null);
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
		requireLeader(userId, projectId);
		String verifier = Pkce.newVerifier();
		Team team = requireTeamForProject(projectId);
		OAuthState state = oauthStates.start(
				userId, OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY, safeReturnPath(returnPath), projectId, team.getId(), verifier);
		return new OAuthStartResponse(github.installationUrl(state.state()), state.state());
	}

	@Transactional
	public String completeGithubInstallation(UUID userId, String rawState, Long installationId, String userCode) {
		OAuthState state = oauthStates.consumeForUser(rawState, userId, OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY);
		requireLeader(userId, state.projectId());
		if (installationId == null) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_REQUEST, "installation_id is required.");
		}
		String jwt = githubJwt.createJwt();
		GitHubOAuthClient.GitHubInstallationResponse installation = github.getInstallation(jwt, installationId);
		if (installation == null || installation.id() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_REQUEST, "GitHub installation could not be verified.");
		}
		if (properties.getGithub().getAppId() != null
				&& !properties.getGithub().getAppId().isBlank()
				&& installation.appId() != null
				&& !String.valueOf(installation.appId()).equals(properties.getGithub().getAppId())) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.FORBIDDEN, "Installation does not belong to this GitHub App.");
		}
		if (userCode != null && !userCode.isBlank()) {
			String userToken = github.exchangeUserToken(
					userCode,
					state.pkceVerifier(),
					callback(properties.getGithub().getOauthCallbackUrl(), "/api/integrations/github/oauth/callback"));
			GitHubOAuthClient.GitHubUserInstallationsResponse userInstalls = github.listUserInstallations(userToken);
			boolean authorized = false;
			if (userInstalls != null && userInstalls.installations() != null) {
				for (GitHubOAuthClient.GitHubInstallationIdResponse node : userInstalls.installations()) {
					if (node.id() != null && node.id() == installationId) {
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
		persistVerifiedInstallation(userId, state.projectId(), installationId, installation);
		return redirect(state.frontendReturnPath());
	}

	@Transactional
	protected void persistVerifiedInstallation(
			UUID userId, UUID projectId, Long installationId, GitHubOAuthClient.GitHubInstallationResponse installation) {
		Project project = requireFetchedProject(projectId);
		installations.findByProject_Id(projectId).ifPresent(existing -> {
			if (!installationId.equals(existing.getInstallationId())) {
				existing.setProject(null);
				existing.setInstallationStatus(GitHubInstallationStatus.SUSPENDED);
				installations.save(existing);
			}
		});
		GithubInstallation entity = installations.findByInstallationId(installationId).orElseGet(GithubInstallation::new);
		entity.setInstallationId(installationId);
		entity.setAppId(installation.appId() == null ? 0L : installation.appId());
		entity.setAccountLogin(installation.account() == null ? null : installation.account().login());
		entity.setAccountType(installation.account() == null ? null : installation.account().type());
		entity.setHtmlUrl(installation.htmlUrl());
		entity.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		entity.setInstalledBy(users.findById(userId).orElseThrow());
		entity.setProject(project);
		entity.setLastVerifiedAt(LocalDateTime.now());
		entity.setConsecutiveFailures(0);
		GithubInstallation saved = installations.save(entity);
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

	public List<GitHubOAuthClient.RepoSummary> listGithubRepos(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		GithubInstallation installation = installations.findByProject_Id(projectId).orElseThrow(() -> new IntegrationException(
				IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_REQUEST, "No verified GitHub installation."));
		if (installation.getInstallationStatus() != GitHubInstallationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.CONFLICT, "GitHub installation is not active.");
		}
		String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
		return github.parseRepos(github.listInstallationRepos(token));
	}

	@Transactional
	public void selectGithubRepos(UUID userId, UUID projectId, List<SelectGitHubRepositoryRequest> selected) {
		requireLeader(userId, projectId);
		GithubInstallation installation = installations.findByProject_Id(projectId).orElseThrow(() -> new IntegrationException(
				IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_REQUEST, "No verified GitHub installation."));
		String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
		List<GitHubOAuthClient.RepoSummary> accessible = github.parseRepos(github.listInstallationRepos(token));
		persistSelectedRepos(userId, projectId, installation, selected, accessible);
	}

	@Transactional
	protected void persistSelectedRepos(
			UUID userId,
			UUID projectId,
			GithubInstallation installation,
			List<SelectGitHubRepositoryRequest> selected,
			List<GitHubOAuthClient.RepoSummary> accessible) {
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
			GitRepo repo = repos.findByProviderAndRepositoryId(GitProvider.GITHUB, repositoryId).orElseGet(GitRepo::new);
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
			GitRepo saved = repos.save(repo);
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
		startSync("GITHUB", projectId);
	}

	@Transactional
	public void disconnectGithub(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		Project project = requireFetchedProject(projectId);
		repos.findByProject_Id(projectId).forEach(repo -> {
			repo.setConnectionStatus(IntegrationStatus.REVOKED);
			repos.save(repo);
		});
		installations.findByProject_Id(projectId).ifPresent(installation -> {
			installation.setInstallationStatus(GitHubInstallationStatus.SUSPENDED);
			installation.setProject(null);
			installations.save(installation);
		});
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
		OAuthState state = oauthStates.consumeForUser(rawState, userId, OAuthFlowType.JIRA_TEAM_CONNECT);
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

	@Transactional
	public void saveJiraSelection(UUID userId, UUID projectId, Map<String, String> selection) {
		requireLeader(userId, projectId);
		PendingJiraConnect pending = pendingJira
				.consume(userId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_EXPIRED,
						HttpStatus.BAD_REQUEST,
						"Jira team authorization has expired. Start the connection again."));
		String cloudId = selection.get("cloudId");
		JiraOAuthClient.AccessibleResource site = requireAccessibleSite(pending.accessToken(), cloudId);
		JiraOAuthClient.JiraProjectResponse projectNode = jira.getProject(pending.accessToken(), site.id(), selection.get("jiraProjectId"));
		if (projectNode == null || projectNode.id() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira project is not accessible.");
		}
		if (selection.get("boardId") != null && !selection.get("boardId").isBlank()) {
			JiraOAuthClient.JiraBoardResponse board = jira.getBoard(pending.accessToken(), site.id(), selection.get("boardId"));
			if (board == null || board.id() == null) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_BOARD_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira board is not accessible.");
			}
		}
		persistJiraIntegration(userId, projectId, pending, site, projectNode, selection.get("boardId"));
	}

	@Transactional
	protected void persistJiraIntegration(
			UUID userId,
			UUID projectId,
			PendingJiraConnect pending,
			JiraOAuthClient.AccessibleResource site,
			JiraOAuthClient.JiraProjectResponse projectNode,
			String boardId) {
		if (pending.refreshToken() != null && !encryptor.isReady()) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.SERVICE_UNAVAILABLE,
					"Integration token encryption key is not configured.");
		}
		Project project = requireFetchedProject(projectId);
		UserAccount actor = users.findById(userId).orElseThrow();
		JiraIntegration integration = jiraIntegrations.findByProject_Id(project.getId()).orElseGet(JiraIntegration::new);
		integration.setProject(project);
		integration.setCloudId(site.id());
		integration.setSiteUrl(site.url());
		integration.setSiteName(site.name());
		integration.setJiraProjectId(projectNode.id());
		integration.setProjectKey(projectNode.key());
		integration.setName(projectNode.name());
		integration.setJiraBoardId(boardId);
		integration.setGrantedScopes(pending.scope());
		integration.setConnectedBy(actor);
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		JiraIntegration saved = jiraIntegrations.save(integration);
		if (pending.refreshToken() != null) {
			saved.setEncryptedRefreshToken(
					encryptor.encrypt(
							pending.refreshToken(), TokenEncryptor.aad(saved.getId().toString(), "JIRA", userId.toString())));
			jiraIntegrations.save(saved);
		}
		startSync("JIRA", project.getId());
		audit.record(
				actor,
				project,
				teams.findByProject_Id(projectId).orElse(null),
				"JIRA_INTEGRATION_CONNECTED",
				"jira_integration",
				saved.getId(),
				Map.of(),
				Map.of("cloudId", site.id(), "projectKey", saved.getProjectKey()),
				Map.of(),
				AuditSource.OAUTH,
				null,
				null,
				null);
		outbox.publish("jira_integration", saved.getId(), "JIRA_INTEGRATION_CONNECTED", Map.of("projectId", projectId.toString()));
	}

	@Transactional
	public void disconnectJira(UUID userId, UUID projectId) {
		requireLeader(userId, projectId);
		JiraIntegration integration = jiraIntegrations.findByProject_Id(projectId).orElseThrow(() -> new IntegrationException(
				IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.NOT_FOUND, "Jira is not connected."));
		integration.setConnectionStatus(IntegrationStatus.REVOKED);
		integration.setEncryptedRefreshToken(null);
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

	private void startSync(String system, UUID targetId) {
		SyncJobLog job = new SyncJobLog();
		job.setTargetSystem(system);
		job.setTargetId(targetId);
		job.setJobType(SyncJobType.INITIAL);
		job.setStatus(SyncJobStatus.RUNNING);
		job.setStartedAt(LocalDateTime.now());
		job.setItemsProcessed(0);
		job.setItemsFailed(0);
		syncJobs.save(job);
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
		String safe = safeReturnPath(returnPath);
		if (safe != null) {
			return UriComponentsBuilder.fromUriString(properties.getPublicBaseUrl()).path(safe).build().toUriString();
		}
		return properties.getSuccessUrl();
	}

	static String safeReturnPath(String returnPath) {
		if (returnPath == null || returnPath.isBlank()) {
			return null;
		}
		String trimmed = returnPath.trim();
		if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.contains("://") || trimmed.contains("\\")) {
			return null;
		}
		return trimmed;
	}
}
