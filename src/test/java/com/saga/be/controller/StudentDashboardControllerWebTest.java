package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.student.dashboard.StudentDashboardActiveTaskResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardCommitMetricsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardCourseResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardGithubIntegrationResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardIntegrationsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardJiraIntegrationResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardMetricsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardRecentCommitResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardSprintResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardStudentResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardTaskMetricsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardTeamResponse;
import java.util.List;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.student.StudentDashboardService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class StudentDashboardControllerWebTest {

	@Mock
	private StudentDashboardService dashboard;

	private MockMvc mockMvc;
	private UUID userId;
	private UUID courseId;

	@BeforeEach
	void setUp() {
		userId = UUID.fromString("11111111-1111-4111-8111-111111111111");
		courseId = UUID.fromString("22222222-2222-4222-8222-222222222222");
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setEmail("member@gmail.com");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		SecurityContextHolder.getContext().setAuthentication(SagaAuthentications.authenticated(account));
		mockMvc = MockMvcBuilders.standaloneSetup(new StudentDashboardController(dashboard))
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@AfterEach
	void clearSecurity() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void memberPhaseAContractOmitsLaterSections() throws Exception {
		UUID studentId = UUID.fromString("33333333-3333-4333-8333-333333333333");
		UUID teamId = UUID.fromString("44444444-4444-4444-8444-444444444444");
		UUID projectId = UUID.fromString("55555555-5555-4555-8555-555555555555");
		UUID sprintId = UUID.fromString("66666666-6666-4666-8666-666666666666");
		when(dashboard.get(eq(userId), eq(courseId)))
				.thenReturn(new StudentDashboardResponse(
						new StudentDashboardStudentResponse(
								studentId, userId, "SE111111", "Alpha", "https://example.com/a.png", "MEMBER"),
						new StudentDashboardCourseResponse(
								courseId, "SWP391-SE18", "SWP391", "Software Development Project", "FA26"),
						new StudentDashboardTeamResponse(teamId, 1, "Alpha", projectId, "SAGA", 3),
						new StudentDashboardIntegrationsResponse(
								new StudentDashboardJiraIntegrationResponse(
										true, "SAGA", "ACTIVE", LocalDateTime.of(2026, 9, 1, 8, 0)),
								new StudentDashboardGithubIntegrationResponse(
										true, 2, "ACTIVE", LocalDateTime.of(2026, 9, 1, 9, 0))),
						new StudentDashboardSprintResponse(
								sprintId,
								"42",
								"Sprint 1",
								"active",
								LocalDateTime.of(2026, 9, 1, 0, 0),
								LocalDateTime.of(2026, 9, 14, 0, 0),
								4,
								2,
								50.0d),
						new StudentDashboardMetricsResponse(
								new StudentDashboardTaskMetricsResponse(7, 1, 2, 1, 3, 0, 42.857, 18, 8),
								new StudentDashboardCommitMetricsResponse(
										34, 31, 3, 91.176, LocalDateTime.of(2026, 9, 4, 8, 0))),
						List.of(new StudentDashboardActiveTaskResponse(
								UUID.fromString("77777777-7777-4777-8777-777777777777"),
								"SAGA-1",
								"Fix login",
								"TODO",
								"HIGH",
								3,
								LocalDateTime.of(2026, 9, 10, 0, 0),
								2,
								1,
								false)),
						List.of(new StudentDashboardRecentCommitResponse(
								"abcdef123456",
								"abcdef1",
								"fix login",
								"org/saga",
								LocalDateTime.of(2026, 9, 4, 9, 0),
								List.of("SAGA-1", "SAGA-2")))));

		mockMvc.perform(get("/api/student/courses/" + courseId + "/dashboard"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.student.teamRole").value("MEMBER"))
				.andExpect(jsonPath("$.course.subjectCode").value("SWP391"))
				.andExpect(jsonPath("$.team.membersCount").value(3))
				.andExpect(jsonPath("$.integrations.jira.connected").value(true))
				.andExpect(jsonPath("$.integrations.github.repositoryCount").value(2))
				.andExpect(jsonPath("$.currentSprint.completionPercent").value(50.0))
				.andExpect(jsonPath("$.currentSprint.daysRemaining").doesNotExist())
				.andExpect(jsonPath("$.myMetrics.tasks.totalAssigned").value(7))
				.andExpect(jsonPath("$.myMetrics.commits.linkedCommits").value(31))
				.andExpect(jsonPath("$.myActiveTasks[0].evidenceCommitCount").value(1))
				.andExpect(jsonPath("$.recentCommits[0].linkedTaskKeys[1]").value("SAGA-2"))
				.andExpect(jsonPath("$.contribution").doesNotExist())
				.andExpect(jsonPath("$.weeklyCommits").doesNotExist())
				.andExpect(jsonPath("$.actionableAlerts").doesNotExist());
	}

	@Test
	void noTeamContractKeepsTeamIntegrationsAndSprintNull() throws Exception {
		when(dashboard.get(eq(userId), eq(courseId)))
				.thenReturn(new StudentDashboardResponse(
						new StudentDashboardStudentResponse(UUID.randomUUID(), userId, "SE111111", "Alpha", null, null),
						new StudentDashboardCourseResponse(courseId, "SWP391-SE18", "SWP391", "Software", "FA26"),
						null,
						null,
						null,
						null,
						List.of(),
						List.of()));

		mockMvc.perform(get("/api/student/courses/" + courseId + "/dashboard"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.team").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.integrations").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.currentSprint").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.myMetrics").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.myActiveTasks").isArray())
				.andExpect(jsonPath("$.myActiveTasks").isEmpty())
				.andExpect(jsonPath("$.recentCommits").isEmpty())
				.andExpect(jsonPath("$.student.teamRole").value(Matchers.nullValue()));
	}

	@Test
	void malformedCourseIdIsRequestInvalid() throws Exception {
		mockMvc.perform(get("/api/student/courses/not-a-uuid/dashboard"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
	}

	@Test
	void forbiddenEnrollmentUsesCanonicalCode() throws Exception {
		when(dashboard.get(eq(userId), eq(courseId)))
				.thenThrow(new AcademicException(
						AcademicErrorCode.STUDENT_COURSE_FORBIDDEN,
						HttpStatus.FORBIDDEN,
						"Student is not ACTIVE in this course."));
		mockMvc.perform(get("/api/student/courses/" + courseId + "/dashboard"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("STUDENT_COURSE_FORBIDDEN"));
	}
}
