package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JiraTaskProjectionHardResetTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskWorkSessionRepository workSessions;
	@Mock
	private ContributionConfirmationRepository confirmations;
	@Mock
	private SprintRepository sprints;

	@InjectMocks
	private JiraTaskProjectionHardReset reset;

	@Test
	void protectedEvidenceExistsWhenWorkSessionPresent() {
		UUID projectId = UUID.randomUUID();
		when(workSessions.existsByProject_Id(projectId)).thenReturn(true);
		assertTrue(reset.protectedEvidenceExists(projectId));
	}

	@Test
	void protectedEvidenceExistsWhenConfirmationPresentViaTask() {
		UUID projectId = UUID.randomUUID();
		when(workSessions.existsByProject_Id(projectId)).thenReturn(false);
		when(workSessions.existsByTask_Project_Id(projectId)).thenReturn(false);
		when(confirmations.existsByProject_Id(projectId)).thenReturn(false);
		when(confirmations.existsByTask_Project_Id(projectId)).thenReturn(true);
		assertTrue(reset.protectedEvidenceExists(projectId));
	}

	@Test
	void protectedEvidenceAbsentWhenNoRows() {
		UUID projectId = UUID.randomUUID();
		when(workSessions.existsByProject_Id(projectId)).thenReturn(false);
		when(workSessions.existsByTask_Project_Id(projectId)).thenReturn(false);
		when(confirmations.existsByProject_Id(projectId)).thenReturn(false);
		when(confirmations.existsByTask_Project_Id(projectId)).thenReturn(false);
		assertFalse(reset.protectedEvidenceExists(projectId));
	}

	@Test
	void hardDeleteClearsBlocksThenDeletesTasksAndNeverTouchesProtectedEvidence() {
		UUID projectId = UUID.randomUUID();
		reset.hardDeleteAllTasksForProject(projectId);
		verify(tasks).clearBlocksTaskReferencesByProjectId(projectId);
		verify(tasks).deleteByProject_Id(projectId);
		verify(workSessions, never()).deleteAll();
		verify(confirmations, never()).deleteAll();
		verify(workSessions, never()).delete(org.mockito.ArgumentMatchers.any());
		verify(confirmations, never()).delete(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void hardDeleteAllSprintsForIntegrationDelegatesToSprintRepositoryByIntegrationId() {
		UUID integrationId = UUID.randomUUID();
		reset.hardDeleteAllSprintsForIntegration(integrationId);
		verify(sprints).deleteByJiraIntegration_Id(integrationId);
	}
}
