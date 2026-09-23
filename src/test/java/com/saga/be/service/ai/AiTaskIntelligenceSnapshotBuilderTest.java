package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiTaskIntelligenceSnapshotBuilderTest {

	@Test
	void springAutowiredConstructorDefaultsToASystemClockWithoutRequiringAClockBean() {
		// Regression guard: production has no java.time.Clock bean registered anywhere. The 4-arg
		// constructor below is the one Spring actually autowires; it must not require a Clock
		// parameter, or bean creation fails at startup exactly as it did in production
		// ("No qualifying bean of type 'java.time.Clock' available").
		TaskGitCommitLinkRepository commitLinks = mock(TaskGitCommitLinkRepository.class);
		TaskWorkSessionRepository workSessions = mock(TaskWorkSessionRepository.class);
		when(commitLinks.findByTask_IdOrderByGitCommit_CommittedAtDesc(any(), any())).thenReturn(List.of());
		when(workSessions.countByTask_Id(any())).thenReturn(0L);

		AiTaskIntelligenceSnapshotBuilder builder = new AiTaskIntelligenceSnapshotBuilder(
				new ObjectMapper().registerModule(new JavaTimeModule()), commitLinks, workSessions, new TaskDeadlineProperties());

		Project project = new Project();
		project.setId(UUID.randomUUID());
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(project);
		task.setStatus(TaskStatus.IN_PROGRESS);
		task.setDueDate(LocalDateTime.now().plusDays(1));

		assertThatCode(() -> {
			List<AiEvidenceDraft> draft = builder.build(task);
			assertThat(draft).isNotEmpty();
		}).doesNotThrowAnyException();
	}
}
