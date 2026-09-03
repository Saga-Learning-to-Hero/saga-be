package com.saga.be.dto.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.exception.GlobalExceptionHandler;
import jakarta.validation.Valid;
import java.util.List;
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

class SelectGitHubRepositoryRequestWebTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = MockMvcBuilders.standaloneSetup(new SelectGitHubRepositoryRequestValidationFixture())
				.setControllerAdvice(new GlobalExceptionHandler())
				.setValidator(validator)
				.build();
	}

	@Test
	void typedFrontendRequestIsAccepted() throws Exception {
		mockMvc.perform(put("/__contract/github-repositories")
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"repositoryId\":1338790015,\"role\":\"FRONTEND\"}]"))
				.andExpect(status().isNoContent());
	}

	@Test
	void missingRepositoryIdIs400() throws Exception {
		mockMvc.perform(put("/__contract/github-repositories")
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"role\":\"FRONTEND\"}]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.message").value("Request is invalid."));
	}

	@Test
	void invalidEnumRoleIs400Not500() throws Exception {
		mockMvc.perform(put("/__contract/github-repositories")
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"repositoryId\":1338790015,\"role\":\"frontend\"}]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
				.andExpect(jsonPath("$.message").value("Request is invalid."));
	}

	@RestController
	static class SelectGitHubRepositoryRequestValidationFixture {

		@PutMapping("/__contract/github-repositories")
		ResponseEntity<Void> select(@Valid @RequestBody List<@Valid SelectGitHubRepositoryRequest> body) {
			return ResponseEntity.noContent().build();
		}
	}
}
