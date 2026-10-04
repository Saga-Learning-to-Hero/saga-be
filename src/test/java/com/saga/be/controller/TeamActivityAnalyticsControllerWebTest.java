package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.project.BurndownChartResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.projection.TeamActivityAnalyticsService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP boundary of the sprint burndown: the {@code sprintId} path variable selected in the UI
 * reaches the service unchanged (canonical local UUID, never dropped or defaulted), and each
 * sprint's own response is returned. The production controller is excluded by its {@code !test}
 * profile, so it is registered here with a mocked service behind the real MVC/security chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TeamActivityAnalyticsControllerWebTest.Configuration.class)
class TeamActivityAnalyticsControllerWebTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private TeamActivityAnalyticsService analytics;
	@Autowired private UserAccountRepository users;

	private UserAccount student;

	@BeforeEach
	void setUp() {
		reset(analytics, users);
		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setEmail("student@saga.local");
		student.setUsername("student");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		student.setPasswordHash("hash");
		when(users.findById(any(UUID.class))).thenAnswer(call -> student.getId().equals(call.getArgument(0)) ? Optional.of(student) : Optional.empty());
		when(users.findAccountStatusById(any(UUID.class))).thenAnswer(call -> ((Optional<?>) (student.getId().equals(call.getArgument(0)) ? Optional.of(student) : Optional.empty())).map(account -> ((com.saga.be.entity.account.UserAccount) account).getAccountStatus()));
	}

	@Test
	void eachSelectedSprintIdIsPropagatedToTheServiceAndReturnsThatSprintsOwnResult() throws Exception {
		UUID courseId = UUID.randomUUID();
		UUID teamId = UUID.randomUUID();
		UUID s1 = UUID.randomUUID();
		UUID s2 = UUID.randomUUID();
		when(analytics.burndown(student.getId(), courseId, teamId, s1)).thenReturn(burndown(courseId, teamId, s1, "S1", 2));
		when(analytics.burndown(student.getId(), courseId, teamId, s2)).thenReturn(burndown(courseId, teamId, s2, "S2", 5));

		mockMvc.perform(get("/api/courses/{c}/teams/{t}/sprints/{s}/burndown", courseId, teamId, s1)
						.with(authentication(SagaAuthentications.authenticated(student))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sprintId").value(s1.toString()))
				.andExpect(jsonPath("$.totalScope").value(2));
		mockMvc.perform(get("/api/courses/{c}/teams/{t}/sprints/{s}/burndown", courseId, teamId, s2)
						.with(authentication(SagaAuthentications.authenticated(student))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sprintId").value(s2.toString()))
				.andExpect(jsonPath("$.totalScope").value(5));

		verify(analytics).burndown(student.getId(), courseId, teamId, s1);
		verify(analytics).burndown(student.getId(), courseId, teamId, s2);
	}

	@Test
	void externalJiraSprintIdIsNotAcceptedAsASprintIdentity() throws Exception {
		mockMvc.perform(get("/api/courses/{c}/teams/{t}/sprints/{s}/burndown", UUID.randomUUID(), UUID.randomUUID(), "101")
						.with(authentication(SagaAuthentications.authenticated(student))))
				.andExpect(status().isBadRequest());
		verify(analytics, never()).burndown(any(), any(), any(), any());
	}

	private static BurndownChartResponse burndown(UUID courseId, UUID teamId, UUID sprintId, String name, int total) {
		LocalDate day = LocalDate.of(2026, 9, 1);
		// Points are irrelevant to sprint propagation; left empty so this stays independent of the point shape.
		return new BurndownChartResponse(courseId, teamId, sprintId, name, day, day, total, List.of());
	}

	@TestConfiguration
	static class Configuration {
		@Bean @Primary TeamActivityAnalyticsService teamActivityAnalyticsService() { return mock(TeamActivityAnalyticsService.class); }
		@Bean @Primary UserAccountRepository userAccountRepository() { return mock(UserAccountRepository.class); }
		@Bean TeamActivityAnalyticsController teamActivityAnalyticsController(TeamActivityAnalyticsService analytics) {
			return new TeamActivityAnalyticsController(analytics);
		}
	}
}
