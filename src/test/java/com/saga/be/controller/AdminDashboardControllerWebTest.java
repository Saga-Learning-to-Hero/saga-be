package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.admin.dashboard.AdminDashboardCacheMetadataResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSummaryResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardMissingService;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardUnconnectedTeamResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardWeeklyPointResponse;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.service.admin.dashboard.AdminDashboardService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminDashboardControllerWebTest {

	@Mock
	private AdminDashboardService dashboard;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new AdminDashboardController(dashboard)).build();
	}

	@Test
	void summaryExposesPhaseCContractAndOmitsLaterSections() throws Exception {
		UUID semesterId = UUID.fromString("11111111-1111-4111-8111-111111111111");
		when(dashboard.summary(eq(semesterId), eq(false)))
				.thenReturn(new AdminDashboardSummaryResponse(
						new AdminDashboardSelectedSemesterResponse(
								semesterId,
								"FA26",
								"Fall",
								LocalDate.of(2026, 9, 1),
								LocalDate.of(2026, 12, 15),
								16,
								3,
								true),
						List.of(),
						new AdminDashboardKpisResponse(10, 25.0d, "SP26", 2, 4, 1, 25.0d, 8, 6, 50.0d),
						List.of(new AdminDashboardWeeklyPointResponse(
								1,
								"Tuần 01",
								LocalDate.of(2026, 9, 1),
								LocalDate.of(2026, 9, 7),
								false,
								12,
								4,
								83.33d)),
						List.of(new AdminDashboardUnconnectedTeamResponse(
								UUID.fromString("22222222-2222-4222-8222-222222222222"),
								7,
								"Team 7",
								"SWP391",
								"Lecturer",
								"lecturer@fe.edu.vn",
								AdminDashboardMissingService.BOTH,
								LocalDateTime.of(2026, 9, 5, 0, 0),
								14)),
						List.of(
								new AdminDashboardIntegrationPulseResponse(
										IntegrationProvider.GITHUB,
										123L,
										456L,
										LocalDateTime.of(2026, 9, 19, 10, 0)),
								new AdminDashboardIntegrationPulseResponse(
										IntegrationProvider.JIRA, 94L, 382L, null)),
						new AdminDashboardCacheMetadataResponse(
								Instant.parse("2026-09-19T04:00:00Z"), Instant.parse("2026-09-19T04:10:00Z"), 600L, false)));
		mockMvc.perform(get("/api/admin/dashboard/summary").param("semesterId", semesterId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.selectedSemester.code").value("FA26"))
				.andExpect(jsonPath("$.selectedSemester.totalWeeks").value(16))
				.andExpect(jsonPath("$.selectedSemester.currentWeekIndex").value(3))
				.andExpect(jsonPath("$.selectedSemester.active").value(true))
				.andExpect(jsonPath("$.kpis.totalStudents").value(10))
				.andExpect(jsonPath("$.kpis.studentsGrowthPercentage").value(25.0))
				.andExpect(jsonPath("$.kpis.comparedSemesterCode").value("SP26"))
				.andExpect(jsonPath("$.kpis.connectedTeamsRate").value(25.0))
				.andExpect(jsonPath("$.kpis.traceabilityRate").value(50.0))
				.andExpect(jsonPath("$.weeklyTimeline[0].weekLabel").value("Tuần 01"))
				.andExpect(jsonPath("$.weeklyTimeline[0].isCurrentWeek").value(false))
				.andExpect(jsonPath("$.weeklyTimeline[0].currentWeek").doesNotExist())
				.andExpect(jsonPath("$.weeklyTimeline[0].commits").value(12))
				.andExpect(jsonPath("$.weeklyTimeline[0].tasksCompleted").value(4))
				.andExpect(jsonPath("$.weeklyTimeline[0].traceabilityRate").value(83.33))
				.andExpect(jsonPath("$.unconnectedTeamsAlert[0].teamNo").value(7))
				.andExpect(jsonPath("$.unconnectedTeamsAlert[0].missingService").value("BOTH"))
				.andExpect(jsonPath("$.unconnectedTeamsAlert[0].daysSinceCreated").value(14))
				.andExpect(jsonPath("$.integrationPulse[0].service").value("GITHUB"))
				.andExpect(jsonPath("$.integrationPulse[0].uniqueEventsReceived24h").value(123))
				.andExpect(jsonPath("$.integrationPulse[0].uniqueEventsReceived7d").value(456))
				.andExpect(jsonPath("$.integrationPulse[0].lastUniqueEventAt").value("2026-09-19T10:00:00"))
				.andExpect(jsonPath("$.integrationPulse[1].service").value("JIRA"))
				.andExpect(jsonPath("$.integrationPulse[1].uniqueEventsReceived24h").value(94))
				.andExpect(jsonPath("$.integrationPulse[1].lastUniqueEventAt").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.integrationPulse[0].successRate").doesNotExist())
				.andExpect(jsonPath("$.integrationPulse[0].latencyMs").doesNotExist())
				.andExpect(jsonPath("$.integrationPulse[0].status").doesNotExist())
				.andExpect(jsonPath("$.cacheMetadata.refreshPending").value(false))
				.andExpect(jsonPath("$.projectHealthDistribution").doesNotExist())
				.andExpect(jsonPath("$.sprintMilestones").doesNotExist())
				.andExpect(jsonPath("$.integrationsHealth").doesNotExist());
	}

	@Test
	void omittedSemesterIdAndForceRefreshArePassedThrough() throws Exception {
		when(dashboard.summary(isNull(), eq(true)))
				.thenReturn(new AdminDashboardSummaryResponse(
						new AdminDashboardSelectedSemesterResponse(
								UUID.fromString("11111111-1111-4111-8111-111111111111"),
								"FA26",
								"Fall",
								LocalDate.of(2026, 9, 1),
								LocalDate.of(2026, 12, 15),
								16,
								3,
								true),
						List.of(),
						new AdminDashboardKpisResponse(0, null, null, 0, 0, 0, null, 0, 0, null),
						List.of(),
						List.of(),
						List.of(),
						new AdminDashboardCacheMetadataResponse(
								Instant.parse("2026-09-19T04:00:00Z"), Instant.parse("2026-09-19T04:10:00Z"), 600L, false)));
		mockMvc.perform(get("/api/admin/dashboard/summary").param("forceRefresh", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kpis.studentsGrowthPercentage").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.selectedSemester.id")
						.value("11111111-1111-4111-8111-111111111111"));
	}
}
