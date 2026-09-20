package com.saga.be.service.projection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.JiraTaskFailoverRun;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraTeamTokenService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JiraFailoverWorkerSafetyTest {
	@Mock JiraFailoverPersistenceService state;
	@Mock JiraTeamTokenService tokens;
	@Mock JiraIssueWriteClient jira;
	@Mock JiraTaskProjectionService projection;

	@Test void staleCreatingIsFunnelledToUnknownWithoutCreate() {
		UUID runId = UUID.randomUUID(); JiraTaskFailoverItem creating = item(JiraFailoverItemStatus.CREATING);
		JiraTaskFailoverItem unknown = item(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);
		when(state.loadRunItems(runId)).thenReturn(List.of(creating), List.of(unknown));
		new JiraFailoverWorker(state, tokens, jira, projection).processRun(runId);
		verify(state).recoverCreating(creating.getId());
		verify(jira, never()).createIssue(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test void remoteBoundRecoveryNeverCreatesAgain() {
		JiraTaskFailoverItem bound = item(JiraFailoverItemStatus.REMOTE_BOUND); bound.setRemoteIssueId("10042");
		when(state.loadItem(bound.getId())).thenReturn(bound);
		new JiraFailoverWorker(state, tokens, jira, projection).recoverBound(bound.getId());
		verify(jira, never()).createIssue(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	private static JiraTaskFailoverItem item(JiraFailoverItemStatus status) {
		JiraIntegration target = new JiraIntegration(); target.setId(UUID.randomUUID()); target.setCloudId("cloud"); target.setJiraProjectId("10000");
		JiraTaskFailoverRun run = new JiraTaskFailoverRun(); run.setId(UUID.randomUUID()); run.setTargetJiraIntegration(target);
		JiraTaskFailoverItem item = new JiraTaskFailoverItem(); item.setId(UUID.randomUUID()); item.setRun(run); item.setStatus(status); return item;
	}
}
