package com.saga.be.service.evidence;

import com.saga.be.auth.StepUpAuthenticationService;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.attribution.ContributionConfirmation;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.ConfirmationEvent;
import com.saga.be.entity.enums.ConfirmationMethod;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.confirmation.EvidenceHasher;
import com.saga.be.service.identity.TeamAuthorization;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TaskEvidenceService {

	private final TaskRepository tasks;
	private final TaskWorkSessionRepository sessions;
	private final ContributionConfirmationRepository confirmations;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final UserAccountRepository users;
	private final StepUpAuthenticationService stepUp;

	public TaskEvidenceService(
			TaskRepository tasks,
			TaskWorkSessionRepository sessions,
			ContributionConfirmationRepository confirmations,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			UserAccountRepository users,
			PasswordEncoder passwordEncoder,
			IntegrationProperties properties) {
		this.tasks = tasks;
		this.sessions = sessions;
		this.confirmations = confirmations;
		this.teams = teams;
		this.members = members;
		this.users = users;
		this.stepUp = new StepUpAuthenticationService(
				users::findById, passwordEncoder, properties.getReauthWindow(), properties.getReauthMaxFailures());
	}

	@Transactional
	public TaskWorkSession start(UUID userId, UUID taskId) {
		Task task = tasks.findById(taskId).orElseThrow();
		requireMember(userId, task.getProject().getId());
		TaskWorkSession session = new TaskWorkSession();
		session.setTask(task);
		session.setUser(users.findById(userId).orElseThrow());
		session.setProject(task.getProject());
		teams.findByProject_Id(task.getProject().getId()).ifPresent(session::setTeam);
		session.setStartedAt(LocalDateTime.now());
		session.setStatus(WorkSessionStatus.OPEN);
		return sessions.save(session);
	}

	@Transactional
	public TaskWorkSession stop(UUID userId, UUID taskId, UUID sessionId) {
		TaskWorkSession session = sessions.findById(sessionId).orElseThrow();
		if (!session.getUser().getId().equals(userId) || !session.getTask().getId().equals(taskId)) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "Work session was not found.");
		}
		session.setEndedAt(LocalDateTime.now());
		session.setStatus(WorkSessionStatus.STOPPED);
		return sessions.save(session);
	}

	@Transactional
	public ContributionConfirmation confirm(
			UUID userId, UUID taskId, Instant stepUpAt, List<String> commits, List<String> prs) {
		stepUp.requireFresh(stepUpAt);
		Task task = tasks.findById(taskId).orElseThrow();
		requireMember(userId, task.getProject().getId());
		String snapshot = EvidenceHasher.canonical(
				task.getExternalKey(), commits, prs, task.getStatus() == null ? null : task.getStatus().name());
		ContributionConfirmation row = new ContributionConfirmation();
		row.setTask(task);
		row.setUser(users.findById(userId).orElseThrow());
		row.setProject(task.getProject());
		row.setEventState(ConfirmationEvent.CONFIRMED);
		row.setConfirmationMethod(ConfirmationMethod.PASSWORD_STEP_UP);
		row.setEvidenceSnapshotJson(snapshot);
		row.setEvidenceHash(EvidenceHasher.sha256(snapshot));
		return confirmations.save(row);
	}

	private void requireMember(UUID userId, UUID projectId) {
		Team team = teams.findByProject_Id(projectId).orElse(null);
		boolean member = team != null
				&& members.findByTeam_Id(team.getId()).stream()
						.anyMatch(item ->
								item.getCourseEnrollment().getStudentProfile().getUserAccount().getId().equals(userId));
		TeamAuthorization.requireMember(
				member
						? new TeamAuthorization.Membership(
								team.getId(), projectId, team.getCourse().getId(), null, userId)
						: null);
	}
}
