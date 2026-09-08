package com.saga.be.service.contribution;

import com.saga.be.dto.contribution.ContributionEvaluationResponse;
import com.saga.be.dto.contribution.ContributionMemberResponse;
import com.saga.be.dto.contribution.ContributionOverrideRequest;
import com.saga.be.dto.contribution.ContributionOverrideResponse;
import com.saga.be.dto.contribution.ContributionSliceWeightsResponse;
import com.saga.be.dto.contribution.ContributionSprintBreakdownResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.assessment.ContributionOverride;
import com.saga.be.entity.assessment.PeerReview;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.ContributionCriterion;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.jira.TaskAttachment;
import com.saga.be.entity.jira.TaskFile;
import com.saga.be.entity.jira.TaskWebLink;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.ContributionOverrideRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.contribution.ReservedContributionMarkerClassifier.Outcome;
import com.saga.be.service.contribution.SprintFirstContributionMixer.MemberResult;
import com.saga.be.service.contribution.SprintFirstContributionMixer.OverrideFact;
import com.saga.be.service.contribution.SprintFirstContributionMixer.PeerFact;
import com.saga.be.service.contribution.SprintFirstContributionMixer.SprintSlice;
import com.saga.be.service.contribution.SprintFirstContributionMixer.TaskFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TeamContributionService {

	public static final String OVERRIDE_TYPE = "TEAM_CONTRIBUTION_OVERRIDE";

	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final TaskRepository tasks;
	private final TaskAttachmentRepository attachments;
	private final TaskFileRepository uploadedFiles;
	private final TaskWebLinkRepository webLinks;
	private final PeerReviewRepository peerReviews;
	private final ContributionOverrideRepository overrides;
	private final StudentProfileRepository students;
	private final ContributionSliceWeightResolver weights;
	private final AuditService audit;

	public TeamContributionService(
			TeamRepository teams,
			TeamMemberRepository members,
			TaskRepository tasks,
			TaskAttachmentRepository attachments,
			TaskFileRepository uploadedFiles,
			TaskWebLinkRepository webLinks,
			PeerReviewRepository peerReviews,
			ContributionOverrideRepository overrides,
			StudentProfileRepository students,
			ContributionSliceWeightResolver weights,
			AuditService audit) {
		this.teams = teams;
		this.members = members;
		this.tasks = tasks;
		this.attachments = attachments;
		this.uploadedFiles = uploadedFiles;
		this.webLinks = webLinks;
		this.peerReviews = peerReviews;
		this.overrides = overrides;
		this.students = students;
		this.weights = weights;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public ContributionEvaluationResponse evaluate(UserAccount actor, UUID teamId) {
		Team team = requireReadableTeam(actor, teamId);
		return evaluateTeam(team);
	}

	@Transactional
	public ContributionOverrideResponse override(
			UserAccount actor, UUID teamId, ContributionOverrideRequest request, AuditRequest auditRequest) {
		Team team = requireWritableTeam(actor, teamId);
		if (request == null || request.studentProfileId() == null || request.percentage() == null) {
			throw new AcademicException(
					AcademicErrorCode.CONTRIBUTION_OVERRIDE_INVALID,
					HttpStatus.BAD_REQUEST,
					"studentProfileId and percentage are required.");
		}
		if (request.percentage().compareTo(BigDecimal.ZERO) < 0
				|| request.percentage().compareTo(BigDecimal.valueOf(100)) > 0) {
			throw new AcademicException(
					AcademicErrorCode.CONTRIBUTION_OVERRIDE_INVALID,
					HttpStatus.BAD_REQUEST,
					"Override percentage must be between 0 and 100.");
		}
		boolean onTeam = members.findFetchedByTeam_Id(teamId).stream()
				.anyMatch(member -> member.getCourseEnrollment()
						.getStudentProfile()
						.getId()
						.equals(request.studentProfileId()));
		if (!onTeam) {
			throw new AcademicException(
					AcademicErrorCode.CONTRIBUTION_OVERRIDE_INVALID,
					HttpStatus.BAD_REQUEST,
					"Student is not a member of this team.");
		}
		ContributionEvaluationResponse current = evaluateTeam(team);
		BigDecimal oldValue = current.members().stream()
				.filter(row -> row.studentProfileId().equals(request.studentProfileId()))
				.map(ContributionMemberResponse::finalContributionPercentage)
				.findFirst()
				.orElse(BigDecimal.ZERO);
		ContributionOverride row = new ContributionOverride();
		row.setCourse(team.getCourse());
		row.setTeam(team);
		StudentProfile profile = students
				.findById(request.studentProfileId())
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.CONTRIBUTION_OVERRIDE_INVALID,
						HttpStatus.BAD_REQUEST,
						"Student is not a member of this team."));
		row.setStudentProfile(profile);
		row.setOverrideType(OVERRIDE_TYPE);
		row.setOldValue(oldValue);
		row.setNewValue(request.percentage());
		row.setReason(request.reason());
		row.setCreatedBy(actor);
		ContributionOverride saved = overrides.save(row);
		if (audit != null) {
			audit.record(
					actor,
					team.getProject(),
					team,
					"CONTRIBUTION_OVERRIDE_CREATED",
					"contribution_override",
					saved.getId(),
					Map.of("oldValue", oldValue),
					Map.of("newValue", request.percentage()),
					Map.of("studentProfileId", request.studentProfileId().toString()),
					AuditSource.API,
					auditRequest == null ? null : auditRequest.requestId(),
					auditRequest == null ? null : auditRequest.ip(),
					auditRequest == null ? null : auditRequest.userAgent());
		}
		return new ContributionOverrideResponse(
				saved.getId(), request.studentProfileId(), oldValue, request.percentage(), request.reason());
	}

	ContributionEvaluationResponse evaluateTeam(Team team) {
		List<TeamMember> roster = members.findFetchedByTeam_Id(team.getId());
		Project project = team.getProject();
		if (project == null || roster.isEmpty()) {
			return new ContributionEvaluationResponse(
					team.getId(),
					project == null ? null : project.getId(),
					team.getCourse().getId(),
					team.getCourse().getContributionConfigMode(),
					toWeightResponse(ContributionSliceWeights.fromCourse(team.getCourse())),
					List.of());
		}
		ContributionSliceWeights sliceWeights = weights.resolve(team);
		List<SprintFirstContributionMixer.Member> mixerMembers = new ArrayList<>();
		Map<UUID, TeamMember> memberByStudent = new LinkedHashMap<>();
		for (TeamMember member : roster) {
			UUID studentId = member.getCourseEnrollment().getStudentProfile().getId();
			mixerMembers.add(new SprintFirstContributionMixer.Member(studentId));
			memberByStudent.put(studentId, member);
		}
		List<Task> projectTasks = tasks.findActiveFetchedByProject_Id(project.getId());
		Set<UUID> taskIds = projectTasks.stream().map(Task::getId).collect(Collectors.toSet());
		Set<UUID> evidenced = new HashSet<>();
		if (!taskIds.isEmpty()) {
			for (TaskAttachment attachment : attachments.findByTask_IdIn(taskIds)) {
				evidenced.add(attachment.getTask().getId());
			}
			for (TaskWebLink link : webLinks.findByTask_IdIn(taskIds)) {
				evidenced.add(link.getTask().getId());
			}
			for (TaskFile file : uploadedFiles.findByTask_IdIn(taskIds)) {
				evidenced.add(file.getTask().getId());
			}
		}
		List<TaskFact> facts = new ArrayList<>();
		for (Task task : projectTasks) {
			if (task.getAssigneeStudent() == null) {
				continue;
			}
			Outcome outcome = ReservedContributionMarkerClassifier.classify(TaskLabelParser.parse(task.getLabelsJson()));
			ContributionCriterion criterion = ReservedContributionMarkerClassifier.toCriterion(outcome);
			if ((criterion == ContributionCriterion.DOCUMENT || criterion == ContributionCriterion.RESEARCH)
					&& !evidenced.contains(task.getId())) {
				criterion = null;
			}
			facts.add(new TaskFact(
					task.getAssigneeStudent().getId(),
					task.getSprint() == null ? null : task.getSprint().getId(),
					task.getSprint() == null ? null : task.getSprint().getName(),
					task.getStatus(),
					task.getStoryPoint(),
					criterion));
		}
		List<UUID> studentIds = new ArrayList<>(memberByStudent.keySet());
		List<PeerFact> peers = new ArrayList<>();
		if (!studentIds.isEmpty()) {
			for (PeerReview review : peerReviews.findFetchedByProjectAndReviewees(project.getId(), studentIds)) {
				if (review.getStarRating() == null) {
					continue;
				}
				peers.add(new PeerFact(
						review.getRevieweeStudent().getId(),
						review.getSprint() == null ? null : review.getSprint().getId(),
						review.getStarRating()));
			}
		}
		List<OverrideFact> overrideFacts = overrides
				.findByCourse_IdAndTeam_IdOrderByCreatedAtAsc(team.getCourse().getId(), team.getId())
				.stream()
				.filter(row -> OVERRIDE_TYPE.equals(row.getOverrideType()) && row.getStudentProfile() != null)
				.map(row -> new OverrideFact(row.getStudentProfile().getId(), row.getNewValue()))
				.toList();
		SprintFirstContributionMixer.Result mixed =
				SprintFirstContributionMixer.mix(mixerMembers, facts, peers, sliceWeights, overrideFacts);
		Map<UUID, Integer> peerCounts = new HashMap<>();
		Map<UUID, Boolean> hasDocument = new HashMap<>();
		Map<UUID, Boolean> hasTask = new HashMap<>();
		for (PeerFact peer : peers) {
			peerCounts.merge(peer.revieweeStudentId(), 1, Integer::sum);
		}
		for (TaskFact fact : facts) {
			hasTask.put(fact.assigneeStudentId(), true);
			if (fact.criterion() == ContributionCriterion.DOCUMENT) {
				hasDocument.put(fact.assigneeStudentId(), true);
			}
		}
		List<ContributionMemberResponse> rows = new ArrayList<>();
		for (MemberResult result : mixed.members()) {
			TeamMember member = memberByStudent.get(result.studentProfileId());
			StudentProfile profile = member.getCourseEnrollment().getStudentProfile();
			UserAccount account = profile.getUserAccount();
			rows.add(new ContributionMemberResponse(
					result.studentProfileId(),
					account == null ? null : account.getFullName(),
					profile.getStudentCode(),
					member.getRoleInTeam(),
					scale(result.sliceScore()),
					scale(result.sliceContributionPercentage()),
					scale(result.finalContributionPercentage()),
					scale(result.peerReviewScore()),
					scale(result.codeContributionPercentage()),
					scale(result.testContributionPercentage()),
					scale(result.documentContributionPercentage()),
					scale(result.researchContributionPercentage()),
					scale(result.taskContributionPercentage()),
					result.sprintBreakdowns().stream().map(TeamContributionService::toSprint).toList(),
					warnings(
							result,
							peerCounts.getOrDefault(result.studentProfileId(), 0),
							hasDocument.getOrDefault(result.studentProfileId(), false),
							hasTask.getOrDefault(result.studentProfileId(), false))));
		}
		return new ContributionEvaluationResponse(
				team.getId(),
				project.getId(),
				team.getCourse().getId(),
				team.getCourse().getContributionConfigMode(),
				toWeightResponse(sliceWeights),
				rows);
	}

	private Team requireReadableTeam(UserAccount actor, UUID teamId) {
		Team team = teams.findFetchedById(teamId)
				.or(() -> teams.findById(teamId))
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TEAM_NOT_FOUND, HttpStatus.NOT_FOUND, "Team was not found."));
		if (actor != null && actor.getAccountRole() == AccountRole.ADMIN) {
			return team;
		}
		if (actor != null && actor.getAccountRole() == AccountRole.LECTURER) {
			requireLecturer(actor, team);
			return team;
		}
		if (actor != null && actor.getAccountRole() == AccountRole.STUDENT) {
			RoleInTeam role = members.findFetchedByTeam_Id(teamId).stream()
					.filter(member -> member.getCourseEnrollment()
							.getStudentProfile()
							.getUserAccount()
							.getId()
							.equals(actor.getId()))
					.map(TeamMember::getRoleInTeam)
					.findFirst()
					.orElse(null);
			if (role == RoleInTeam.LEADER) {
				return team;
			}
			throw new AcademicException(
					AcademicErrorCode.CONTRIBUTION_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Only the Team Leader can view contribution evaluation.");
		}
		throw new AcademicException(
				AcademicErrorCode.CONTRIBUTION_FORBIDDEN, HttpStatus.FORBIDDEN, "Not allowed to view contribution.");
	}

	private Team requireWritableTeam(UserAccount actor, UUID teamId) {
		Team team = requireReadableTeam(actor, teamId);
		if (actor != null && actor.getAccountRole() == AccountRole.STUDENT) {
			throw new AcademicException(
					AcademicErrorCode.CONTRIBUTION_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Only the course lecturer can override contribution.");
		}
		return team;
	}

	private void requireLecturer(UserAccount actor, Team team) {
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

	private static ContributionSprintBreakdownResponse toSprint(SprintSlice slice) {
		return new ContributionSprintBreakdownResponse(
				slice.sprintId(),
				slice.sprintName(),
				scale(slice.sliceScore()),
				scale(slice.sliceContributionPercentage()),
				scale(slice.contributionPercentage()));
	}

	private static ContributionSliceWeightsResponse toWeightResponse(ContributionSliceWeights weights) {
		return new ContributionSliceWeightsResponse(
				scale(weights.asPercent(weights.code())),
				scale(weights.asPercent(weights.test())),
				scale(weights.asPercent(weights.document())),
				scale(weights.asPercent(weights.research())));
	}

	private static List<String> warnings(
			MemberResult result, int peerCount, boolean hasDocument, boolean hasTask) {
		List<String> warnings = new ArrayList<>();
		BigDecimal finalScore = result.finalContributionPercentage() == null
				? BigDecimal.ZERO
				: result.finalContributionPercentage();
		if (peerCount == 0 && finalScore.compareTo(BigDecimal.valueOf(50)) >= 0) {
			warnings.add("NO_PEER_REVIEW");
		}
		if (result.peerReviewScore() != null
				&& result.peerReviewScore().compareTo(new BigDecimal("0.6")) <= 0
				&& finalScore.compareTo(BigDecimal.valueOf(40)) >= 0) {
			warnings.add("LOW_PEER_REVIEW");
		}
		int evidenceCount = (hasTask ? 1 : 0) + (hasDocument ? 1 : 0) + (peerCount > 0 ? 1 : 0);
		if (evidenceCount <= 1 && finalScore.compareTo(BigDecimal.valueOf(60)) >= 0) {
			warnings.add("INSUFFICIENT_EVIDENCE");
		}
		if (!hasTask && !hasDocument && peerCount == 0) {
			warnings.add("NO_EVIDENCE");
		}
		return warnings;
	}

	private static BigDecimal scale(BigDecimal value) {
		return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
	}

}
