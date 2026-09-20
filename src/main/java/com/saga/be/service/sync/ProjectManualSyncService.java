package com.saga.be.service.sync;

import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.dto.project.ProjectSyncStatusResponse;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
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
	 * Not read-only: SyncJobClaimService must be able to fail stale RUNNING rows before reserve.
	 * Each ACTIVE Jira source is enqueued independently by integration id.
	 */
	public ProjectSyncEnqueueResponse enqueue(UUID userId, UUID projectId) {
		authorization.requireStudentLeader(userId, projectId);
		String jiraState = enqueueJira(projectId);
		String githubState = enqueueGithub(projectId);
		return new ProjectSyncEnqueueResponse(projectId.toString(), jiraState, githubState);
	}

	/**
	 * Recovery enqueue for a single named Jira source. GitHub is not touched — response
	 * {@code github} is always {@link ProjectSyncEnqueueResponse#SKIPPED_NOT_CONFIGURED}.
	 */
	public ProjectSyncEnqueueResponse enqueueJiraSource(UUID userId, UUID projectId, UUID integrationId) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = jiraIntegrations
				.findByIdAndProject_Id(integrationId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Jira source was not found for this project."));
		String jiraState;
		if (integration.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			jiraState = ProjectSyncEnqueueResponse.SKIPPED_NOT_ACTIVE;
		} else if (!credentials.hasRefreshOrAccessCredential(integrationId)) {
			jiraState = ProjectSyncEnqueueResponse.SKIPPED_NO_CREDENTIAL;
		} else if (!claims.tryReserveEnqueue("JIRA", integrationId)) {
			jiraState = ProjectSyncEnqueueResponse.SKIPPED_ALREADY_RUNNING;
		} else {
			try {
				launcher.enqueueJiraInitialSync(integrationId);
				jiraState = ProjectSyncEnqueueResponse.QUEUED;
			} catch (RuntimeException ex) {
				claims.releaseEnqueue("JIRA", integrationId);
				throw ex;
			}
		}
		return new ProjectSyncEnqueueResponse(
				projectId.toString(), jiraState, ProjectSyncEnqueueResponse.SKIPPED_NOT_CONFIGURED);
	}

	@Transactional(readOnly = true)
	public List<ProjectSyncStatusResponse> latestStatus(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		List<ProjectSyncStatusResponse> out = new ArrayList<>();
		for (JiraIntegration integration : jiraIntegrations.findAllByProject_Id(projectId)) {
			syncJobs.findFirstByTargetSystemAndTargetIdOrderByStartedAtDesc("JIRA", integration.getId())
					.map(job -> toStatus(projectId, job, integration.getId()))
					.ifPresent(out::add);
		}
		syncJobs.findFirstByTargetSystemAndTargetIdOrderByStartedAtDesc("GITHUB", projectId)
				.map(job -> toStatus(projectId, job, null))
				.ifPresent(out::add);
		return out;
	}

	private String enqueueJira(UUID projectId) {
		List<JiraIntegration> all = jiraIntegrations.findAllFetchedByProject_Id(projectId);
		if (all.isEmpty()) {
			return ProjectSyncEnqueueResponse.SKIPPED_NOT_CONFIGURED;
		}
		List<JiraIntegration> active = all.stream()
				.filter(row -> row.getConnectionStatus() == IntegrationStatus.ACTIVE)
				.toList();
		if (active.isEmpty()) {
			return ProjectSyncEnqueueResponse.SKIPPED_NOT_ACTIVE;
		}
		boolean anyQueued = false;
		boolean anyAlreadyRunning = false;
		boolean anyNoCredential = false;
		for (JiraIntegration integration : active) {
			UUID integrationId = integration.getId();
			if (!credentials.hasRefreshOrAccessCredential(integrationId)) {
				anyNoCredential = true;
				continue;
			}
			if (!claims.tryReserveEnqueue("JIRA", integrationId)) {
				anyAlreadyRunning = true;
				continue;
			}
			try {
				launcher.enqueueJiraInitialSync(integrationId);
				anyQueued = true;
			} catch (RuntimeException ex) {
				claims.releaseEnqueue("JIRA", integrationId);
				throw ex;
			}
		}
		if (anyQueued) {
			return ProjectSyncEnqueueResponse.QUEUED;
		}
		if (anyAlreadyRunning) {
			return ProjectSyncEnqueueResponse.SKIPPED_ALREADY_RUNNING;
		}
		if (anyNoCredential) {
			return ProjectSyncEnqueueResponse.SKIPPED_NO_CREDENTIAL;
		}
		return ProjectSyncEnqueueResponse.SKIPPED_NOT_ACTIVE;
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

	private static ProjectSyncStatusResponse toStatus(UUID projectId, SyncJobLog job, UUID jiraIntegrationId) {
		return new ProjectSyncStatusResponse(
				projectId,
				job.getTargetSystem(),
				job.getStatus() == null ? null : job.getStatus().name(),
				job.getStartedAt(),
				job.getCompletedAt(),
				job.getItemsProcessed(),
				job.getItemsFailed(),
				jiraIntegrationId);
	}
}
