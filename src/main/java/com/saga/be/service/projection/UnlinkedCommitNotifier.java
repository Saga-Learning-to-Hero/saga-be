package com.saga.be.service.projection;

import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.notification.NotificationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells the author and the team leader when newly pushed commits are attached to no task, so they
 * can put the Jira key in the next commit or attach it by hand. One notification per push per
 * person (not one per commit), merge commits ignored. Runs after the commits are committed and
 * never affects ingestion.
 */
@Component
@Profile("!test")
public class UnlinkedCommitNotifier {

	private static final Logger log = LoggerFactory.getLogger(UnlinkedCommitNotifier.class);
	static final int LISTED = 3;

	private final TaskGitCommitLinkRepository links;
	private final TaskCommitManualLinkRepository manualLinks;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final NotificationService notifications;

	public UnlinkedCommitNotifier(
			TaskGitCommitLinkRepository links,
			TaskCommitManualLinkRepository manualLinks,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			NotificationService notifications) {
		this.links = links;
		this.manualLinks = manualLinks;
		this.teams = teams;
		this.members = members;
		this.notifications = notifications;
	}

	/** What is needed after commit, captured while the commits are still attached. */
	record NewCommit(UUID id, String sha, String message, UUID authorUserId, String authorName, String authorLogin) {}

	public void afterNewCommits(UUID projectId, List<GitCommit> fresh) {
		if (projectId == null || fresh == null || fresh.isEmpty()) return;
		List<NewCommit> captured = new ArrayList<>();
		for (GitCommit commit : fresh) {
			if (commit.looksLikeMerge()) continue;
			var account = commit.getAuthorStudent() == null ? null : commit.getAuthorStudent().getUserAccount();
			captured.add(new NewCommit(commit.getId(), commit.getShaHash(), commit.getMessage(),
					account == null ? null : account.getId(), account == null ? null : account.getFullName(), commit.getAuthorExternalId()));
		}
		if (captured.isEmpty()) return;
		Runnable run = () -> {
			try {
				notifyUnlinked(projectId, captured);
			} catch (RuntimeException ex) {
				log.warn("unlinked commit notification failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCommit() { run.run(); }
			});
		} else {
			run.run();
		}
	}

	void notifyUnlinked(UUID projectId, List<NewCommit> captured) {
		List<UUID> ids = captured.stream().map(NewCommit::id).toList();
		Set<UUID> linked = new HashSet<>();
		links.findLiveWithTaskByGitCommitIds(ids).forEach(link -> linked.add(link.getGitCommit().getId()));
		manualLinks.findFetchedByProjectAndCommitIds(projectId, ids).forEach(link -> linked.add(link.getGitCommit().getId()));
		List<NewCommit> unlinked = captured.stream().filter(c -> !linked.contains(c.id())).toList();
		if (unlinked.isEmpty()) return;
		UUID leader = leaderUserId(projectId).orElse(null);
		Map<String, List<NewCommit>> byAuthor = new LinkedHashMap<>();
		for (NewCommit commit : unlinked) {
			String key = commit.authorUserId() != null ? commit.authorUserId().toString() : "login:" + commit.authorLogin();
			byAuthor.computeIfAbsent(key, ignored -> new ArrayList<>()).add(commit);
		}
		for (List<NewCommit> group : byAuthor.values()) {
			NewCommit first = group.getFirst();
			String list = describe(group);
			String hint = " Hãy ghi mã task (ví dụ SAGA-12) vào tên commit, hoặc mở trang Commits để gắn task thủ công.";
			if (first.authorUserId() != null) {
				send(first.authorUserId(), "Commit chưa gắn task",
						"Bạn có " + group.size() + " commit mới chưa gắn task: " + list + "." + hint,
						"commit-no-task:" + projectId + ":" + first.sha() + ":" + first.authorUserId());
			}
			if (leader != null && !leader.equals(first.authorUserId())) {
				String who = first.authorName() != null ? "Thành viên " + first.authorName()
						: "Tài khoản GitHub " + (first.authorLogin() == null ? "chưa liên kết" : first.authorLogin());
				send(leader, "Commit chưa gắn task",
						who + " có " + group.size() + " commit mới chưa gắn task: " + list + "." + hint,
						"commit-no-task:" + projectId + ":" + first.sha() + ":" + leader);
			}
		}
	}

	static String describe(List<NewCommit> group) {
		String listed = group.stream().limit(LISTED)
				.map(c -> shortSha(c.sha()) + " \"" + shortMessage(c.message()) + "\"")
				.collect(Collectors.joining(", "));
		return group.size() > LISTED ? listed + " và " + (group.size() - LISTED) + " commit khác" : listed;
	}

	private void send(UUID userId, String title, String message, String eventKey) {
		try {
			notifications.createNotification(userId, NotificationType.WARNING, title,
					message.length() > 1000 ? message.substring(0, 997) + "..." : message, null,
					eventKey.length() > 255 ? eventKey.substring(0, 255) : eventKey);
		} catch (RuntimeException ex) {
			log.warn("unlinked commit notification failed type={}", ex.getClass().getSimpleName());
		}
	}

	private Optional<UUID> leaderUserId(UUID projectId) {
		return teams.findByProject_Id(projectId)
				.flatMap(team -> members.findFetchedByTeam_Id(team.getId()).stream()
						.filter(m -> m.getRoleInTeam() == RoleInTeam.LEADER
								&& m.getCourseEnrollment() != null
								&& m.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE
								&& m.getCourseEnrollment().getStudentProfile() != null
								&& m.getCourseEnrollment().getStudentProfile().getUserAccount() != null)
						.map(m -> m.getCourseEnrollment().getStudentProfile().getUserAccount().getId())
						.findFirst());
	}

	private static String shortSha(String sha) {
		return sha == null ? "" : sha.length() <= 7 ? sha : sha.substring(0, 7);
	}

	private static String shortMessage(String message) {
		if (message == null) return "";
		String line = message.strip().lines().findFirst().orElse("");
		return line.length() <= 50 ? line : line.substring(0, 47) + "...";
	}
}
