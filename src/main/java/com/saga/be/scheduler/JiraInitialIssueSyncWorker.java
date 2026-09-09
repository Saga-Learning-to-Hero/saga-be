package com.saga.be.scheduler;

import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.jira.JiraIssueEvidenceSyncService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class JiraInitialIssueSyncWorker {

	private static final Logger log = LoggerFactory.getLogger(JiraInitialIssueSyncWorker.class);

	private final SyncJobLogRepository syncJobs;
	private final JiraIntegrationRepository integrations;
	private final JiraIssueEvidenceSyncService evidence;

	public JiraInitialIssueSyncWorker(
			SyncJobLogRepository syncJobs,
			JiraIntegrationRepository integrations,
			JiraIssueEvidenceSyncService evidence) {
		this.syncJobs = syncJobs;
		this.integrations = integrations;
		this.evidence = evidence;
	}

	@Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT15S")
	public void drainInitialJobs() {
		List<SyncJobLog> jobs =
				syncJobs.findByTargetSystemAndJobTypeAndStatus("JIRA", SyncJobType.INITIAL, SyncJobStatus.RUNNING);
		for (SyncJobLog job : jobs) {
			try {
				JiraIntegration integration = integrations.findFetchedByProject_Id(job.getTargetId()).orElse(null);
				int processed = integration == null ? 0 : evidence.syncIntegration(integration);
				job.setStatus(SyncJobStatus.SUCCEEDED);
				job.setItemsProcessed(processed);
				job.setCompletedAt(LocalDateTime.now());
				if (integration != null) {
					integration.setLastSyncedAt(LocalDateTime.now());
					integration.setLastSuccessfulSyncAt(LocalDateTime.now());
					integrations.save(integration);
				}
			} catch (RuntimeException ex) {
				log.warn("jira initial evidence sync failed job={}: {}", job.getId(), ex.getMessage());
				job.setStatus(SyncJobStatus.FAILED);
				job.setErrorCategory("JIRA_ISSUE_SYNC");
				job.setErrorMessage(ex.getMessage());
				job.setCompletedAt(LocalDateTime.now());
				job.setItemsFailed(1);
			}
			syncJobs.save(job);
		}
	}
}
