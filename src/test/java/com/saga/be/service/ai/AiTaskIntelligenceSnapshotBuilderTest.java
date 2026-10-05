package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiTaskIntelligenceSnapshotBuilderTest {

	private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

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

	@Test
	void everyEvidencePayloadIsAJsonObject_soATaskWithLinkedCommitsReachesTheAi() throws Exception {
		Project project = new Project();
		project.setId(UUID.randomUUID());
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(project);
		task.setExternalKey("SAGA-119");
		task.setTitle("Chỉnh sửa hệ số peer");
		task.setStatus(TaskStatus.DONE);
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setShaHash("2b90c54aa");
		commit.setMessage("fix: [FE][SAGA-119] Chỉnh sửa hệ số peer review");
		commit.setCommittedAt(LocalDateTime.of(2026, 10, 5, 0, 3));
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setTask(task);
		link.setGitCommit(commit);
		link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		TaskGitCommitLinkRepository links = mock(TaskGitCommitLinkRepository.class);
		when(links.findByTask_IdOrderByGitCommit_CommittedAtDesc(eq(task.getId()), any())).thenReturn(List.of(link));
		TaskWorkSessionRepository sessions = mock(TaskWorkSessionRepository.class);
		AiTaskIntelligenceSnapshotBuilder builder = new AiTaskIntelligenceSnapshotBuilder(
				mapper, links, sessions, new TaskDeadlineProperties(), Clock.fixed(Instant.parse("2026-10-05T07:00:00Z"), ZoneOffset.UTC));

		List<AiEvidenceDraft> draft = builder.build(task);

		assertThat(draft).hasSize(2);
		for (AiEvidenceDraft row : draft) {
			// exactly how RemoteAiModelProvider reads each payload before sending it to saga-ai
			assertThatCode(() -> mapper.readValue(row.payloadJson(), new TypeReference<Map<String, Object>>() {}))
					.as(row.sourceRef())
					.doesNotThrowAnyException();
		}
		Map<String, Object> commits = mapper.readValue(draft.get(1).payloadJson(), new TypeReference<>() {});
		assertThat(commits.get("taskId")).isEqualTo(task.getId().toString());
		assertThat((List<?>) commits.get("linkedCommits")).singleElement()
				.satisfies(row -> assertThat(((Map<?, ?>) row).get("sha")).isEqualTo("2b90c54aa"));
	}
}
