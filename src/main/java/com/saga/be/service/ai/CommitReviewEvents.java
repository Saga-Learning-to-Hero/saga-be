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
		UUID requester = waiting.remove(runId);
		if (requester != null) notifyRequester(runId, requester);
	}

	/** Runs a person asked for with "Đánh giá lại", to tell them when the result is ready. */
	private final java.util.Map<UUID, UUID> waiting = new java.util.concurrent.ConcurrentHashMap<>();
	private com.saga.be.service.notification.NotificationService notifications;
	private com.saga.be.repository.ProjectRepository projects;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void setNotifications(com.saga.be.service.notification.NotificationService notifications) {
		this.notifications = notifications;
	}

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void setProjects(com.saga.be.repository.ProjectRepository projects) {
		this.projects = projects;
	}

	/** Tell {@code userId} when this run finishes (they may have left the page meanwhile). */
	public void notifyWhenFinished(UUID runId, UUID userId) {
		if (runId != null && userId != null) waiting.put(runId, userId);
	}

	private void notifyRequester(UUID runId, UUID userId) {
		if (notifications == null) return;
		try {
			com.saga.be.entity.ai.AiAnalysisRun run = runs.findById(runId).orElse(null);
			if (run == null || run.getProject() == null) return;
			String sha = run.getArtifactRevision() == null ? "" : run.getArtifactRevision();
			String shortSha = sha.length() > 7 ? sha.substring(0, 7) : sha;
			boolean done = run.getStatus() == com.saga.be.entity.enums.AiAnalysisStatus.COMPLETED;
			String title = done ? "AI đã đánh giá xong commit " + shortSha : "AI chưa đánh giá được commit " + shortSha;
			String message = done
					? "Kết quả đánh giá AI cho commit " + shortSha + " đã sẵn sàng. Bấm để xem chi tiết."
					: "Lần đánh giá AI cho commit " + shortSha + " bị lỗi. Bạn có thể mở commit và bấm \"Đánh giá lại\".";
			notifications.createNotification(userId, com.saga.be.entity.enums.NotificationType.TASK, title, message,
					actionUrl(run.getProject().getId(), run.getArtifactId()), "commit-review-done:" + runId);
		} catch (RuntimeException ex) {
			log.warn("commit review done notification skipped runId={} type={}", runId, ex.getClass().getSimpleName());
		}
	}

	/** The commits page opens this commit from its {@code commitId} parameter. */
	private String actionUrl(UUID projectId, UUID commitId) {
		StringBuilder url = new StringBuilder("/student/commits?");
		UUID courseId = projects == null ? null : projects.findCourseIdById(projectId).orElse(null);
		if (courseId != null) url.append("courseId=").append(courseId).append('&');
		return url.append("commitId=").append(commitId).toString();
	}
}
