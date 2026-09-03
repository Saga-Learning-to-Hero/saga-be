package com.saga.be.dto.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.exception.GlobalExceptionHandler;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class SelectJiraIntegrationRequestWebTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = MockMvcBuilders.standaloneSetup(new SelectJiraIntegrationRequestValidationFixture())
				.setControllerAdvice(new GlobalExceptionHandler())
				.setValidator(validator)
				.build();
	}

	@Test
	void typedRequestWithBoardIsAccepted() throws Exception {
		mockMvc.perform(put("/__contract/jira")
						.contentType(MediaType.APPLICATION_JSON)
						.content(
								"""
								{"cloudId":"aeb21465-f2da-4923-b356-f6f1cfa4fd13","jiraProjectId":"10067","boardId":"68"}
								"""))
				.andExpect(status().isNoContent());
	}

	@Test
	void omittedBoardIsAccepted() throws Exception {
		mockMvc.perform(put("/__contract/jira")
						.contentType(MediaType.APPLICATION_JSON)
						.content(
								"""
								{"cloudId":"aeb21465-f2da-4923-b356-f6f1cfa4fd13","jiraProjectId":"10067"}
								"""))
				.andExpect(status().isNoContent());
	}

	@Test
	void missingCloudIdIs400() throws Exception {
		mockMvc.perform(put("/__contract/jira")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"jiraProjectId\":\"10067\",\"boardId\":\"68\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.message").value("Request is invalid."));
	}

	@Test
	void missingJiraProjectIdIs400() throws Exception {
		mockMvc.perform(put("/__contract/jira")
						.contentType(MediaType.APPLICATION_JSON)
						.content(
								"""
								{"cloudId":"aeb21465-f2da-4923-b356-f6f1cfa4fd13","boardId":"68"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
	}

	@Test
	void malformedBodyIs400() throws Exception {
		mockMvc.perform(put("/__contract/jira").contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.message").value("Request is invalid."));
	}

	@RestController
	static class SelectJiraIntegrationRequestValidationFixture {

		@PutMapping("/__contract/jira")
		ResponseEntity<Void> save(@Valid @RequestBody SelectJiraIntegrationRequest body) {
			return ResponseEntity.noContent().build();
		}
	}
}
