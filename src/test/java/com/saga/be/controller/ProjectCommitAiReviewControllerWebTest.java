package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.ai.CommitAiReviewDtos;
import com.saga.be.dto.ai.TeamAiKeyDtos;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.ai.CommitAiReviewService;
import com.saga.be.service.ai.CommitTaskManualLinkService;
import com.saga.be.service.ai.TeamAiCredentialService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProjectCommitAiReviewControllerWebTest {

	private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID COMMIT = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID TASK = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final String BASE = "/api/projects/" + PROJECT;

	@Mock private CommitAiReviewService reviews;
	@Mock private CommitTaskManualLinkService manualLinks;
	@Mock private TeamAiCredentialService teamKeys;

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
		mvc = MockMvcBuilders.standaloneSetup(new ProjectCommitAiReviewController(reviews, manualLinks, teamKeys))
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private static CommitAiReviewDtos.Detail detail(String status, boolean merge) {
		return new CommitAiReviewDtos.Detail(COMMIT, "abc", "msg", merge, status, "Đạt", "ok", List.of(), !merge, merge ? "MERGE" : null,
				"TEAM", true, null, null, null, null, null, null, new CommitAiReviewDtos.TaskReview(null, null, List.of(), List.of()), null);
	}

	@Test
	void reviewDetailIsReadForTheSignedInUser() throws Exception {
		when(reviews.detail(user.getId(), PROJECT, COMMIT)).thenReturn(detail(CommitAiReviewDtos.SKIPPED_MERGE, true));

		mvc.perform(get(BASE + "/commits/" + COMMIT + "/ai-review"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SKIPPED_MERGE"))
				.andExpect(jsonPath("$.canRequestReview").value(false))
				.andExpect(jsonPath("$.reviewBlockedReason").value("MERGE"));
	}

	@Test
	void backfillHasItsOwnRoute_notMistakenForACommitId() throws Exception {
		when(reviews.backfill(user.getId(), PROJECT, 10)).thenReturn(new CommitAiReviewDtos.BackfillResult(3, 5, 0));

		mvc.perform(post(BASE + "/commits/ai-review/backfill").param("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.queued").value(3))
				.andExpect(jsonPath("$.skipped").value(5));
	}

	@Test
	void requestingAReviewOfAMergeCommitIs422WithItsCode() throws Exception {
		when(reviews.request(user.getId(), PROJECT, COMMIT)).thenThrow(new IntegrationException(
				IntegrationErrorCode.AI_COMMIT_MERGE_NOT_REVIEWED, HttpStatus.UNPROCESSABLE_ENTITY, "Merge commit chỉ gộp code đã có"));

		mvc.perform(post(BASE + "/commits/" + COMMIT + "/ai-review"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("AI_COMMIT_MERGE_NOT_REVIEWED"));
	}

	@Test
	void manualLinkAndUnlink() throws Exception {
		when(manualLinks.link(user.getId(), PROJECT, COMMIT, TASK)).thenReturn(detail(CommitAiReviewDtos.PASS, false));
		when(manualLinks.unlink(user.getId(), PROJECT, COMMIT, TASK)).thenReturn(detail(CommitAiReviewDtos.WARNING, false));

		mvc.perform(post(BASE + "/commits/" + COMMIT + "/manual-task-links").contentType(MediaType.APPLICATION_JSON)
						.content("{\"taskId\":\"" + TASK + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PASS"));
		mvc.perform(delete(BASE + "/commits/" + COMMIT + "/manual-task-links/" + TASK))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("WARNING"));
	}

	@Test
	void aForbiddenManualLinkIs403() throws Exception {
		when(manualLinks.link(any(), any(), any(), any())).thenThrow(new IntegrationException(
				IntegrationErrorCode.COMMIT_TASK_LINK_FORBIDDEN, HttpStatus.FORBIDDEN, "Chỉ tác giả"));

		mvc.perform(post(BASE + "/commits/" + COMMIT + "/manual-task-links").contentType(MediaType.APPLICATION_JSON)
						.content("{\"taskId\":\"" + TASK + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COMMIT_TASK_LINK_FORBIDDEN"));
	}

	@Test
	void teamKeyIsReadSavedAndRemoved_neverEchoingTheKey() throws Exception {
		TeamAiKeyDtos.Status status = new TeamAiKeyDtos.Status(true, "GEMINI", "gemini-3.6-flash", "UNVERIFIED", "1234",
				null, null, null, null, true, false, List.of());
		when(teamKeys.status(user.getId(), PROJECT)).thenReturn(status);
		when(teamKeys.save(eq(user.getId()), eq(PROJECT), any())).thenReturn(status);
		when(teamKeys.revoke(user.getId(), PROJECT)).thenReturn(status);

		mvc.perform(get(BASE + "/ai-team-key")).andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("GEMINI"));
		mvc.perform(put(BASE + "/ai-team-key").contentType(MediaType.APPLICATION_JSON)
						.content("{\"provider\":\"GEMINI\",\"modelId\":\"gemini-3.6-flash\",\"apiKey\":\"AIza-secret-1234\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastFour").value("1234"))
				.andExpect(jsonPath("$.apiKey").doesNotExist());
		verify(teamKeys).save(user.getId(), PROJECT, new TeamAiKeyDtos.SaveRequest("GEMINI", "gemini-3.6-flash", "AIza-secret-1234"));
		mvc.perform(delete(BASE + "/ai-team-key")).andExpect(status().isOk());
		verify(teamKeys).revoke(user.getId(), PROJECT);
	}

	@Test
	void backfillWithoutALimitPassesNull() throws Exception {
		when(reviews.backfill(eq(user.getId()), eq(PROJECT), isNull())).thenReturn(new CommitAiReviewDtos.BackfillResult(0, 0, 0));
		mvc.perform(post(BASE + "/commits/ai-review/backfill")).andExpect(status().isOk());
	}
}
