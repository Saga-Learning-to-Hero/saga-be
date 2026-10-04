package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.notification.NotificationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UnlinkedCommitNotifierTest {

	private final UUID projectId = UUID.randomUUID();
	private final UUID leaderId = UUID.randomUUID();
	private TaskGitCommitLinkRepository links;
	private TaskCommitManualLinkRepository manualLinks;
	private NotificationService notifications;
	private UnlinkedCommitNotifier notifier;

	@BeforeEach
	void setUp() {
		links = mock(TaskGitCommitLinkRepository.class);
		manualLinks = mock(TaskCommitManualLinkRepository.class);
		notifications = mock(NotificationService.class);
		TeamByProjectRepository teams = mock(TeamByProjectRepository.class);
		TeamMemberRepository members = mock(TeamMemberRepository.class);
		Team team = new Team();
		team.setId(UUID.randomUUID());
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(member(leaderId, RoleInTeam.LEADER)));
		when(links.findLiveWithTaskByGitCommitIds(any())).thenReturn(List.of());
		when(manualLinks.findFetchedByProjectAndCommitIds(eq(projectId), any())).thenReturn(List.of());
		notifier = new UnlinkedCommitNotifier(links, manualLinks, teams, members, notifications);
	}

	@Test
	void theAuthorAndTheLeaderAreToldOncePerPush_withTheCommitsListed() {
		UUID author = UUID.randomUUID();
		List<GitCommit> push = new ArrayList<>();
		for (int i = 0; i < 5; i++) push.add(commit("sha000000" + i, "fix bug number " + i, author, "Nguyễn An", 1));

		notifier.afterNewCommits(projectId, push);

		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(eq(author), eq(NotificationType.WARNING), eq("Commit chưa gắn task"), message.capture(), isNull(),
				eq("commit-no-task:" + projectId + ":sha0000000:" + author));
		assertThat(message.getValue()).startsWith("Bạn có 5 commit mới chưa gắn task: sha0000 \"fix bug number 0\"")
				.contains("và 2 commit khác").contains("SAGA-12").contains("gắn task thủ công");
		ArgumentCaptor<String> leaderMessage = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(eq(leaderId), eq(NotificationType.WARNING), eq("Commit chưa gắn task"), leaderMessage.capture(), isNull(), anyString());
		assertThat(leaderMessage.getValue()).startsWith("Thành viên Nguyễn An có 5 commit mới chưa gắn task");
		verify(notifications, times(2)).createNotification(any(), any(), any(), any(), any(), any());
	}

	@Test
	void linkedCommitsAndMergesAreLeftOut() {
		UUID author = UUID.randomUUID();
		GitCommit linked = commit("linked1", "SAGA-1 feat", author, "An", 1);
		GitCommit merge = commit("merge01", "Merge pull request #3", author, "An", 2);
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setGitCommit(linked);
		when(links.findLiveWithTaskByGitCommitIds(any())).thenReturn(List.of(link));

		notifier.afterNewCommits(projectId, List.of(linked, merge));

		verifyNoInteractions(notifications);
	}

	@Test
	void theLeadersOwnCommitsSendOneNotificationOnly() {
		notifier.afterNewCommits(projectId, List.of(commit("leader1", "update", leaderId, "Trưởng nhóm", 1)));
		verify(notifications, times(1)).createNotification(eq(leaderId), any(), any(), anyString(), isNull(), anyString());
	}

	@Test
	void anUnmappedGithubAuthorIsReportedToTheLeaderOnly() {
		GitCommit commit = commit("ghost01", "wip", null, null, 1);
		commit.setAuthorExternalId("ghost-login");

		notifier.afterNewCommits(projectId, List.of(commit));

		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(eq(leaderId), any(), any(), message.capture(), isNull(), anyString());
		assertThat(message.getValue()).startsWith("Tài khoản GitHub ghost-login có 1 commit mới chưa gắn task");
		verify(notifications, times(1)).createNotification(any(), any(), any(), any(), any(), any());
	}

	@Test
	void aNotificationFailureNeverBreaksIngestion() {
		when(notifications.createNotification(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("down"));
		notifier.afterNewCommits(projectId, List.of(commit("abc1234", "x", UUID.randomUUID(), "An", 1)));
		notifier.afterNewCommits(projectId, List.of());
		notifier.afterNewCommits(null, List.of(commit("abc1235", "x", UUID.randomUUID(), "An", 1)));
		verify(links, times(1)).findLiveWithTaskByGitCommitIds(any());
	}

	@Test
	void onlyMergeCommits_noQueryAtAll() {
		notifier.afterNewCommits(projectId, List.of(commit("merge02", "Merge branch 'dev'", UUID.randomUUID(), "An", 2)));
		verify(links, never()).findLiveWithTaskByGitCommitIds(any());
	}

	private static GitCommit commit(String sha, String message, UUID authorUserId, String authorName, int parents) {
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setShaHash(sha);
		commit.setMessage(message);
		commit.setParentCount(parents);
		if (authorUserId != null) {
			UserAccount account = new UserAccount();
			account.setId(authorUserId);
			account.setFullName(authorName);
			StudentProfile student = new StudentProfile();
			student.setUserAccount(account);
			commit.setAuthorStudent(student);
		}
		return commit;
	}

	private static TeamMember member(UUID userId, RoleInTeam role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		StudentProfile student = new StudentProfile();
		student.setUserAccount(account);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(student);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		TeamMember member = new TeamMember();
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}
}
