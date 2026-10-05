package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.AiAnalysisRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Commit badges refresh when a review is queued or finished, and only for commit reviews. */
class CommitReviewEventsTest {

	private final ProjectRealtimePublisher realtime = mock(ProjectRealtimePublisher.class);
	private final AiAnalysisRunRepository runs = mock(AiAnalysisRunRepository.class);
	private final CommitReviewEvents events = new CommitReviewEvents(realtime, runs);

	@Test
	void aFinishedCommitReviewRefreshesItsProjectsBadges() {
		UUID runId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		when(runs.findCommitReviewProjectId(runId)).thenReturn(Optional.of(projectId));

		events.finished(runId);

		verify(realtime).publish(ProjectRealtimeEventType.COMMIT_REVIEWS_CHANGED, projectId);
	}

	@Test
	void otherAnalysesSendNothing_andAFailingLookupNeverEscapes() {
		UUID other = UUID.randomUUID();
		when(runs.findCommitReviewProjectId(other)).thenReturn(Optional.empty());
		events.finished(other);
		verify(realtime, never()).publish(any(ProjectRealtimeEventType.class), any(UUID.class));

		UUID broken = UUID.randomUUID();
		when(runs.findCommitReviewProjectId(broken)).thenThrow(new RuntimeException("db down"));
		assertThatCode(() -> events.finished(broken)).doesNotThrowAnyException();
	}

	private com.saga.be.entity.ai.AiAnalysisRun run(UUID runId, UUID projectId, UUID commitId, com.saga.be.entity.enums.AiAnalysisStatus status) {
		com.saga.be.entity.project.Project project = new com.saga.be.entity.project.Project();
		project.setId(projectId);
		com.saga.be.entity.ai.AiAnalysisRun run = new com.saga.be.entity.ai.AiAnalysisRun();
		run.setId(runId);
		run.setProject(project);
		run.setArtifactId(commitId);
		run.setArtifactRevision("2b90c54aaaaaaaaa");
		run.setStatus(status);
		return run;
	}

	@Test
	void thePersonWhoPressedReviewIsToldWhenTheResultIsReady_withALinkToTheCommit() {
		com.saga.be.service.notification.NotificationService notifications = mock(com.saga.be.service.notification.NotificationService.class);
		com.saga.be.repository.ProjectRepository projects = mock(com.saga.be.repository.ProjectRepository.class);
		events.setNotifications(notifications);
		events.setProjects(projects);
		UUID runId = UUID.randomUUID(), projectId = UUID.randomUUID(), commitId = UUID.randomUUID(), courseId = UUID.randomUUID(), userId = UUID.randomUUID();
		when(runs.findCommitReviewProjectId(runId)).thenReturn(Optional.of(projectId));
		when(runs.findById(runId)).thenReturn(Optional.of(run(runId, projectId, commitId, com.saga.be.entity.enums.AiAnalysisStatus.COMPLETED)));
		when(projects.findCourseIdById(projectId)).thenReturn(Optional.of(courseId));

		events.notifyWhenFinished(runId, userId);
		events.finished(runId);
		events.finished(runId); // a second signal never notifies twice

		verify(notifications, org.mockito.Mockito.times(1)).createNotification(
				org.mockito.ArgumentMatchers.eq(userId),
				org.mockito.ArgumentMatchers.eq(com.saga.be.entity.enums.NotificationType.TASK),
				org.mockito.ArgumentMatchers.eq("AI đã đánh giá xong commit 2b90c54"),
				org.mockito.ArgumentMatchers.contains("đã sẵn sàng"),
				org.mockito.ArgumentMatchers.eq("/student/commits?courseId=" + courseId + "&commitId=" + commitId),
				org.mockito.ArgumentMatchers.eq("commit-review-done:" + runId));
	}

	@Test
	void aFailedReviewIsAlsoReported_andAnAutomaticReviewNobodyAskedForIsNot() {
		com.saga.be.service.notification.NotificationService notifications = mock(com.saga.be.service.notification.NotificationService.class);
		events.setNotifications(notifications);
		UUID runId = UUID.randomUUID(), projectId = UUID.randomUUID(), userId = UUID.randomUUID();
		when(runs.findById(runId)).thenReturn(Optional.of(run(runId, projectId, UUID.randomUUID(), com.saga.be.entity.enums.AiAnalysisStatus.FAILED)));
		when(runs.findCommitReviewProjectId(any())).thenReturn(Optional.of(projectId));

		events.notifyWhenFinished(runId, userId);
		events.finished(runId);
		verify(notifications).createNotification(org.mockito.ArgumentMatchers.eq(userId), any(), org.mockito.ArgumentMatchers.startsWith("AI chưa đánh giá được commit"), any(), any(), any());

		events.finished(UUID.randomUUID()); // automatic: not registered
		verify(notifications, org.mockito.Mockito.times(1)).createNotification(any(), any(), any(), any(), any(), any());
	}
}
