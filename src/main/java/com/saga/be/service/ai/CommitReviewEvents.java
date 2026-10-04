package com.saga.be.service.ai;

import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.AiAnalysisRunRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Tells the commits page that a commit review was queued or finished, so its badge updates without a
 * reload. A separate event from COMMITS_CHANGED: a review changes no commit and must not rebuild the graph.
 */
@Component
@Profile("!test")
public class CommitReviewEvents {

	private static final Logger log = LoggerFactory.getLogger(CommitReviewEvents.class);

	private final ProjectRealtimePublisher realtime;
	private final AiAnalysisRunRepository runs;

	public CommitReviewEvents(ProjectRealtimePublisher realtime, AiAnalysisRunRepository runs) {
		this.realtime = realtime;
		this.runs = runs;
	}

	/** A review of one of this project's commits was queued (sent after the surrounding commit). */
	public void queued(UUID projectId) {
		if (projectId != null) realtime.publish(ProjectRealtimeEventType.COMMIT_REVIEWS_CHANGED, projectId);
	}

	/** Run finished, whatever the outcome; ignored for anything but a commit review. Never throws. */
	public void finished(UUID runId) {
		try {
			runs.findCommitReviewProjectId(runId).ifPresent(this::queued);
		} catch (RuntimeException ex) {
			log.warn("commit review event skipped runId={} type={}", runId, ex.getClass().getSimpleName());
		}
	}
}
