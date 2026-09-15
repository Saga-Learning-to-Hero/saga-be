package com.saga.be.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.task.TaskWorkSessionResponse;
import com.saga.be.dto.task.TaskWorkSessionsResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.attribution.ContributionConfirmation;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.ConfirmationEvent;
import com.saga.be.entity.enums.ConfirmationMethod;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.confirmation.EvidenceHasher;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class TaskEvidenceServiceTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskWorkSessionRepository sessions;
	@Mock
	private ContributionConfirmationRepository confirmations;
	@Mock
	private TeamByProjectRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private UserAccountRepository users;
	@Mock
	private PasswordEncoder passwordEncoder;

	private TaskEvidenceService service;
	private UserAccount student;
	private Project project;
	private Team team;
	private Task task;
	private final AtomicReference<ProjectRealtimeEvent> lastEvent = new AtomicReference<>();

	@BeforeEach
	void setUp() {
		IntegrationProperties properties = new IntegrationProperties();
		properties.setReauthWindow(Duration.ofMinutes(10));
		lastEvent.set(null);
		service = new TaskEvidenceService(
				tasks,
				sessions,
				confirmations,
				teams,
				members,
				users,
				passwordEncoder,
				properties,
				new com.saga.be.realtime.ProjectRealtimePublisher(event -> lastEvent.set((ProjectRealtimeEvent) event)));
		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setAccountRole(AccountRole.STUDENT);
		project = new Project();
		project.setId(UUID.randomUUID());
		Course course = new Course();
		course.setId(UUID.randomUUID());
		team = new Team();
		team.setId(UUID.randomUUID());
		team.setCourse(course);
		team.setProject(project);
		task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(project);
		task.setExternalKey("SAGA-1");
		task.setStatus(TaskStatus.TODO);
	}

	@Test
	void startAuthorizesWithTargetedMembershipQuery() {
		stubMemberLock();
		stubStudentLookup();
		when(sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
						task.getId(), student.getId(), WorkSessionStatus.OPEN))
				.thenReturn(List.of());
		stubSaveAssignsId();

		TaskWorkSessionResponse response = service.start(student.getId(), task.getId());

		assertEquals(WorkSessionStatus.OPEN.name(), response.status());
		assertEquals(task.getId(), response.taskId());
		assertNotNull(response.startedAt());
		assertNull(response.endedAt());
		assertTrue(response.elapsedSeconds() >= 0);
		verify(members, times(1)).existsByProjectIdAndUserId(project.getId(), student.getId());
		verify(members, never()).findByTeam_Id(any());
		verify(sessions, times(1)).save(any(TaskWorkSession.class));
		assertEquals(ProjectRealtimeEventType.TASK_EVIDENCE_CHANGED, lastEvent.get().type());
	}

	@Test
	void startRejectsNonMemberWithoutLoadingRoster() {
		when(tasks.lockActiveById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(false);

		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> service.start(student.getId(), task.getId()));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
		verify(members, never()).findByTeam_Id(any());
		verify(sessions, never()).save(any());
		assertNull(lastEvent.get());
	}

	@Test
	void startSameUserSameTask_returnsExistingOpenSession() {
		stubMemberLock();
		TaskWorkSession existing = openSession(LocalDateTime.now().minusMinutes(5));
		when(sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
						task.getId(), student.getId(), WorkSessionStatus.OPEN))
				.thenReturn(List.of(existing));

		TaskWorkSessionResponse first = service.start(student.getId(), task.getId());
		TaskWorkSessionResponse second = service.start(student.getId(), task.getId());

		assertEquals(existing.getId(), first.id());
		assertEquals(existing.getId(), second.id());
		assertEquals(WorkSessionStatus.OPEN.name(), second.status());
		assertTrue(second.elapsedSeconds() >= 300);
		verify(sessions, never()).save(any());
		assertNull(lastEvent.get());
	}

	@Test
	void listForUser_returnsActiveSessionAndCompletedHistory() {
		when(tasks.findActiveFetchedById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
		LocalDateTime t0 = LocalDateTime.now().minusHours(2);
		LocalDateTime t1 = t0.plusMinutes(30);
		TaskWorkSession completed = stoppedSession(t0, t1);
		TaskWorkSession open = openSession(LocalDateTime.now().minusMinutes(3));
		when(sessions.findByTask_IdAndUser_IdOrderByStartedAtAsc(task.getId(), student.getId()))
				.thenReturn(List.of(completed, open));

		TaskWorkSessionsResponse view = service.listForUser(student.getId(), task.getId());

		assertEquals(task.getId(), view.taskId());
		assertEquals(2, view.sessions().size());
		assertEquals(completed.getId(), view.sessions().getFirst().id());
		assertEquals(WorkSessionStatus.STOPPED.name(), view.sessions().getFirst().status());
		assertEquals(30 * 60, view.sessions().getFirst().elapsedSeconds());
		assertEquals(open.getId(), view.activeSession().id());
		assertEquals(WorkSessionStatus.OPEN.name(), view.activeSession().status());
		verify(sessions, never()).save(any());
		assertNull(lastEvent.get());
	}

	@Test
	void listForUser_doesNotStopOpenSession() {
		when(tasks.findActiveFetchedById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
		TaskWorkSession open = openSession(LocalDateTime.now().minusMinutes(1));
		when(sessions.findByTask_IdAndUser_IdOrderByStartedAtAsc(task.getId(), student.getId()))
				.thenReturn(List.of(open));

		TaskWorkSessionsResponse view = service.listForUser(student.getId(), task.getId());

		assertEquals(open.getId(), view.activeSession().id());
		assertEquals(WorkSessionStatus.OPEN.name(), view.activeSession().status());
		assertNull(view.activeSession().endedAt());
		verify(sessions, never()).save(any());
	}

	@Test
	void stopClosesExactlyTheIntendedSession() {
		TaskWorkSession open = openSession(LocalDateTime.now().minusMinutes(2));
		when(sessions.findById(open.getId())).thenReturn(Optional.of(open));
		when(sessions.save(any(TaskWorkSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

		TaskWorkSessionResponse stopped = service.stop(student.getId(), task.getId(), open.getId());

		assertEquals(open.getId(), stopped.id());
		assertEquals(WorkSessionStatus.STOPPED.name(), stopped.status());
		assertNotNull(stopped.endedAt());
		assertTrue(stopped.elapsedSeconds() >= 120);
		assertEquals(ProjectRealtimeEventType.TASK_EVIDENCE_CHANGED, lastEvent.get().type());
	}

	@Test
	void stopAlreadyStopped_isIdempotent() {
		TaskWorkSession done = stoppedSession(LocalDateTime.now().minusMinutes(10), LocalDateTime.now().minusMinutes(5));
		when(sessions.findById(done.getId())).thenReturn(Optional.of(done));

		TaskWorkSessionResponse again = service.stop(student.getId(), task.getId(), done.getId());

		assertEquals(WorkSessionStatus.STOPPED.name(), again.status());
		assertEquals(done.getEndedAt(), again.endedAt());
		verify(sessions, never()).save(any());
		assertNull(lastEvent.get());
	}

	@Test
	void stopByAnotherUser_isForbiddenAndDoesNotMutate() {
		TaskWorkSession open = openSession(LocalDateTime.now());
		when(sessions.findById(open.getId())).thenReturn(Optional.of(open));
		UUID otherUser = UUID.randomUUID();

		IntegrationException ex = assertThrows(
				IntegrationException.class, () -> service.stop(otherUser, task.getId(), open.getId()));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
		verify(sessions, never()).save(any());
		assertNull(lastEvent.get());
	}

	@Test
	void stopUnknownSession_isNotFound() {
		UUID sessionId = UUID.randomUUID();
		when(sessions.findById(sessionId)).thenReturn(Optional.empty());

		AcademicException ex =
				assertThrows(AcademicException.class, () -> service.stop(student.getId(), task.getId(), sessionId));
		assertEquals(AcademicErrorCode.TASK_NOT_FOUND, ex.getCode());
	}

	@Test
	void startOnADifferentTask_isAllowedWhileAnotherTaskIsOpen() {
		Task other = new Task();
		other.setId(UUID.randomUUID());
		other.setProject(project);
		when(tasks.lockActiveById(other.getId())).thenReturn(Optional.of(other));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
						other.getId(), student.getId(), WorkSessionStatus.OPEN))
				.thenReturn(List.of());
		stubSaveAssignsId();

		TaskWorkSessionResponse created = service.start(student.getId(), other.getId());

		assertEquals(other.getId(), created.taskId());
		assertEquals(WorkSessionStatus.OPEN.name(), created.status());
		verify(sessions, times(1)).save(any(TaskWorkSession.class));
	}

	@Test
	void startAfterStop_createsANewOpenSession() {
		stubMemberLock();
		stubStudentLookup();
		when(sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
						task.getId(), student.getId(), WorkSessionStatus.OPEN))
				.thenReturn(List.of())
				.thenReturn(List.of());
		stubSaveAssignsId();

		TaskWorkSessionResponse first = service.start(student.getId(), task.getId());
		TaskWorkSessionResponse second = service.start(student.getId(), task.getId());

		assertEquals(WorkSessionStatus.OPEN.name(), first.status());
		assertEquals(WorkSessionStatus.OPEN.name(), second.status());
		verify(sessions, times(2)).save(any(TaskWorkSession.class));
	}

	@Test
	void stopWrongTaskId_isForbidden() {
		TaskWorkSession open = openSession(LocalDateTime.now());
		when(sessions.findById(open.getId())).thenReturn(Optional.of(open));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.stop(student.getId(), UUID.randomUUID(), open.getId()));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
		verify(sessions, never()).save(any());
	}

	@Test
	void listForUser_missingTask_isNotFound() {
		when(tasks.findActiveFetchedById(task.getId())).thenReturn(Optional.empty());

		AcademicException ex =
				assertThrows(AcademicException.class, () -> service.listForUser(student.getId(), task.getId()));
		assertEquals(AcademicErrorCode.TASK_NOT_FOUND, ex.getCode());
		verify(sessions, never()).save(any());
	}

	@Test
	void threeLegacyOpenRows_getPicksEarliestAndStartDoesNotInsert() {
		LocalDateTime t0 = LocalDateTime.of(2026, 9, 14, 2, 55, 40);
		TaskWorkSession first = openSession(t0);
		TaskWorkSession second = openSession(t0.plusSeconds(11));
		TaskWorkSession third = openSession(t0.plusHours(1).plusMinutes(28));
		when(tasks.findActiveFetchedById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
		when(sessions.findByTask_IdAndUser_IdOrderByStartedAtAsc(task.getId(), student.getId()))
				.thenReturn(List.of(first, second, third));

		TaskWorkSessionsResponse view = service.listForUser(student.getId(), task.getId());

		assertEquals(first.getId(), view.activeSession().id());
		assertEquals(WorkSessionStatus.OPEN.name(), view.activeSession().status());
		assertEquals(3, view.sessions().size());
		assertEquals(first.getId(), view.sessions().get(0).id());
		assertEquals(second.getId(), view.sessions().get(1).id());
		assertEquals(third.getId(), view.sessions().get(2).id());
		verify(sessions, never()).save(any());

		when(tasks.lockActiveById(task.getId())).thenReturn(Optional.of(task));
		when(sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
						task.getId(), student.getId(), WorkSessionStatus.OPEN))
				.thenReturn(List.of(first, second, third));

		TaskWorkSessionResponse started = service.start(student.getId(), task.getId());

		assertEquals(first.getId(), started.id());
		assertEquals(WorkSessionStatus.OPEN.name(), started.status());
		verify(sessions, never()).save(any());
	}

	@Test
	void threeLegacyOpenRows_stopChosenLeavesOtherOpenRowsAsNextActive() {
		LocalDateTime t0 = LocalDateTime.of(2026, 9, 14, 2, 55, 40);
		TaskWorkSession first = openSession(t0);
		TaskWorkSession second = openSession(t0.plusSeconds(11));
		TaskWorkSession third = openSession(t0.plusHours(1).plusMinutes(28));
		when(sessions.findById(first.getId())).thenReturn(Optional.of(first));
		when(sessions.save(any(TaskWorkSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

		TaskWorkSessionResponse stopped = service.stop(student.getId(), task.getId(), first.getId());

		assertEquals(first.getId(), stopped.id());
		assertEquals(WorkSessionStatus.STOPPED.name(), stopped.status());
		assertEquals(WorkSessionStatus.OPEN, second.getStatus());
		assertEquals(WorkSessionStatus.OPEN, third.getStatus());
		assertNull(second.getEndedAt());
		assertNull(third.getEndedAt());

		when(tasks.findActiveFetchedById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
		when(sessions.findByTask_IdAndUser_IdOrderByStartedAtAsc(task.getId(), student.getId()))
				.thenReturn(List.of(first, second, third));
		when(tasks.lockActiveById(task.getId())).thenReturn(Optional.of(task));
		when(sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
						task.getId(), student.getId(), WorkSessionStatus.OPEN))
				.thenReturn(List.of(second, third));

		TaskWorkSessionsResponse view = service.listForUser(student.getId(), task.getId());
		assertEquals(second.getId(), view.activeSession().id());
		assertEquals(WorkSessionStatus.OPEN.name(), view.activeSession().status());
		assertEquals(3, view.sessions().size());

		TaskWorkSessionResponse started = service.start(student.getId(), task.getId());
		assertEquals(second.getId(), started.id());
		verify(sessions, times(1)).save(any(TaskWorkSession.class));
	}

	@Test
	void afterLegacyDuplicatesClosedWithOwnStartedAt_startReturnsRemainingOpen() {
		LocalDateTime t0 = LocalDateTime.of(2026, 9, 14, 2, 55, 40);
		TaskWorkSession canonical = openSession(t0);
		TaskWorkSession ghostA = stoppedSession(t0.plusSeconds(11), t0.plusSeconds(11));
		TaskWorkSession ghostB = stoppedSession(t0.plusHours(1).plusMinutes(28), t0.plusHours(1).plusMinutes(28));
		stubMemberLock();
		when(sessions.findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
						task.getId(), student.getId(), WorkSessionStatus.OPEN))
				.thenReturn(List.of(canonical));
		when(tasks.findActiveFetchedById(task.getId())).thenReturn(Optional.of(task));
		when(sessions.findByTask_IdAndUser_IdOrderByStartedAtAsc(task.getId(), student.getId()))
				.thenReturn(List.of(canonical, ghostA, ghostB));

		TaskWorkSessionResponse started = service.start(student.getId(), task.getId());
		TaskWorkSessionsResponse view = service.listForUser(student.getId(), task.getId());

		assertEquals(canonical.getId(), started.id());
		assertEquals(WorkSessionStatus.OPEN.name(), started.status());
		assertEquals(canonical.getId(), view.activeSession().id());
		assertEquals(3, view.sessions().size());
		assertEquals(0, view.sessions().get(1).elapsedSeconds());
		assertEquals(0, view.sessions().get(2).elapsedSeconds());
		assertEquals(WorkSessionStatus.STOPPED.name(), view.sessions().get(1).status());
		assertEquals(WorkSessionStatus.STOPPED.name(), view.sessions().get(2).status());
		verify(sessions, never()).save(any());
	}

	@Test
	void listForUser_rejectsNonMember() {
		when(tasks.findActiveFetchedById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(false);

		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> service.listForUser(student.getId(), task.getId()));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
	}

	@Test
	void confirmWithoutStepUp_isStepUpRequiredAndDoesNotPersist() {
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.confirm(student.getId(), task.getId(), null, List.of("abc"), List.of()));
		assertEquals(IntegrationErrorCode.STEP_UP_REQUIRED, ex.getCode());
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
		verify(confirmations, never()).save(any());
		assertNull(lastEvent.get());
	}

	@Test
	void confirmAfterFreshPasswordStepUp_persistsValidJsonSnapshot() {
		stubConfirmMember();
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(confirmations.save(any(ContributionConfirmation.class))).thenAnswer(invocation -> {
			ContributionConfirmation row = invocation.getArgument(0);
			row.setId(UUID.randomUUID());
			return row;
		});
		String sha = "d362a3532ceaebe36c78f7e151e881daae8c6277";

		ContributionConfirmation saved =
				service.confirm(student.getId(), task.getId(), Instant.now(), List.of(sha), List.of());

		assertEquals(ConfirmationEvent.CONFIRMED, saved.getEventState());
		assertEquals(ConfirmationMethod.PASSWORD_STEP_UP, saved.getConfirmationMethod());
		assertEquals(EvidenceHasher.canonical("SAGA-1", List.of(sha), List.of(), "TODO"), saved.getEvidenceSnapshotJson());
		assertEquals(EvidenceHasher.sha256(saved.getEvidenceSnapshotJson()), saved.getEvidenceHash());
		assertTrue(saved.getEvidenceSnapshotJson().startsWith("{"));
		assertTrue(saved.getEvidenceSnapshotJson().contains("\"commits\""));
		assertFalse(saved.getEvidenceSnapshotJson().startsWith("{commits="));
		assertEquals(ProjectRealtimeEventType.TASK_EVIDENCE_CHANGED, lastEvent.get().type());
	}

	@Test
	void confirmRejectsNonMemberAfterStepUpWithoutPersisting() {
		when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(false);

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.confirm(student.getId(), task.getId(), Instant.now(), List.of("abc"), List.of()));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
		verify(confirmations, never()).save(any());
		assertNull(lastEvent.get());
	}

	@Test
	void confirmDoesNotLookUpCommitOwnership_currentSemanticsSnapshotClientShas() {
		stubConfirmMember();
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(confirmations.save(any(ContributionConfirmation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ContributionConfirmation saved = service.confirm(
				student.getId(),
				task.getId(),
				Instant.now(),
				List.of("ffffffffffffffffffffffffffffffffffffffff"),
				List.of());

		assertTrue(saved.getEvidenceSnapshotJson().contains("ffffffffffffffffffffffffffffffffffffffff"));
		verify(confirmations, times(1)).save(any(ContributionConfirmation.class));
	}

	@Test
	void confirmPersistenceFailure_doesNotPublishRealtime() {
		stubConfirmMember();
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(confirmations.save(any(ContributionConfirmation.class))).thenThrow(new IllegalStateException("jdbc 3141"));

		assertThrows(
				IllegalStateException.class,
				() -> service.confirm(
						student.getId(),
						task.getId(),
						Instant.now(),
						List.of("d362a3532ceaebe36c78f7e151e881daae8c6277"),
						List.of()));
		assertNull(lastEvent.get());
	}

	private void stubConfirmMember() {
		when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
	}

	private void stubMemberLock() {
		when(tasks.lockActiveById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
	}

	private void stubStudentLookup() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
	}

	private void stubSaveAssignsId() {
		when(sessions.save(any(TaskWorkSession.class))).thenAnswer(invocation -> {
			TaskWorkSession row = invocation.getArgument(0);
			if (row.getId() == null) {
				row.setId(UUID.randomUUID());
			}
			return row;
		});
	}

	private TaskWorkSession openSession(LocalDateTime startedAt) {
		TaskWorkSession session = new TaskWorkSession();
		session.setId(UUID.randomUUID());
		session.setTask(task);
		session.setUser(student);
		session.setProject(project);
		session.setStartedAt(startedAt);
		session.setStatus(WorkSessionStatus.OPEN);
		return session;
	}

	private TaskWorkSession stoppedSession(LocalDateTime startedAt, LocalDateTime endedAt) {
		TaskWorkSession session = openSession(startedAt);
		session.setEndedAt(endedAt);
		session.setStatus(WorkSessionStatus.STOPPED);
		return session;
	}
}
