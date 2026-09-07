package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.identity.PersonalIntegrationService;
import com.saga.be.service.identity.ProjectIntegrationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@ExtendWith(MockitoExtension.class)
class IntegrationCallbackRedirectWebTest {

	@Mock
	private ProjectIntegrationService projects;
	@Mock
	private PersonalIntegrationService personal;
	@Mock
	private UserAccountRepository users;

	private IntegrationProperties properties;
	private SagaUserPrincipal principal;
	private UserAccount actor;
	private MockMvc callbacks;
	private MockMvc rest;

	@BeforeEach
	void setUp() {
		properties = new IntegrationProperties();
		properties.setFailureUrl("http://localhost:3000/integrations/failure");
		properties.setSuccessUrl("http://localhost:3000/integrations/success");
		UUID userId = UUID.randomUUID();
		principal = new SagaUserPrincipal(userId, "leader@gmail.com", "leader", "Leader", null, null, false);
		actor = new UserAccount();
		actor.setId(userId);
		callbacks = MockMvcBuilders.standaloneSetup(
						new IntegrationCallbackController(projects, properties),
						new PersonalIntegrationController(personal, users, properties),
						new ProjectIntegrationController(projects, properties))
				.setCustomArgumentResolvers(new PrincipalResolver(principal))
				.build();
		rest = MockMvcBuilders.standaloneSetup(new ProjectIntegrationController(projects, properties))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new PrincipalResolver(principal))
				.build();
	}

	@Test
	void githubTeamSetupCallbackRedirectsKnownFailureToFrontend() throws Exception {
		when(projects.completeGithubInstallation(eq(principal.getUserId()), eq("state"), eq(158866076L), any()))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_EXPIRED, HttpStatus.BAD_REQUEST, "secret expired token"));
		callbacks.perform(get("/api/integrations/github/setup/callback")
						.param("state", "state")
						.param("installation_id", "158866076"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "http://localhost:3000/integrations/failure?code=OAUTH_STATE_EXPIRED"));
	}

	@Test
	void jiraTeamCallbackRedirectsKnownFailureToFrontend() throws Exception {
		when(projects.completeJiraTeamCallback(eq(principal.getUserId()), eq("code"), eq("state")))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_EXPIRED, HttpStatus.BAD_REQUEST, "do-not-leak"));
		callbacks.perform(get("/api/integrations/jira/team/callback").param("code", "code").param("state", "state"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "http://localhost:3000/integrations/failure?code=OAUTH_STATE_EXPIRED"));
	}

	@Test
	void personalGithubCallbackRedirectsKnownFailureToFrontend() throws Exception {
		when(users.findById(principal.getUserId())).thenReturn(Optional.of(actor));
		when(personal.completeGithub(eq(principal.getUserId()), eq("code"), eq("state"), eq(actor)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_INVALID, HttpStatus.BAD_REQUEST, "oauth-code-must-not-leak"));
		callbacks.perform(get("/api/integrations/github/oauth/callback").param("code", "code").param("state", "state"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "http://localhost:3000/integrations/failure?code=OAUTH_STATE_INVALID"));
	}

	@Test
	void personalJiraCallbackRedirectsKnownFailureToFrontend() throws Exception {
		when(users.findById(principal.getUserId())).thenReturn(Optional.of(actor));
		when(personal.completeJira(eq(principal.getUserId()), eq("code"), eq("state"), eq(actor)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_SITE_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "secret"));
		callbacks.perform(get("/api/integrations/jira/oauth/callback").param("code", "code").param("state", "state"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "http://localhost:3000/integrations/failure?code=JIRA_SITE_NOT_ACCESSIBLE"));
	}

	@Test
	void projectGithubSetupCallbackRedirectsKnownFailureToFrontend() throws Exception {
		UUID projectId = UUID.randomUUID();
		when(projects.completeGithubInstallation(eq(principal.getUserId()), eq("state"), eq(1L), any()))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_REQUEST, "installation secret"));
		callbacks.perform(get("/api/projects/" + projectId + "/integrations/github/setup/callback")
						.param("state", "state")
						.param("installation_id", "1"))
				.andExpect(status().isFound())
				.andExpect(header().string(
						"Location", "http://localhost:3000/integrations/failure?code=GITHUB_INSTALLATION_INVALID"));
	}

	@Test
	void restIntegrationExceptionRemainsJson() throws Exception {
		UUID projectId = UUID.randomUUID();
		when(projects.summary(principal.getUserId(), projectId))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "not a leader"));
		rest.perform(get("/api/projects/" + projectId + "/integrations"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INTEGRATION_FORBIDDEN"))
				.andExpect(jsonPath("$.message").value("not a leader"));
	}

	private record PrincipalResolver(SagaUserPrincipal principal) implements HandlerMethodArgumentResolver {

		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return SagaUserPrincipal.class.equals(parameter.getParameterType());
		}

		@Override
		public Object resolveArgument(
				MethodParameter parameter,
				ModelAndViewContainer mavContainer,
				NativeWebRequest webRequest,
				WebDataBinderFactory binderFactory) {
			return principal;
		}
	}
}
