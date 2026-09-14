package com.saga.be.service.evidence;

import com.saga.be.auth.StepUpAuthenticationService;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.task.TaskWorkSessionResponse;
import com.saga.be.dto.task.TaskWorkSessionsResponse;
import com.saga.be.entity.attribution.ContributionConfirmation;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.ConfirmationEvent;
import com.saga.be.entity.enums.ConfirmationMethod;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.service.confirmation.EvidenceHasher;
import com.saga.be.service.identity.TeamAuthorization;
import java.time.Duration;
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
	private final ProjectRealtimePublisher realtime;

	public TaskEvidenceService(
			TaskRepository tasks,
			TaskWorkSessionRepository sessions,
			ContributionConfirmationRepository confirmations,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			UserAccountRepository users,
			PasswordEncoder passwordEncoder,
			IntegrationProperties properties,
			ProjectRealtimePublisher realtime) {
		this.tasks = tasks;
		this.sessions = sessions;
		this.confirmations = confirmations;
		this.teams = teams;
		this.members = members;
		this.users = users;
		this.realtime = realtime;
		this.stepUp = new StepUpAuthenticationService(
				users::findById, passwordEncoder, properties.getReauthWindow(), properties.getReauthMaxFailures());
	}

	@Transactional(readOnly = true)
	public TaskWorkSessionsResponse listForUser(UUID userId, UUID taskId) {
		Task task = requireActiveTask(taskId);
		requireMember(userId, task.getProject().getId());
		LocalDateTime now = LocalDateTime.now();
		List<TaskWorkSessionResponse> history = sessions.findByTask_IdAndUser_IdOrderByStartedAtAsc(taskId, userId).stream()
				.map(row -> toResponse(row, now))
				.toList();
		TaskWorkSessionResponse active = history.stream()
				.filter(row -> WorkSessionStatus.OPEN.name().equals(row.status()))
				.findFirst()
				.orElse(null);
		return new TaskWorkSessionsResponse(taskId, active, history);
	}

	/**
	 * Idempotent for the same student+task: at most one OPEN session. Concurrent START is
	 * serialized by locking the Task row (no unique-index migration). A second OPEN on a
	 * different task is still allowed.
	 *
	 * <p>Legacy duplicate OPEN rows (same task+user) are not auto-closed here: GET/START pick the
	 * earliest {@code startedAt} and leave extras OPEN. Operators inspect/clean those rows with
	 * {@code scripts/audit/task_work_session_duplicate_open.sql} before deploy.
	 */
	@Transactional
	public TaskWorkSessionResponse start(UUID userId, UUID taskId) {
		Task task = tasks.lockActiveById(taskId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found."));
		requireMember(userId, task.getProject().getId());
		LocalDateTime now = LocalDateTime.now();
		List<TaskWorkSession> open =
				sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(taskId, userId, WorkSessionStatus.OPEN);
		if (!open.isEmpty()) {
			return toResponse(open.getFirst(), now);
		}
		TaskWorkSession session = new TaskWorkSession();
		session.setTask(task);
		session.setUser(users.findById(userId).orElseThrow());
		session.setProject(task.getProject());
		teams.findByProject_Id(task.getProject().getId()).ifPresent(session::setTeam);
		session.setStartedAt(now);
		session.setStatus(WorkSessionStatus.OPEN);
		TaskWorkSession saved = sessions.save(session);
		realtime.publish(
				ProjectRealtimeEventType.TASK_EVIDENCE_CHANGED, task.getProject().getId(), taskId.toString());
		return toResponse(saved, now);
	}

	@Transactional
	public TaskWorkSessionResponse stop(UUID userId, UUID taskId, UUID sessionId) {
		TaskWorkSession session = sessions.findById(sessionId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND, "Work session was not found."));
		if (!session.getUser().getId().equals(userId) || !session.getTask().getId().equals(taskId)) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "Work session was not found.");
		}
		LocalDateTime now = LocalDateTime.now();
		if (session.getStatus() == WorkSessionStatus.STOPPED) {
			return toResponse(session, now);
		}
		session.setEndedAt(now);
		session.setStatus(WorkSessionStatus.STOPPED);
		TaskWorkSession saved = sessions.save(session);
		realtime.publish(
				ProjectRealtimeEventType.TASK_EVIDENCE_CHANGED,
				session.getProject().getId(),
				taskId.toString());
		return toResponse(saved, now);
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
		ContributionConfirmation saved = confirmations.save(row);
		realtime.publish(
				ProjectRealtimeEventType.TASK_EVIDENCE_CHANGED, task.getProject().getId(), taskId.toString());
		return saved;
	}

	private void requireMember(UUID userId, UUID projectId) {
		Team team = teams.findByProject_Id(projectId).orElse(null);
		boolean member = team != null && members.existsByProjectIdAndUserId(projectId, userId);
		TeamAuthorization.requireMember(
				member
						? new TeamAuthorization.Membership(
								team.getId(), projectId, team.getCourse().getId(), null, userId)
						: null);
	}

	private Task requireActiveTask(UUID taskId) {
		return tasks.findActiveFetchedById(taskId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found."));
	}

	static TaskWorkSessionResponse toResponse(TaskWorkSession session, LocalDateTime now) {
		LocalDateTime end = session.getEndedAt() != null ? session.getEndedAt() : now;
		long elapsed = 0L;
		if (session.getStartedAt() != null && end != null && !end.isBefore(session.getStartedAt())) {
			elapsed = Duration.between(session.getStartedAt(), end).getSeconds();
		}
		return new TaskWorkSessionResponse(
				session.getId(),
				session.getTask() == null ? null : session.getTask().getId(),
				session.getStartedAt(),
				session.getEndedAt(),
				session.getStatus() == null ? null : session.getStatus().name(),
				elapsed);
	}
}
