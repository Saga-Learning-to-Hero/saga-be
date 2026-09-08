package com.saga.be.service.contribution;

import com.saga.be.dto.contribution.ContributionSliceWeightsRequest;
import com.saga.be.dto.contribution.ContributionTeamWeightRowResponse;
import com.saga.be.dto.contribution.ContributionTeamWeightsResponse;
import com.saga.be.dto.contribution.CourseContributionWeightsResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.assessment.ProjectGroupWeightConfig;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.ContributionConfigMode;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.ProjectGroupWeightConfigRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
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
public class CourseContributionWeightService {

	private static final MathContext MATH = ContributionSliceWeights.MATH;
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

	private final LecturerCourseAuthorization authorization;
	private final CourseRepository courses;
	private final TeamRepository teams;
	private final ProjectGroupWeightConfigRepository configs;
	private final AuditService audit;

	public CourseContributionWeightService(
			LecturerCourseAuthorization authorization,
			CourseRepository courses,
			TeamRepository teams,
			ProjectGroupWeightConfigRepository configs,
			AuditService audit) {
		this.authorization = authorization;
		this.courses = courses;
		this.teams = teams;
		this.configs = configs;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public CourseContributionWeightsResponse getWeights(UserAccount actor, UUID courseId) {
		Course course = authorization.requireCourse(actor, courseId);
		return toResponse(course);
	}

	@Transactional
	public CourseContributionWeightsResponse updateWeights(
			UserAccount actor, UUID courseId, ContributionSliceWeightsRequest request, AuditRequest auditRequest) {
		Course course = authorization.requireCourse(actor, courseId);
		validateCoursePercents(request);
		Map<String, Object> before = snapshot(course);
		course.setCodeContributionWeight(request.codeWeight().doubleValue());
		course.setTestContributionWeight(request.testWeight().doubleValue());
		course.setDocumentContributionWeight(request.documentWeight().doubleValue());
		course.setResearchContributionWeight(request.researchWeight().doubleValue());
		courses.save(course);
		record(actor, course, "COURSE_CONTRIBUTION_WEIGHTS_UPDATED", before, snapshot(course), auditRequest);
		return toResponse(course);
	}

	@Transactional
	public CourseContributionWeightsResponse switchMode(
			UserAccount actor, UUID courseId, ContributionConfigMode mode, AuditRequest auditRequest) {
		Course course = authorization.requireCourse(actor, courseId);
		if (mode == null) {
			throw new AcademicException(
					AcademicErrorCode.CONTRIBUTION_WEIGHTS_INVALID, HttpStatus.BAD_REQUEST, "Contribution mode is required.");
		}
		if (mode == ContributionConfigMode.PROJECT_GROUP) {
			List<Team> courseTeams = teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId);
			Set<UUID> configured = configs.findByTeam_Course_Id(courseId).stream()
					.filter(row -> row.getProject() != null)
					.map(row -> row.getProject().getId())
					.collect(Collectors.toSet());
			boolean incomplete = courseTeams.stream()
					.filter(team -> team.getProject() != null)
					.anyMatch(team -> !configured.contains(team.getProject().getId()));
			if (incomplete) {
				throw new AcademicException(
						AcademicErrorCode.TEAM_MODE_CONFIGURATION_INCOMPLETE,
						HttpStatus.CONFLICT,
						"Every team with a project must have group weights before PROJECT_GROUP mode.");
			}
		}
		Map<String, Object> before = snapshot(course);
		course.setContributionConfigMode(mode);
		courses.save(course);
		record(actor, course, "COURSE_CONTRIBUTION_MODE_UPDATED", before, snapshot(course), auditRequest);
		return toResponse(course);
	}

	@Transactional(readOnly = true)
	public ContributionTeamWeightsResponse listTeamWeights(UserAccount actor, UUID courseId) {
		Course course = authorization.requireCourse(actor, courseId);
		Set<UUID> configured = configs.findByTeam_Course_Id(courseId).stream()
				.filter(row -> row.getProject() != null)
				.map(row -> row.getProject().getId())
				.collect(Collectors.toSet());
		List<ContributionTeamWeightRowResponse> rows = new ArrayList<>();
		for (Team team : teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)) {
			UUID projectId = team.getProject() == null ? null : team.getProject().getId();
			rows.add(new ContributionTeamWeightRowResponse(
					team.getId(),
					projectId,
					team.getName(),
					team.getTeamNo(),
					projectId != null && configured.contains(projectId)));
		}
		return new ContributionTeamWeightsResponse(course.getId(), course.getContributionConfigMode(), rows);
	}

	static void validateCoursePercents(ContributionSliceWeightsRequest request) {
		if (request == null
				|| request.codeWeight() == null
				|| request.testWeight() == null
				|| request.documentWeight() == null
				|| request.researchWeight() == null) {
			throw invalid("Four contribution weights are required.");
		}
		requireNonNegative(request.codeWeight());
		requireNonNegative(request.testWeight());
		requireNonNegative(request.documentWeight());
		requireNonNegative(request.researchWeight());
		BigDecimal sum = request.codeWeight()
				.add(request.testWeight(), MATH)
				.add(request.documentWeight(), MATH)
				.add(request.researchWeight(), MATH);
		if (sum.subtract(HUNDRED, MATH).abs().compareTo(TOLERANCE) > 0) {
			throw invalid("Course contribution weights must sum to 100.");
		}
	}

	private static void requireNonNegative(BigDecimal value) {
		if (value.compareTo(BigDecimal.ZERO) < 0) {
			throw invalid("Contribution weights must be >= 0.");
		}
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.CONTRIBUTION_WEIGHTS_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	private CourseContributionWeightsResponse toResponse(Course course) {
		return new CourseContributionWeightsResponse(
				course.getContributionConfigMode(),
				percent(course.getCodeContributionWeight()),
				percent(course.getTestContributionWeight()),
				percent(course.getDocumentContributionWeight()),
				percent(course.getResearchContributionWeight()));
	}

	private static BigDecimal percent(Double value) {
		return BigDecimal.valueOf(value == null ? 25d : value).setScale(4, java.math.RoundingMode.HALF_UP);
	}

	private Map<String, Object> snapshot(Course course) {
		return Map.of(
				"mode",
				course.getContributionConfigMode() == null ? "" : course.getContributionConfigMode().name(),
				"codeWeight",
				course.getCodeContributionWeight(),
				"testWeight",
				course.getTestContributionWeight(),
				"documentWeight",
				course.getDocumentContributionWeight(),
				"researchWeight",
				course.getResearchContributionWeight());
	}

	private void record(
			UserAccount actor,
			Course course,
			String action,
			Map<String, Object> before,
			Map<String, Object> after,
			AuditRequest auditRequest) {
		if (audit == null) {
			return;
		}
		audit.record(
				actor,
				null,
				null,
				action,
				"course",
				course.getId(),
				before,
				after,
				Map.of("courseId", course.getId().toString()),
				AuditSource.API,
				auditRequest == null ? null : auditRequest.requestId(),
				auditRequest == null ? null : auditRequest.ip(),
				auditRequest == null ? null : auditRequest.userAgent());
	}
}
