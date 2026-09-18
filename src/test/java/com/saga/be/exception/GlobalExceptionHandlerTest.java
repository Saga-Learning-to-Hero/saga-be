package com.saga.be.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.ApiErrorResponse;
import com.saga.be.dto.JiraWriteIncompleteDetails;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void integrationErrorWithoutDetailsOmitsDetailsField() throws Exception {
		IntegrationException ex = new IntegrationException(
				IntegrationErrorCode.JIRA_WRITE_INCOMPLETE,
				HttpStatus.BAD_GATEWAY,
				"Jira issue was created but a secondary field update failed. Local state reflects provider truth; retry the failed field.");

		ResponseEntity<ApiErrorResponse> response = handler.handleIntegration(ex);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody().details()).isNull();
		String json = mapper.writeValueAsString(response.getBody());
		assertThat(json).isEqualTo(
				"{\"code\":\"JIRA_WRITE_INCOMPLETE\",\"message\":\"Jira issue was created but a secondary field update failed. Local state reflects provider truth; retry the failed field.\"}");
	}

	@Test
	void nativeParentIncompleteIncludesTaskIdRecoveryDetails() throws Exception {
		UUID taskId = UUID.randomUUID();
		IntegrationException ex = new IntegrationException(
				IntegrationErrorCode.JIRA_WRITE_INCOMPLETE,
				HttpStatus.BAD_GATEWAY,
				"Jira issue was created but native parent could not be assigned. Local Task already exists and reflects provider truth; do not retry create. Retry the parent assignment with PATCH.",
				JiraWriteIncompleteDetails.nativeParentNotApplied(taskId));

		ResponseEntity<ApiErrorResponse> response = handler.handleIntegration(ex);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody().code()).isEqualTo("JIRA_WRITE_INCOMPLETE");
		assertThat(response.getBody().details())
				.isEqualTo(JiraWriteIncompleteDetails.nativeParentNotApplied(taskId));
		String json = mapper.writeValueAsString(response.getBody());
		assertThat(json).contains("\"code\":\"JIRA_WRITE_INCOMPLETE\"");
		assertThat(json).contains("\"taskId\":\"" + taskId + "\"");
		assertThat(json).contains("\"providerWriteApplied\":true");
		assertThat(json).contains("\"nativeParentApplied\":false");
		assertThat(json).contains("\"recoveryAction\":\"PATCH_NATIVE_PARENT\"");
		assertThat(json).doesNotContain("token");
		assertThat(json).doesNotContain("Bearer");
	}
}
