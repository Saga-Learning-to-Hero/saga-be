package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.assistant.AssistantDtos.ConversationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.assistant.AssistantService;
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
class ProjectAssistantControllerWebTest {

	private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CONVERSATION = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final String BASE = "/api/projects/" + PROJECT + "/assistant";

	@Mock private AssistantService assistant;

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
		mvc = MockMvcBuilders.standaloneSetup(new ProjectAssistantController(assistant))
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void startingAConversationIsCreatedForTheSignedInUser() throws Exception {
		when(assistant.startConversation(user.getId(), PROJECT))
				.thenReturn(new ConversationResponse(CONVERSATION, PROJECT, null, null, null));

		mvc.perform(post(BASE + "/conversations"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(CONVERSATION.toString()));
	}

	@Test
	void aBlankOrMissingOrTooLongQuestionIsRejectedBeforeTheService() throws Exception {
		mvc.perform(post(BASE + "/conversations/" + CONVERSATION + "/messages")
						.contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		mvc.perform(post(BASE + "/conversations/" + CONVERSATION + "/messages")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post(BASE + "/conversations/" + CONVERSATION + "/messages")
						.contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"" + "x".repeat(1001) + "\"}"))
				.andExpect(status().isBadRequest());

		verify(assistant, never()).ask(any(), any(), any(), anyString());
	}

	@Test
	void aValidQuestionReachesTheServiceAndTheDailyLimitIs429() throws Exception {
		when(assistant.ask(user.getId(), PROJECT, CONVERSATION, "Task nào trễ?"))
				.thenThrow(new IntegrationException(IntegrationErrorCode.ASSISTANT_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "limit"));

		mvc.perform(post(BASE + "/conversations/" + CONVERSATION + "/messages")
						.contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Task nào trễ?\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("ASSISTANT_RATE_LIMITED"));
	}

	@Test
	void feedbackNeedsHelpfulAndReadsAreBound() throws Exception {
		UUID message = UUID.randomUUID();
		mvc.perform(post(BASE + "/messages/" + message + "/feedback")
						.contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"x\"}"))
				.andExpect(status().isBadRequest());
		verify(assistant, never()).feedback(any(), any(), any(), anyBoolean(), any());

		mvc.perform(post(BASE + "/messages/" + message + "/feedback")
						.contentType(MediaType.APPLICATION_JSON).content("{\"helpful\":true}"))
				.andExpect(status().isOk());
		mvc.perform(get(BASE + "/status")).andExpect(status().isOk());
		mvc.perform(get(BASE + "/conversations")).andExpect(status().isOk());
		mvc.perform(get(BASE + "/conversations/" + CONVERSATION + "/messages")).andExpect(status().isOk());

		verify(assistant).feedback(user.getId(), PROJECT, message, true, null);
		verify(assistant).status(user.getId(), PROJECT);
		verify(assistant).conversations(user.getId(), PROJECT);
		verify(assistant).messages(user.getId(), PROJECT, CONVERSATION);
	}
}
