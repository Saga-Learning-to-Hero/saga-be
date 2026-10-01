package com.saga.be.service.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskDelayCaseRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Delay cases hold explanations and lecturer decisions: a Jira source hard reset must not erase them. */
class JiraTaskProjectionHardResetDelayCaseTest {

	@Test
	void delayCasesCountAsProtectedEvidence() {
		UUID projectId = UUID.randomUUID();
		TaskDelayCaseRepository delayCases = mock(TaskDelayCaseRepository.class);
		JiraTaskProjectionHardReset reset = new JiraTaskProjectionHardReset(
				mock(TaskRepository.class),
				mock(TaskWorkSessionRepository.class),
				mock(ContributionConfirmationRepository.class),
				mock(SprintRepository.class));

		assertThat(reset.protectedEvidenceExists(projectId)).isFalse();

		reset.setDelayCases(delayCases);
		when(delayCases.existsByProject_Id(projectId)).thenReturn(true);
		assertThat(reset.protectedEvidenceExists(projectId)).isTrue();
	}
}
