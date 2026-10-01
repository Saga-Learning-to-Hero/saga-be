package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.delay.DelayCaseDtos.ExplainRequest;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.entity.enums.DelayCauseCategory;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.delay.TaskDelayCaseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class TaskDelayCaseControllerWebTest {

	private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CASE = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Mock private TaskDelayCaseService delays;

	private MockMvc mvc;
	private UserAccount user;

	@BeforeEach
	void setUp() {
		user = new UserAccount();
		user.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
		user.setEmail("student@fpt.edu.vn");
		user.setAccountRole(AccountRole.STUDENT);
		user.setAccountStatus(AccountStatus.ACTIVE);
		user.setPasswordHash("hash");
		SecurityContextHolder.getContext().setAuthentication(SagaAuthentications.authenticated(user));
		mvc = MockMvcBuilders.standaloneSetup(new TaskDelayCaseController(delays), new LecturerDelayCaseController(delays))
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void anExplanationWithoutACategoryIsRejectedBeforeTheService() throws Exception {
		mvc.perform(post("/api/projects/" + PROJECT + "/delay-cases/" + CASE + "/explanation")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"note\":\"x\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));

		verifyNoInteractions(delays);
	}

	@Test
	void aValidExplanationReachesTheServiceForTheSignedInUser() throws Exception {
		mvc.perform(post("/api/projects/" + PROJECT + "/delay-cases/" + CASE + "/explanation")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"category\":\"PERSONAL_EMERGENCY\",\"note\":\"Nhập viện\"}"))
				.andExpect(status().isOk());

		verify(delays).explain(eq(user.getId()), eq(PROJECT), eq(CASE),
				eq(new ExplainRequest(DelayCauseCategory.PERSONAL_EMERGENCY, "Nhập viện", null, null)));
	}

	@Test
	void filtersAndTheLecturerQueueStatusesAreBound() throws Exception {
		mvc.perform(get("/api/projects/" + PROJECT + "/delay-cases").param("status", "AWAITING_LEADER"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/lecturer/delay-cases").param("status", "AWAITING_LECTURER", "CLOSED_OBJECTIVE"))
				.andExpect(status().isOk());

		verify(delays).list(user.getId(), PROJECT, DelayCaseStatus.AWAITING_LEADER, null);
		verify(delays).lecturerQueue(user.getId(), List.of(DelayCaseStatus.AWAITING_LECTURER, DelayCaseStatus.CLOSED_OBJECTIVE));
	}

	@Test
	void aLeaderReviewNeedsADecision() throws Exception {
		mvc.perform(post("/api/projects/" + PROJECT + "/delay-cases/" + CASE + "/leader-review")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"comment\":\"x\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/projects/" + PROJECT + "/delay-cases/" + CASE + "/lecturer-review")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());

		verify(delays, org.mockito.Mockito.never()).leaderReview(any(), any(), any(), any());
		verify(delays, org.mockito.Mockito.never()).lecturerReview(any(), any(), any(), any());
	}
}
