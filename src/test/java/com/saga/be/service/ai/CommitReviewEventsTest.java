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
}
