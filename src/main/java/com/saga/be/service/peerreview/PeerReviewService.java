package com.saga.be.service.peerreview;

import com.saga.be.dto.peerreview.PeerReviewCandidatesResponse;
import com.saga.be.dto.peerreview.PeerReviewListResponse;
import com.saga.be.dto.peerreview.PeerReviewResponse;
import com.saga.be.dto.peerreview.PeerReviewRubricResponse;
import com.saga.be.dto.peerreview.SubmitPeerReviewRequest;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.assessment.PeerReview;
import com.saga.be.entity.assessment.PeerReviewDetail;
import com.saga.be.entity.assessment.RubricTemplate;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class PeerReviewService {

	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final SprintRepository sprints;
	private final RubricTemplateRepository rubrics;
	private final PeerReviewRepository reviews;
	private final PeerReviewDetailRepository details;
	private final ProjectRealtimePublisher realtime;

	public PeerReviewService(
			TeamRepository teams,
			TeamMemberRepository members,
			SprintRepository sprints,
			RubricTemplateRepository rubrics,
			PeerReviewRepository reviews,
			PeerReviewDetailRepository details,
			ProjectRealtimePublisher realtime) {
		this.teams = teams;
		this.members = members;
		this.sprints = sprints;
		this.rubrics = rubrics;
		this.reviews = reviews;
		this.details = details;
		this.realtime = realtime;
	}

	@Transactional(readOnly = true)
	public PeerReviewRubricResponse defaultRubric() {
		return new PeerReviewRubricResponse(null, null, toCriteria(globalRubrics()));
	}

	@Transactional(readOnly = true)
	public PeerReviewRubricResponse teamRubric(UserAccount actor, UUID teamId) {
		Team team = requireReadableTeam(actor, teamId);
		UUID subjectId = subjectId(team);
		List<RubricTemplate> rows = resolveRubrics(subjectId);
		UUID responseSubjectId = rows.isEmpty() || rows.getFirst().getSubject() == null ? null : subjectId;
		return new PeerReviewRubricResponse(team.getId(), responseSubjectId, toCriteria(rows));
	}

	@Transactional(readOnly = true)
	public PeerReviewCandidatesResponse candidates(UserAccount actor, UUID teamId, UUID sprintId) {
		TeamScope scope = requireReviewerScope(actor, teamId, sprintId);
		Map<UUID, PeerReview> existing = new HashMap<>();
		for (PeerReview review : reviews.findBySprint_IdAndReviewerStudent_Id(sprintId, scope.reviewer().getId())) {
			existing.put(review.getRevieweeStudent().getId(), review);
		}
		List<PeerReviewCandidatesResponse.Candidate> candidates = new ArrayList<>();
		for (TeamMember member : scope.roster()) {
			StudentProfile student = studentOf(member);
			if (student.getId().equals(scope.reviewer().getId())) {
				continue;
			}
			PeerReview prior = existing.get(student.getId());
			candidates.add(new PeerReviewCandidatesResponse.Candidate(
					student.getId(),
					fullName(student),
					student.getStudentCode(),
					prior != null,
					prior == null ? null : prior.getId(),
					prior == null ? null : prior.getStarRating()));
		}
		return new PeerReviewCandidatesResponse(teamId, sprintId, scope.reviewer().getId(), candidates);
	}

	@Transactional
	public PeerReviewResponse submit(UserAccount actor, UUID teamId, UUID sprintId, SubmitPeerReviewRequest request) {
		if (request == null || request.revieweeId() == null) {
			throw invalid("revieweeId is required.");
		}
		TeamScope scope = requireReviewerScope(actor, teamId, sprintId);
		if (request.revieweeId().equals(scope.reviewer().getId())) {
			throw invalid("Self-review is not allowed.");
		}
		TeamMember revieweeMember = scope.roster().stream()
				.filter(member -> request.revieweeId().equals(studentOf(member).getId()))
				.findFirst()
				.orElse(null);
		if (revieweeMember == null) {
			throw invalid("Reviewee is not a member of this team.");
		}
		StudentProfile reviewee = studentOf(revieweeMember);
		List<RubricTemplate> rubricRows = resolveRubrics(subjectId(scope.team()));
		RatedStars rated = rate(request, rubricRows);

		PeerReview row = reviews.findBySprint_IdAndReviewerStudent_IdAndRevieweeStudent_Id(
						sprintId, scope.reviewer().getId(), reviewee.getId())
				.orElseGet(PeerReview::new);
		row.setSprint(scope.sprint());
		row.setReviewerStudent(scope.reviewer());
		row.setRevieweeStudent(reviewee);
		row.setStarRating(rated.total());
		row.setComment(blankToNull(request.comment()));
		PeerReview saved = reviews.save(row);
		replaceDetails(saved, rated.lines());
		List<PeerReviewDetail> persisted = details.findByPeerReview_IdOrderByCriteriaOrderAsc(saved.getId());
		Project project = scope.team().getProject();
		if (project != null) {
			realtime.publish(
					ProjectRealtimeEventType.PEER_REVIEW_CHANGED, project.getId(), saved.getId().toString());
		}
		return toResponse(saved, scope.sprint(), persisted);
	}

	@Transactional(readOnly = true)
	public PeerReviewListResponse list(UserAccount actor, UUID teamId, UUID sprintId) {
		Team team = requireReadableTeam(actor, teamId);
		Sprint sprint = requireSprint(team, sprintId);
		List<PeerReview> rows = reviews.findFetchedByProjectAndSprint(team.getProject().getId(), sprintId);
		Map<UUID, List<PeerReviewDetail>> byReview = new LinkedHashMap<>();
		List<UUID> ids = rows.stream().map(PeerReview::getId).toList();
		if (!ids.isEmpty()) {
			for (PeerReviewDetail detail : details.findFetchedByPeerReview_IdIn(ids)) {
				byReview.computeIfAbsent(detail.getPeerReview().getId(), ignored -> new ArrayList<>()).add(detail);
			}
		}
		List<PeerReviewResponse> mapped = new ArrayList<>();
		for (PeerReview row : rows) {
			mapped.add(toResponse(row, sprint, byReview.getOrDefault(row.getId(), List.of())));
		}
		return new PeerReviewListResponse(team.getId(), sprint.getId(), sprint.getName(), mapped);
	}

	private RatedStars rate(SubmitPeerReviewRequest request, List<RubricTemplate> rubricRows) {
		List<SubmitPeerReviewRequest.CriterionRating> ratings = request.criteriaRatings();
		if (ratings == null || ratings.isEmpty()) {
			if (request.starRating() == null) {
				throw invalid("starRating or criteriaRatings is required.");
			}
			return new RatedStars(request.starRating(), List.of());
		}
		Map<UUID, RubricTemplate> byId = new LinkedHashMap<>();
		for (RubricTemplate row : rubricRows) {
			byId.put(row.getId(), row);
		}
		if (byId.isEmpty()) {
			throw invalid("This team has no active rubric; send starRating instead of criteriaRatings.");
		}
		Set<UUID> seen = new HashSet<>();
		List<RatedLine> lines = new ArrayList<>();
		int total = 0;
		int order = 0;
		for (SubmitPeerReviewRequest.CriterionRating rating : ratings) {
			if (rating == null || rating.rubricId() == null || rating.starRating() == null) {
				throw invalid("Each criteriaRating needs rubricId and starRating.");
			}
			if (!seen.add(rating.rubricId())) {
				throw invalid("Duplicate rubricId in criteriaRatings.");
			}
			RubricTemplate rubric = byId.get(rating.rubricId());
			if (rubric == null) {
				throw invalid("criteriaRatings must use the active team rubric.");
			}
			int stars = rating.starRating();
			total += stars;
			lines.add(new RatedLine(rubric, rubric.getCriteriaName(), order++, stars));
		}
		if (seen.size() != byId.size()) {
			throw invalid("criteriaRatings must include every active rubric criterion.");
		}
		return new RatedStars(total, lines);
	}

	private void replaceDetails(PeerReview review, List<RatedLine> lines) {
		List<PeerReviewDetail> existing = details.findByPeerReview_IdOrderByCriteriaOrderAsc(review.getId());
		if (!existing.isEmpty()) {
			details.deleteAll(existing);
			details.flush();
		}
		if (lines.isEmpty()) {
			return;
		}
		List<PeerReviewDetail> rows = new ArrayList<>(lines.size());
		for (RatedLine line : lines) {
			PeerReviewDetail detail = new PeerReviewDetail();
			detail.setPeerReview(review);
			detail.setRubric(line.rubric());
			detail.setCriteriaName(line.criteriaName() == null ? "" : line.criteriaName());
			detail.setCriteriaOrder(line.order());
			detail.setStarRating(line.stars());
			rows.add(detail);
		}
		details.saveAll(rows);
	}

	private List<RubricTemplate> resolveRubrics(UUID subjectId) {
		if (subjectId != null) {
			List<RubricTemplate> subjectRows =
					rubrics.findBySubject_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(subjectId);
			if (!subjectRows.isEmpty()) {
				return subjectRows;
			}
		}
		return globalRubrics();
	}

	private List<RubricTemplate> globalRubrics() {
		return rubrics.findBySubjectIsNullAndDeletedAtIsNullOrderByCreatedAtAscIdAsc();
	}

	private TeamScope requireReviewerScope(UserAccount actor, UUID teamId, UUID sprintId) {
		if (actor == null || actor.getAccountRole() != AccountRole.STUDENT) {
			throw forbidden("Only a student on this team can submit or list peer-review candidates.");
		}
		Team team = requireTeam(teamId);
		List<TeamMember> roster = activeRoster(team);
		StudentProfile reviewer = reviewerOnTeam(actor, roster);
		Sprint sprint = requireSprint(team, sprintId);
		return new TeamScope(team, roster, reviewer, sprint);
	}

	private Team requireReadableTeam(UserAccount actor, UUID teamId) {
		Team team = requireTeam(teamId);
		if (actor != null && actor.getAccountRole() == AccountRole.ADMIN) {
			return team;
		}
		if (actor != null && actor.getAccountRole() == AccountRole.LECTURER) {
			requireAssignedLecturer(actor, team);
			return team;
		}
		if (actor != null && actor.getAccountRole() == AccountRole.STUDENT) {
			reviewerOnTeam(actor, activeRoster(team));
			return team;
		}
		throw forbidden("Not allowed to view peer reviews.");
	}

	private Team requireTeam(UUID teamId) {
		return teams.findFetchedById(teamId)
				.or(() -> teams.findById(teamId))
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TEAM_NOT_FOUND, HttpStatus.NOT_FOUND, "Team was not found."));
	}

	private Sprint requireSprint(Team team, UUID sprintId) {
		Project project = team.getProject();
		if (project == null) {
			throw invalid("Team has no project.");
		}
		return sprints.findActiveByIdAndProject_Id(sprintId, project.getId())
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Sprint was not found for this team's project."));
	}

	private StudentProfile reviewerOnTeam(UserAccount actor, List<TeamMember> roster) {
		for (TeamMember member : roster) {
			StudentProfile profile = studentOf(member);
			if (profile.getUserAccount() != null && actor.getId().equals(profile.getUserAccount().getId())) {
				return profile;
			}
		}
		throw forbidden("Not a member of this team.");
	}

	private void requireAssignedLecturer(UserAccount actor, Team team) {
		if (team.getCourse() == null
				|| team.getCourse().getInstructor() == null
				|| team.getCourse().getInstructor().getUserAccount() == null
				|| !actor.getId().equals(team.getCourse().getInstructor().getUserAccount().getId())) {
			throw new AcademicException(
					AcademicErrorCode.LECTURER_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Lecturer is not assigned to this course.");
		}
	}

	private List<TeamMember> activeRoster(Team team) {
		return members.findFetchedByTeam_Id(team.getId()).stream()
				.filter(member -> member.getCourseEnrollment() != null
						&& member.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE)
				.toList();
	}

	private static UUID subjectId(Team team) {
		Course course = team.getCourse();
		if (course == null || course.getSubject() == null) {
			return null;
		}
		return course.getSubject().getId();
	}

	private static StudentProfile studentOf(TeamMember member) {
		return member.getCourseEnrollment().getStudentProfile();
	}

	private static String fullName(StudentProfile student) {
		if (student.getUserAccount() == null) {
			return student.getStudentCode();
		}
		String name = student.getUserAccount().getFullName();
		return name == null || name.isBlank() ? student.getStudentCode() : name;
	}

	private static List<PeerReviewRubricResponse.Criterion> toCriteria(List<RubricTemplate> rows) {
		List<PeerReviewRubricResponse.Criterion> criteria = new ArrayList<>();
		for (RubricTemplate row : rows) {
			criteria.add(new PeerReviewRubricResponse.Criterion(
					row.getId(), row.getCriteriaName(), row.getDescription()));
		}
		return criteria;
	}

	private static PeerReviewResponse toResponse(PeerReview row, Sprint sprint, List<PeerReviewDetail> lines) {
		List<PeerReviewResponse.CriterionRating> ratings = new ArrayList<>();
		for (PeerReviewDetail line : lines) {
			UUID rubricId = line.getRubric() == null ? null : line.getRubric().getId();
			ratings.add(new PeerReviewResponse.CriterionRating(rubricId, line.getCriteriaName(), line.getStarRating()));
		}
		return new PeerReviewResponse(
				row.getId(),
				sprint.getId(),
				sprint.getName(),
				row.getReviewerStudent().getId(),
				fullName(row.getReviewerStudent()),
				row.getRevieweeStudent().getId(),
				fullName(row.getRevieweeStudent()),
				row.getStarRating(),
				ratings,
				row.getComment(),
				row.getCreatedAt(),
				row.getUpdatedAt());
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.PEER_REVIEW_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	private static AcademicException forbidden(String message) {
		return new AcademicException(AcademicErrorCode.PEER_REVIEW_FORBIDDEN, HttpStatus.FORBIDDEN, message);
	}

	private record TeamScope(Team team, List<TeamMember> roster, StudentProfile reviewer, Sprint sprint) {}

	private record RatedStars(int total, List<RatedLine> lines) {}

	private record RatedLine(RubricTemplate rubric, String criteriaName, int order, int stars) {}
}
