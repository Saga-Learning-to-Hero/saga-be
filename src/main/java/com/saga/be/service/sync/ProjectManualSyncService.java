package com.saga.be.service.sync;

import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.dto.project.ProjectSyncStatusResponse;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ProjectManualSyncService {

	private final ProjectDataAuthorization authorization;
	private final JiraIntegrationRepository jiraIntegrations;
	private final GitRepoRepository repos;
	private final SyncJobLogRepository syncJobs;
	private final IntegrationInitialSyncLauncher launcher;
	private final JiraIntegrationCredentialService credentials;
	private final SyncJobClaimService claims;

	public ProjectManualSyncService(
			ProjectDataAuthorization authorization,
			JiraIntegrationRepository jiraIntegrations,
			GitRepoRepository repos,
			SyncJobLogRepository syncJobs,
			IntegrationInitialSyncLauncher launcher,
			JiraIntegrationCredentialService credentials,
			SyncJobClaimService claims) {
		this.authorization = authorization;
		this.jiraIntegrations = jiraIntegrations;
		this.repos = repos;
		this.syncJobs = syncJobs;
		this.launcher = launcher;
		this.credentials = credentials;
		this.claims = claims;
	}

	/**
	 * Recovery enqueue only — no provider HTTP on the request thread.
	 */
	@Transactional(readOnly = true)
	public ProjectSyncEnqueueResponse enqueue(UUID userId, UUID projectId) {
		authorization.requireStudentLeader(userId, projectId);
		String jiraState = enqueueJira(projectId);
		String githubState = enqueueGithub(projectId);
		return new ProjectSyncEnqueueResponse(projectId.toString(), jiraState, githubState);
	}

	@Transactional(readOnly = true)
	public List<ProjectSyncStatusResponse> latestStatus(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		List<ProjectSyncStatusResponse> out = new ArrayList<>(2);
		syncJobs.findFirstByTargetSystemAndTargetIdOrderByStartedAtDesc("JIRA", projectId)
				.map(job -> toStatus(projectId, job))
				.ifPresent(out::add);
		syncJobs.findFirstByTargetSystemAndTargetIdOrderByStartedAtDesc("GITHUB", projectId)
				.map(job -> toStatus(projectId, job))
				.ifPresent(out::add);
		return out;
	}

	private String enqueueJira(UUID projectId) {
		JiraIntegration integration = jiraIntegrations.findFetchedByProject_Id(projectId).orElse(null);
		if (integration == null) {
			return ProjectSyncEnqueueResponse.SKIPPED_NOT_CONFIGURED;
		}
		if (integration.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			return ProjectSyncEnqueueResponse.SKIPPED_NOT_ACTIVE;
		}
		if (!credentials.hasRefreshOrAccessCredential(projectId)) {
			return ProjectSyncEnqueueResponse.SKIPPED_NO_CREDENTIAL;
		}
		if (!claims.tryReserveEnqueue("JIRA", projectId)) {
			return ProjectSyncEnqueueResponse.SKIPPED_ALREADY_RUNNING;
		}
		try {
			launcher.enqueueJiraInitialSync(projectId);
			return ProjectSyncEnqueueResponse.QUEUED;
		} catch (RuntimeException ex) {
			claims.releaseEnqueue("JIRA", projectId);
			throw ex;
		}
	}

	private String enqueueGithub(UUID projectId) {
		List<GitRepo> active = repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE);
		if (active.isEmpty()) {
			boolean any = !repos.findByProject_Id(projectId).isEmpty();
			return any
					? ProjectSyncEnqueueResponse.SKIPPED_NOT_ACTIVE
					: ProjectSyncEnqueueResponse.SKIPPED_NOT_CONFIGURED;
		}
		if (!claims.tryReserveEnqueue("GITHUB", projectId)) {
			return ProjectSyncEnqueueResponse.SKIPPED_ALREADY_RUNNING;
		}
		try {
			launcher.enqueueGithubInitialSync(projectId);
			return ProjectSyncEnqueueResponse.QUEUED;
		} catch (RuntimeException ex) {
			claims.releaseEnqueue("GITHUB", projectId);
			throw ex;
		}
	}

	private static ProjectSyncStatusResponse toStatus(UUID projectId, SyncJobLog job) {
		return new ProjectSyncStatusResponse(
				projectId,
				job.getTargetSystem(),
				job.getStatus() == null ? null : job.getStatus().name(),
				job.getStartedAt(),
				job.getCompletedAt(),
				job.getItemsProcessed(),
				job.getItemsFailed());
	}
}
