package com.saga.be.service.peerreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.peerreview.PeerReviewCandidatesResponse;
import com.saga.be.dto.peerreview.PeerReviewListResponse;
import com.saga.be.dto.peerreview.PeerReviewResponse;
import com.saga.be.dto.peerreview.PeerReviewRubricResponse;
import com.saga.be.dto.peerreview.SubmitPeerReviewRequest;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.assessment.PeerReview;
import com.saga.be.entity.assessment.PeerReviewDetail;
import com.saga.be.entity.assessment.RubricTemplate;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.PeerReviewDetailRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.RubricTemplateRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PeerReviewServiceTest {

	@Mock
	private TeamRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private SprintRepository sprints;
	@Mock
	private RubricTemplateRepository rubrics;
	@Mock
	private PeerReviewRepository reviews;
	@Mock
	private PeerReviewDetailRepository details;
	@Mock
	private ProjectRealtimePublisher realtime;

	private PeerReviewService service;
	private UUID teamId;
	private UUID sprintId;
	private UUID subjectId;
	private UserAccount aliceAccount;
	private UserAccount bobAccount;
	private StudentProfile alice;
	private StudentProfile bob;
	private Team team;
	private Project project;
	private Sprint sprint;
	private RubricTemplate quality;
	private RubricTemplate process;

	@BeforeEach
	void setUp() {
		service = new PeerReviewService(teams, members, sprints, rubrics, reviews, details, realtime);
		teamId = UUID.randomUUID();
		sprintId = UUID.randomUUID();
		subjectId = UUID.randomUUID();
		aliceAccount = account(AccountRole.STUDENT, "Alice");
		bobAccount = account(AccountRole.STUDENT, "Bob");
		alice = student(aliceAccount, "SE001");
		bob = student(bobAccount, "SE002");
		project = new Project();
		project.setId(UUID.randomUUID());
		project.setName("SAGA");
		Subject subject = new Subject();
		subject.setId(subjectId);
		subject.setSubjectCode("SWP");
		subject.setName("Software Project");
		Course course = new Course();
		course.setId(UUID.randomUUID());
		course.setSubject(subject);
		team = new Team();
		team.setId(teamId);
		team.setName("Team 1");
		team.setProject(project);
		team.setCourse(course);
		sprint = new Sprint();
		sprint.setId(sprintId);
		sprint.setName("Sprint 1");
		quality = rubric(null, "Hoàn thành & Chất lượng");
		process = rubric(null, "Tiến độ & Quy trình");
	}

	@Test
	void defaultRubricReturnsGlobalCriteria() {
		when(rubrics.findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc())
				.thenReturn(List.of(quality, process));
		PeerReviewRubricResponse response = service.defaultRubric();
		assertThat(response.teamId()).isNull();
		assertThat(response.subjectId()).isNull();
		assertThat(response.criteria()).extracting(PeerReviewRubricResponse.Criterion::criteriaName)
				.containsExactly("Hoàn thành & Chất lượng", "Tiến độ & Quy trình");
	}

	@Test
	void teamRubricFallsBackToGlobalWhenSubjectHasNone() {
		stubTeam();
		when(rubrics.findBySubject_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(subjectId)).thenReturn(List.of());
		when(rubrics.findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()).thenReturn(List.of(quality));
		PeerReviewRubricResponse response = service.teamRubric(aliceAccount, teamId);
		assertThat(response.teamId()).isEqualTo(teamId);
		assertThat(response.subjectId()).isNull();
		assertThat(response.criteria()).hasSize(1);
	}

	@Test
	void teamRubricUsesSubjectCriteriaWhenPresent() {
		stubTeam();
		RubricTemplate subjectRow = rubric(team.getCourse().getSubject(), "Subject criterion");
		when(rubrics.findBySubject_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(subjectId))
				.thenReturn(List.of(subjectRow));
		PeerReviewRubricResponse response = service.teamRubric(aliceAccount, teamId);
		assertThat(response.subjectId()).isEqualTo(subjectId);
		assertThat(response.criteria().getFirst().criteriaName()).isEqualTo("Subject criterion");
		verify(rubrics, never()).findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc();
	}

	@Test
	void candidatesExcludeSelfAndFlagExistingReview() {
		stubReviewerScope();
		PeerReview existing = review(alice, bob, 9);
		when(reviews.findBySprint_IdAndReviewerStudent_Id(sprintId, alice.getId())).thenReturn(List.of(existing));
		PeerReviewCandidatesResponse response = service.candidates(aliceAccount, teamId, sprintId);
		assertThat(response.reviewerId()).isEqualTo(alice.getId());
		assertThat(response.candidates()).hasSize(1);
		assertThat(response.candidates().getFirst().studentId()).isEqualTo(bob.getId());
		assertThat(response.candidates().getFirst().alreadyReviewed()).isTrue();
		assertThat(response.candidates().getFirst().existingTotalStarRating()).isEqualTo(9);
	}

	@Test
	void lecturerCannotListCandidates() {
		UserAccount lecturer = account(AccountRole.LECTURER, "GV");
		assertThatThrownBy(() -> service.candidates(lecturer, teamId, sprintId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PEER_REVIEW_FORBIDDEN);
	}

	@Test
	void submitSumsCriteriaAndUpserts() {
		stubReviewerScope();
		when(rubrics.findBySubject_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(subjectId)).thenReturn(List.of());
		when(rubrics.findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc())
				.thenReturn(List.of(quality, process));
		when(reviews.findBySprint_IdAndReviewerStudent_IdAndRevieweeStudent_Id(sprintId, alice.getId(), bob.getId()))
				.thenReturn(Optional.empty());
		when(reviews.save(any(PeerReview.class))).thenAnswer(invocation -> {
			PeerReview row = invocation.getArgument(0);
			if (row.getId() == null) {
				row.setId(UUID.randomUUID());
			}
			return row;
		});
		when(details.findByPeerReview_IdOrderByCriteriaOrderAsc(any()))
				.thenReturn(List.of())
				.thenAnswer(invocation -> {
					UUID reviewId = invocation.getArgument(0);
					return List.of(detail(reviewId, quality, 0, 5), detail(reviewId, process, 1, 4));
				});
		when(details.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

		PeerReviewResponse response = service.submit(
				aliceAccount,
				teamId,
				sprintId,
				new SubmitPeerReviewRequest(
						bob.getId(),
						null,
						List.of(
								new SubmitPeerReviewRequest.CriterionRating(quality.getId(), 5),
								new SubmitPeerReviewRequest.CriterionRating(process.getId(), 4)),
						"Phối hợp tốt"));

		assertThat(response.starRating()).isEqualTo(9);
		verify(realtime).publish(ProjectRealtimeEventType.PEER_REVIEW_CHANGED, project.getId(), response.id().toString());
		assertThat(response.reviewerId()).isEqualTo(alice.getId());
		assertThat(response.revieweeId()).isEqualTo(bob.getId());
		assertThat(response.comment()).isEqualTo("Phối hợp tốt");
		assertThat(response.criteriaRatings()).hasSize(2);
	}

	@Test
	void submitTotalOnlyWhenNoCriteria() {
		stubReviewerScope();
		when(rubrics.findBySubject_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(subjectId)).thenReturn(List.of());
		when(rubrics.findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()).thenReturn(List.of(quality));
		when(reviews.findBySprint_IdAndReviewerStudent_IdAndRevieweeStudent_Id(sprintId, alice.getId(), bob.getId()))
				.thenReturn(Optional.empty());
		when(reviews.save(any(PeerReview.class))).thenAnswer(invocation -> {
			PeerReview row = invocation.getArgument(0);
			row.setId(UUID.randomUUID());
			return row;
		});
		when(details.findByPeerReview_IdOrderByCriteriaOrderAsc(any())).thenReturn(List.of());

		PeerReviewResponse response = service.submit(
				aliceAccount, teamId, sprintId, new SubmitPeerReviewRequest(bob.getId(), 18, null, null));

		assertThat(response.starRating()).isEqualTo(18);
		assertThat(response.criteriaRatings()).isEmpty();
		verify(details, never()).saveAll(any());
	}

	@Test
	void selfReviewRejected() {
		stubReviewerScope();
		assertThatThrownBy(() -> service.submit(
						aliceAccount, teamId, sprintId, new SubmitPeerReviewRequest(alice.getId(), 5, null, null)))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PEER_REVIEW_INVALID);
	}

	@Test
	void incompleteCriteriaRejected() {
		stubReviewerScope();
		when(rubrics.findBySubject_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(subjectId)).thenReturn(List.of());
		when(rubrics.findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc())
				.thenReturn(List.of(quality, process));
		assertThatThrownBy(() -> service.submit(
						aliceAccount,
						teamId,
						sprintId,
						new SubmitPeerReviewRequest(
								bob.getId(),
								null,
								List.of(new SubmitPeerReviewRequest.CriterionRating(quality.getId(), 5)),
								null)))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PEER_REVIEW_INVALID);
	}

	@Test
	void teamWithoutProjectRejected() {
		stubTeam();
		team.setProject(null);
		assertThatThrownBy(() -> service.candidates(aliceAccount, teamId, sprintId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PEER_REVIEW_INVALID);
	}

	@Test
	void unknownSprintNotFound() {
		stubTeam();
		when(sprints.findActiveByIdAndProject_Id(sprintId, project.getId())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.candidates(aliceAccount, teamId, sprintId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
	}

	@Test
	void lecturerCanListReviews() {
		UserAccount lecturerAccount = account(AccountRole.LECTURER, "GV");
		LecturerProfile instructor = new LecturerProfile();
		instructor.setId(UUID.randomUUID());
		instructor.setUserAccount(lecturerAccount);
		team.getCourse().setInstructor(instructor);
		when(teams.findFetchedById(teamId)).thenReturn(Optional.of(team));
		when(sprints.findActiveByIdAndProject_Id(sprintId, project.getId())).thenReturn(Optional.of(sprint));
		PeerReview row = review(alice, bob, 8);
		when(reviews.findFetchedByProjectAndSprint(project.getId(), sprintId)).thenReturn(List.of(row));
		when(details.findFetchedByPeerReview_IdIn(List.of(row.getId()))).thenReturn(List.of());

		PeerReviewListResponse response = service.list(lecturerAccount, teamId, sprintId);

		assertThat(response.reviews()).hasSize(1);
		assertThat(response.reviews().getFirst().starRating()).isEqualTo(8);
	}

	@Test
	void adminCanListButNotSubmit() {
		UserAccount admin = account(AccountRole.ADMIN, "Admin");
		when(teams.findFetchedById(teamId)).thenReturn(Optional.of(team));
		when(sprints.findActiveByIdAndProject_Id(sprintId, project.getId())).thenReturn(Optional.of(sprint));
		when(reviews.findFetchedByProjectAndSprint(project.getId(), sprintId)).thenReturn(List.of());
		assertThat(service.list(admin, teamId, sprintId).reviews()).isEmpty();
		assertThatThrownBy(() -> service.submit(
						admin, teamId, sprintId, new SubmitPeerReviewRequest(bob.getId(), 5, null, null)))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PEER_REVIEW_FORBIDDEN);
	}

	private void stubTeam() {
		when(teams.findFetchedById(teamId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(teamId)).thenReturn(List.of(member(alice, RoleInTeam.LEADER), member(bob, RoleInTeam.MEMBER)));
	}

	private void stubReviewerScope() {
		stubTeam();
		when(sprints.findActiveByIdAndProject_Id(sprintId, project.getId())).thenReturn(Optional.of(sprint));
	}

	private static UserAccount account(AccountRole role, String name) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setAccountRole(role);
		account.setFullName(name);
		account.setEmail(name.toLowerCase() + "@fpt.edu.vn");
		return account;
	}

	private static StudentProfile student(UserAccount account, String code) {
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		return profile;
	}

	private TeamMember member(StudentProfile profile, RoleInTeam role) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private static RubricTemplate rubric(Subject subject, String name) {
		RubricTemplate row = new RubricTemplate();
		row.setId(UUID.randomUUID());
		row.setSubject(subject);
		row.setCriteriaName(name);
		row.setDescription(name);
		return row;
	}

	private PeerReview review(StudentProfile reviewer, StudentProfile reviewee, int stars) {
		PeerReview row = new PeerReview();
		row.setId(UUID.randomUUID());
		row.setSprint(sprint);
		row.setReviewerStudent(reviewer);
		row.setRevieweeStudent(reviewee);
		row.setStarRating(stars);
		return row;
	}

	private static PeerReviewDetail detail(UUID reviewId, RubricTemplate rubric, int order, int stars) {
		PeerReview parent = new PeerReview();
		parent.setId(reviewId);
		PeerReviewDetail row = new PeerReviewDetail();
		row.setId(UUID.randomUUID());
		row.setPeerReview(parent);
		row.setRubric(rubric);
		row.setCriteriaName(rubric.getCriteriaName());
		row.setCriteriaOrder(order);
		row.setStarRating(stars);
		return row;
	}
}
