package com.saga.be.service.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.AuthProperties;
import com.saga.be.config.RosterProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.team.LecturerCourseTeamsResponse;
import com.saga.be.dto.team.TeamConfirmResponse;
import com.saga.be.dto.team.TeamPreviewResponse;
import com.saga.be.dto.team.TeamPreviewRow;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TeamRowAction;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.mail.EmailOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class LecturerTeamServiceTest {

	@Mock
	private LecturerCourseAuthorization authorization;
	@Mock
	private EmailOutboxService emails;
	@Mock
	private AuditService audit;

	private InMemoryLecturerTeamStore store;
	private InMemoryTeamPreviewStore previews;
	private LecturerTeamService service;
	private UserAccount lecturer;
	private Course course;
	private CourseEnrollment alpha;
	private CourseEnrollment beta;

	@BeforeEach
	void setUp() {
		store = new InMemoryLecturerTeamStore();
		previews = new InMemoryTeamPreviewStore();
		RosterProperties rosterProperties = new RosterProperties();
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		service = new LecturerTeamService(
				authorization, store, previews, rosterProperties, authProperties, emails, audit);
		lecturer = account(AccountRole.LECTURER, "lecturer@fe.edu.vn");
		course = course("SE1705");
		org.mockito.Mockito.lenient()
				.when(authorization.requireCourse(any(), eq(course.getId())))
				.thenReturn(course);
		alpha = enroll("SE111111", "Alpha Student", "alpha@gmail.com");
		beta = enroll("SE222222", "Beta Student", "beta@gmail.com");
	}

	@Test
	void templatePrefillsActiveRosterOnly() throws Exception {
		enrollWithdrawn("SE333333", "Gone", "gone@gmail.com");
		byte[] bytes = service.template(lecturer, course.getId());
		List<LecturerTeamWorkbook.RawRow> rows = LecturerTeamWorkbook.parse(bytes);
		assertEquals(2, rows.size());
		assertTrue(rows.stream().anyMatch(row -> "SE111111".equals(row.studentCode())));
		assertTrue(rows.stream().noneMatch(row -> "SE333333".equals(row.studentCode())));
	}

	@Test
	void validCreatePreviewAndConfirmCreatesTeamsWithoutProjects() throws Exception {
		TeamPreviewResponse preview = preview(validAssignment());
		assertFalse(preview.hasBlockingErrors());
		assertEquals(TeamRowAction.READY_CREATE, preview.rows().getFirst().action());
		TeamConfirmResponse confirmed = service.confirm(lecturer, course.getId(), preview.previewToken(), auditReq());
		assertEquals(1, confirmed.createdTeams());
		assertEquals(2, confirmed.assignedMembers());
		assertEquals(2, confirmed.emailsEnqueued());
		assertEquals(1, store.teams.size());
		Team team = store.teams.values().iterator().next();
		assertEquals(1, team.getTeamNo());
		assertNull(team.getProject());
		assertEquals("Alpha", team.getName());
		assertEquals(2, store.members.size());
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails, times(2)).enqueue(captor.capture());
		assertEquals("TEAM_ASSIGNED", captor.getAllValues().getFirst().emailType());
		assertEquals(EmailTemplateService.TEAM_ASSIGNED, captor.getAllValues().getFirst().templateKey());
		assertTrue(String.valueOf(captor.getAllValues().getFirst().payload().get("htmlBody")).contains("assigned to a team"));
		assertTrue(previews.find(preview.previewToken()).isEmpty());
	}

	@Test
	void omittedActiveStudentBlocksPreview() throws Exception {
		TeamPreviewResponse preview = preview(List.<String[]>of(row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader")));
		assertTrue(preview.hasBlockingErrors());
		assertTrue(preview.blockingErrors().stream().anyMatch(error -> error.contains("SE222222")));
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.confirm(lecturer, course.getId(), preview.previewToken(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_CONFIRM_BLOCKED, ex.getCode());
		assertTrue(store.teams.isEmpty());
	}

	@Test
	void duplicateStudentIdentityEditAndOutsideCourseAreConflicts() throws Exception {
		TeamPreviewResponse duplicate = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Member"),
				row("3", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Alpha", "Member")));
		assertEquals(TeamRowAction.CONFLICT, duplicate.rows().get(1).action());

		TeamPreviewResponse identity = preview(List.of(
				row("1", "SE1705", "Edited Name", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Alpha", "Member")));
		assertTrue(identity.rows().getFirst().errors().stream().anyMatch(error -> error.contains("FullName")));

		TeamPreviewResponse outsider = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Ghost", "SE999999", "ghost@gmail.com", "1", "Alpha", "Member")));
		assertTrue(outsider.rows().get(1).errors().stream().anyMatch(error -> error.contains("ACTIVE enrollment")));
	}

	@Test
	void withdrawnAndCompletedStudentsCannotBeAssigned() throws Exception {
		CourseEnrollment withdrawn = enrollWithdrawn("SE333333", "Gone", "gone@gmail.com");
		withdrawn.setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
		TeamPreviewResponse preview = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Alpha", "Member"),
				row("3", "SE1705", "Gone", "SE333333", "gone@gmail.com", "1", "Alpha", "Member")));
		assertTrue(preview.rows().get(2).errors().stream().anyMatch(error -> error.contains("ACTIVE enrollment")));
	}

	@Test
	void teamNoRoleAndLeaderRulesAreValidated() throws Exception {
		assertTrue(preview(List.of(
						row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "0", "Alpha", "Leader"),
						row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "0", "Alpha", "Member")))
				.hasBlockingErrors());
		assertTrue(preview(List.of(
						row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Mentor"),
						row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Alpha", "Member")))
				.rows()
				.getFirst()
				.errors()
				.stream()
				.anyMatch(error -> error.contains("MENTOR")));
		TeamPreviewResponse missingLeader = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Member"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Alpha", "Member")));
		assertTrue(missingLeader.rows().getFirst().errors().stream().anyMatch(error -> error.contains("missing a Leader")));
		TeamPreviewResponse twoLeaders = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Alpha", "Leader")));
		assertTrue(twoLeaders.rows().getFirst().errors().stream().anyMatch(error -> error.contains("more than one Leader")));
		TeamPreviewResponse names = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Beta", "Member")));
		assertTrue(names.rows().get(1).errors().stream().anyMatch(error -> error.contains("TeamName does not match")));
		TeamPreviewResponse duplicateNames = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "2", "Alpha", "Leader")));
		assertTrue(duplicateNames.rows().getFirst().errors().stream().anyMatch(error -> error.contains("already used")));
	}

	@Test
	void oversizedFileIsRejected() {
		RosterProperties tiny = new RosterProperties();
		tiny.setMaxFileBytes(4);
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		LecturerTeamService tight = new LecturerTeamService(
				authorization, store, previews, tiny, authProperties, emails, audit);
		AcademicException ex =
				assertThrows(AcademicException.class, () -> tight.preview(lecturer, course.getId(), new byte[] {1, 2, 3, 4, 5}));
		assertEquals(AcademicErrorCode.TEAM_FILE_TOO_LARGE, ex.getCode());
	}

	@Test
	void reassignmentRoleChangeAndIdempotentConfirm() throws Exception {
		service.confirm(lecturer, course.getId(), preview(validAssignment()).previewToken(), auditReq());
		TeamPreviewResponse same = preview(validAssignment());
		assertEquals(TeamRowAction.ALREADY_ASSIGNED, same.rows().getFirst().action());
		TeamConfirmResponse noop = service.confirm(lecturer, course.getId(), same.previewToken(), auditReq());
		assertEquals(2, noop.unchanged());
		assertEquals(0, noop.emailsEnqueued());
		verify(emails, times(2)).enqueue(any());

		TeamPreviewResponse moved = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "2", "Bravo", "Leader"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "2", "Bravo", "Member")));
		assertEquals(TeamRowAction.READY_REASSIGN, moved.rows().getFirst().action());
		TeamConfirmResponse reassigned = service.confirm(lecturer, course.getId(), moved.previewToken(), auditReq());
		assertEquals(1, reassigned.createdTeams());
		assertEquals(2, reassigned.reassignedMembers());
		assertEquals(2, store.members.size());
		assertTrue(store.members.values().stream().allMatch(row -> Integer.valueOf(2).equals(row.getTeam().getTeamNo())));

		TeamPreviewResponse roleChange = preview(List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "2", "Bravo", "Member"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "2", "Bravo", "Leader")));
		assertEquals(TeamRowAction.READY_REASSIGN, roleChange.rows().getFirst().action());
		TeamConfirmResponse roles = service.confirm(lecturer, course.getId(), roleChange.previewToken(), auditReq());
		assertEquals(2, roles.updatedRoles());
		assertEquals(0, roles.createdTeams());
	}

	@Test
	void previewTokenIsBoundAndRetainedAfterFailedConfirm() throws Exception {
		TeamPreviewResponse preview = preview(validAssignment());
		UserAccount other = account(AccountRole.LECTURER, "other@fe.edu.vn");
		AcademicException mismatch = assertThrows(
				AcademicException.class,
				() -> service.confirm(other, course.getId(), preview.previewToken(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_PREVIEW_MISMATCH, mismatch.getCode());
		assertEquals(HttpStatus.FORBIDDEN, mismatch.getStatus());

		Course otherCourse = course("SE1706");
		when(authorization.requireCourse(any(), eq(otherCourse.getId()))).thenReturn(otherCourse);
		AcademicException wrongCourse = assertThrows(
				AcademicException.class,
				() -> service.confirm(lecturer, otherCourse.getId(), preview.previewToken(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_PREVIEW_MISMATCH, wrongCourse.getCode());

		when(emails.enqueue(any())).thenThrow(new RuntimeException("blocked"));
		assertThrows(RuntimeException.class, () -> service.confirm(lecturer, course.getId(), preview.previewToken(), auditReq()));
		assertTrue(store.teams.isEmpty());
		assertTrue(store.members.isEmpty());
		assertTrue(previews.find(preview.previewToken()).isPresent());
		verify(audit, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void expiredTokenAndSingleUse() throws Exception {
		TeamPreviewResponse preview = preview(validAssignment());
		previews.delete(preview.previewToken());
		AcademicException expired = assertThrows(
				AcademicException.class,
				() -> service.confirm(lecturer, course.getId(), preview.previewToken(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_PREVIEW_EXPIRED, expired.getCode());
		TeamPreviewResponse fresh = preview(validAssignment());
		service.confirm(lecturer, course.getId(), fresh.previewToken(), auditReq());
		AcademicException reused = assertThrows(
				AcademicException.class,
				() -> service.confirm(lecturer, course.getId(), fresh.previewToken(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_PREVIEW_EXPIRED, reused.getCode());
	}

	@Test
	void lecturerTeamsAreOrderedAndExistingAssignmentIsReadyAssign() throws Exception {
		Team existing = new Team();
		existing.setCourse(course);
		existing.setTeamNo(1);
		existing.setName("Alpha");
		store.saveTeam(existing);
		TeamPreviewResponse preview = preview(validAssignment());
		assertEquals(TeamRowAction.READY_ASSIGN, preview.rows().getFirst().action());
		service.confirm(lecturer, course.getId(), preview.previewToken(), auditReq());
		LecturerCourseTeamsResponse listed = service.listTeams(lecturer, course.getId());
		assertEquals(1, listed.teams().size());
		assertEquals("LEADER", listed.teams().getFirst().members().getFirst().role());
		assertNull(listed.teams().getFirst().projectId());
		assertEquals(store.members.values().iterator().next().getId(), listed.teams().getFirst().members().getFirst().teamMemberId());
	}

	@Test
	void replaceLeaderAtomicallySwapsRoles() throws Exception {
		service.confirm(lecturer, course.getId(), preview(validAssignment()).previewToken(), auditReq());
		Team team = store.teams.values().iterator().next();
		TeamMember leader = store.members.values().stream()
				.filter(row -> row.getRoleInTeam() == RoleInTeam.LEADER)
				.findFirst()
				.orElseThrow();
		TeamMember member = store.members.values().stream()
				.filter(row -> row.getRoleInTeam() == RoleInTeam.MEMBER)
				.findFirst()
				.orElseThrow();

		LecturerCourseTeamsResponse updated =
				service.replaceLeader(lecturer, course.getId(), team.getId(), member.getId(), auditReq());

		assertEquals(1, updated.teams().size());
		assertEquals(
				1,
				updated.teams().getFirst().members().stream().filter(row -> "LEADER".equals(row.role())).count());
		assertEquals(RoleInTeam.MEMBER, leader.getRoleInTeam());
		assertEquals(RoleInTeam.LEADER, member.getRoleInTeam());
		assertEquals(member.getId(), updated.teams().getFirst().members().getFirst().teamMemberId());
	}

	@Test
	void moveMemberBetweenTeamsKeepsSingleEnrollmentAndProjectOnSource() throws Exception {
		service.confirm(lecturer, course.getId(), preview(validAssignment()).previewToken(), auditReq());
		CourseEnrollment gamma = enroll("SE333333", "Gamma Student", "gamma@gmail.com");
		CourseEnrollment delta = enroll("SE444444", "Delta Student", "delta@gmail.com");
		Team teamA = store.teams.values().iterator().next();
		com.saga.be.entity.project.Project project = new com.saga.be.entity.project.Project();
		project.setId(UUID.randomUUID());
		teamA.setProject(project);
		store.saveTeam(teamA);

		Team teamB = new Team();
		teamB.setCourse(course);
		teamB.setTeamNo(2);
		teamB.setName("Bravo");
		store.saveTeam(teamB);
		TeamMember gammaLeader = new TeamMember();
		gammaLeader.setTeam(teamB);
		gammaLeader.setCourse(course);
		gammaLeader.setCourseEnrollment(gamma);
		gammaLeader.setRoleInTeam(RoleInTeam.LEADER);
		store.saveMember(gammaLeader);
		TeamMember deltaMember = new TeamMember();
		deltaMember.setTeam(teamB);
		deltaMember.setCourse(course);
		deltaMember.setCourseEnrollment(delta);
		deltaMember.setRoleInTeam(RoleInTeam.MEMBER);
		store.saveMember(deltaMember);

		TeamMember moving = store.members.values().stream()
				.filter(row -> beta.getId().equals(row.getCourseEnrollment().getId()))
				.findFirst()
				.orElseThrow();

		LecturerCourseTeamsResponse moved =
				service.moveMember(lecturer, course.getId(), moving.getId(), teamB.getId(), auditReq());

		assertEquals(2, moved.teams().size());
		assertEquals(teamB.getId(), moving.getTeam().getId());
		assertEquals(
				1,
				store.members.values().stream()
						.filter(row -> beta.getId().equals(row.getCourseEnrollment().getId()))
						.count());
		assertEquals(project.getId(), teamA.getProject().getId());
		assertNull(teamB.getProject());
		assertEquals(
				RoleInTeam.LEADER,
				store.members.values().stream()
						.filter(row -> alpha.getId().equals(row.getCourseEnrollment().getId()))
						.findFirst()
						.orElseThrow()
						.getRoleInTeam());
	}

	@Test
	void movingMemberIntoEmptyTeamIsBlocked() throws Exception {
		service.confirm(lecturer, course.getId(), preview(validAssignment()).previewToken(), auditReq());
		Team teamA = store.teams.values().iterator().next();
		Team empty = new Team();
		empty.setCourse(course);
		empty.setTeamNo(2);
		empty.setName("Bravo");
		store.saveTeam(empty);
		TeamMember member = store.members.values().stream()
				.filter(row -> row.getRoleInTeam() == RoleInTeam.MEMBER)
				.findFirst()
				.orElseThrow();
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.moveMember(lecturer, course.getId(), member.getId(), empty.getId(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_LEADER_INVALID, ex.getCode());
		assertEquals(teamA.getId(), member.getTeam().getId());
	}

	@Test
	void replaceLeaderRejectsMemberFromAnotherTeam() throws Exception {
		service.confirm(lecturer, course.getId(), preview(validAssignment()).previewToken(), auditReq());
		Team teamA = store.teams.values().iterator().next();
		CourseEnrollment gamma = enroll("SE333333", "Gamma Student", "gamma@gmail.com");
		CourseEnrollment delta = enroll("SE444444", "Delta Student", "delta@gmail.com");
		Team teamB = new Team();
		teamB.setCourse(course);
		teamB.setTeamNo(2);
		teamB.setName("Bravo");
		store.saveTeam(teamB);
		TeamMember gammaLeader = new TeamMember();
		gammaLeader.setTeam(teamB);
		gammaLeader.setCourse(course);
		gammaLeader.setCourseEnrollment(gamma);
		gammaLeader.setRoleInTeam(RoleInTeam.LEADER);
		store.saveMember(gammaLeader);
		TeamMember deltaMember = new TeamMember();
		deltaMember.setTeam(teamB);
		deltaMember.setCourse(course);
		deltaMember.setCourseEnrollment(delta);
		deltaMember.setRoleInTeam(RoleInTeam.MEMBER);
		store.saveMember(deltaMember);

		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.replaceLeader(lecturer, course.getId(), teamA.getId(), deltaMember.getId(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_NOT_FOUND, ex.getCode());
		assertEquals(RoleInTeam.LEADER, store.members.values().stream()
				.filter(row -> alpha.getId().equals(row.getCourseEnrollment().getId()))
				.findFirst()
				.orElseThrow()
				.getRoleInTeam());
	}

	@Test
	void movingOnlyLeaderIsBlocked() throws Exception {
		service.confirm(lecturer, course.getId(), preview(validAssignment()).previewToken(), auditReq());
		Team teamA = store.teams.values().iterator().next();
		Team teamB = new Team();
		teamB.setCourse(course);
		teamB.setTeamNo(2);
		teamB.setName("Bravo");
		store.saveTeam(teamB);
		CourseEnrollment gamma = enroll("SE333333", "Gamma Student", "gamma@gmail.com");
		TeamMember gammaLeader = new TeamMember();
		gammaLeader.setTeam(teamB);
		gammaLeader.setCourse(course);
		gammaLeader.setCourseEnrollment(gamma);
		gammaLeader.setRoleInTeam(RoleInTeam.LEADER);
		store.saveMember(gammaLeader);

		TeamMember leader = store.members.values().stream()
				.filter(row -> row.getRoleInTeam() == RoleInTeam.LEADER && teamA.getId().equals(row.getTeam().getId()))
				.findFirst()
				.orElseThrow();
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.moveMember(lecturer, course.getId(), leader.getId(), teamB.getId(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_LEADER_INVALID, ex.getCode());
		assertEquals(teamA.getId(), leader.getTeam().getId());
	}

	@Test
	void crossCourseTargetTeamIsBlocked() throws Exception {
		service.confirm(lecturer, course.getId(), preview(validAssignment()).previewToken(), auditReq());
		Course other = course("SE1706");
		Team foreign = new Team();
		foreign.setCourse(other);
		foreign.setTeamNo(1);
		foreign.setName("Foreign");
		store.saveTeam(foreign);
		TeamMember member = store.members.values().stream()
				.filter(row -> row.getRoleInTeam() == RoleInTeam.MEMBER)
				.findFirst()
				.orElseThrow();
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.moveMember(lecturer, course.getId(), member.getId(), foreign.getId(), auditReq()));
		assertEquals(AcademicErrorCode.TEAM_NOT_FOUND, ex.getCode());
	}

	@Test
	void unrelatedLecturerIsForbiddenByAuthorization() {
		UserAccount other = account(AccountRole.LECTURER, "other@fe.edu.vn");
		when(authorization.requireCourse(eq(other), eq(course.getId())))
				.thenThrow(new AcademicException(
						AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, HttpStatus.FORBIDDEN, "forbidden"));
		AcademicException ex =
				assertThrows(AcademicException.class, () -> service.listTeams(other, course.getId()));
		assertEquals(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, ex.getCode());
	}

	private TeamPreviewResponse preview(List<String[]> rows) throws Exception {
		return service.preview(lecturer, course.getId(), LecturerTeamWorkbookTest.filledWorkbook("SE1705", rows));
	}

	private List<String[]> validAssignment() {
		return List.of(
				row("1", "SE1705", "Alpha Student", "SE111111", "alpha@gmail.com", "1", "Alpha", "Leader"),
				row("2", "SE1705", "Beta Student", "SE222222", "beta@gmail.com", "1", "Alpha", "Member"));
	}

	private static String[] row(
			String no,
			String classCode,
			String fullName,
			String studentCode,
			String email,
			String teamNo,
			String teamName,
			String teamRole) {
		return new String[] {no, classCode, fullName, studentCode, email, teamNo, teamName, teamRole};
	}

	private CourseEnrollment enroll(String studentCode, String fullName, String email) {
		return enroll(studentCode, fullName, email, EnrollmentStatus.ACTIVE);
	}

	private CourseEnrollment enrollWithdrawn(String studentCode, String fullName, String email) {
		return enroll(studentCode, fullName, email, EnrollmentStatus.WITHDRAWN);
	}

	private CourseEnrollment enroll(String studentCode, String fullName, String email, EnrollmentStatus status) {
		UserAccount user = account(AccountRole.STUDENT, email);
		user.setFullName(fullName);
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(user);
		profile.setStudentCode(studentCode);
		profile.setVersion(0L);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(status);
		enrollment.setEnrolledAt(LocalDateTime.now());
		store.putEnrollment(enrollment);
		return enrollment;
	}

	private UserAccount account(AccountRole role, String email) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private Course course(String classCode) {
		Semester semester = new Semester();
		semester.setId(UUID.randomUUID());
		semester.setCode("FA26");
		semester.setName("Fall 2026");
		AcademicClass academicClass = new AcademicClass();
		academicClass.setId(UUID.randomUUID());
		academicClass.setClassCode(classCode);
		academicClass.setName(classCode);
		academicClass.setSemester(semester);
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode("SWP391");
		subject.setName("Software Development Project");
		Course created = new Course();
		created.setId(UUID.randomUUID());
		created.setName("SWP391 · " + classCode);
		created.setCourseCode("SWP391");
		created.setAcademicClass(academicClass);
		created.setSemester(semester);
		created.setSubject(subject);
		store.putCourse(created);
		return created;
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-1", "127.0.0.1", "JUnit");
	}
}
