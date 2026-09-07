package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.identity.TeamAuthorization;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ProjectProjectionReadService {

	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final UserAccountRepository users;

	public ProjectProjectionReadService(
			TaskRepository tasks,
			GitCommitRepository commits,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			UserAccountRepository users) {
		this.tasks = tasks;
		this.commits = commits;
		this.teams = teams;
		this.members = members;
		this.users = users;
	}

	@Transactional(readOnly = true)
	public List<ProjectTaskResponse> listTasks(UUID userId, UUID projectId) {
		requireMember(userId, projectId);
		return tasks.findActiveFetchedByProject_Id(projectId).stream().map(this::toTask).toList();
	}

	@Transactional(readOnly = true)
	public List<ProjectCommitResponse> listCommits(UUID userId, UUID projectId) {
		requireMember(userId, projectId);
		return commits.findFetchedByProject_Id(projectId).stream().map(this::toCommit).toList();
	}

	private void requireMember(UUID userId, UUID projectId) {
		UserAccount account = users.findById(userId).orElseThrow();
		if (account.getAccountRole() == AccountRole.ADMIN) {
			return;
		}
		TeamAuthorization.requireMember(membership(userId, projectId));
	}

	private TeamAuthorization.Membership membership(UUID userId, UUID projectId) {
		Team team = teams.findByProject_Id(projectId).orElse(null);
		if (team == null) {
			return null;
		}
		TeamMember member = members.findFetchedByTeam_Id(team.getId()).stream()
				.filter(item -> isActiveCourseMember(item, userId))
				.findFirst()
				.orElse(null);
		return member == null
				? null
				: new TeamAuthorization.Membership(
						team.getId(), projectId, team.getCourse().getId(), member.getRoleInTeam(), userId);
	}

	private static boolean isActiveCourseMember(TeamMember item, UUID userId) {
		if (item == null || item.getCourseEnrollment() == null) {
			return false;
		}
		CourseEnrollment enrollment = item.getCourseEnrollment();
		if (enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
			return false;
		}
		if (enrollment.getStudentProfile() == null || enrollment.getStudentProfile().getUserAccount() == null) {
			return false;
		}
		return userId.equals(enrollment.getStudentProfile().getUserAccount().getId());
	}

	private ProjectTaskResponse toTask(Task task) {
		return new ProjectTaskResponse(
				task.getId(),
				task.getExternalId(),
				task.getExternalKey(),
				task.getTitle(),
				task.getStatus() == null ? null : task.getStatus().name(),
				task.getIssueTypeName(),
				task.getAssigneeExternalId(),
				task.getAssigneeStudent() == null ? null : task.getAssigneeStudent().getId(),
				task.getExternalUpdatedAt(),
				task.getCreatedAt(),
				task.getUpdatedAt());
	}

	private ProjectCommitResponse toCommit(GitCommit commit) {
		return new ProjectCommitResponse(
				commit.getId(),
				commit.getRepo() == null ? null : commit.getRepo().getId(),
				commit.getRepo() == null ? null : commit.getRepo().getFullName(),
				commit.getShaHash(),
				commit.getMessage(),
				commit.getAuthorExternalId(),
				commit.getAuthorStudent() == null ? null : commit.getAuthorStudent().getId(),
				commit.getCommittedAt(),
				commit.getCreatedAt());
	}
}
