package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.ai.AiFailureResponse;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiFailureMessagesTest {

	private static final String GENERIC_TITLE = "Phân tích AI thất bại";

	/** Every failure code saga-be or saga-ai can put on a run. */
	private static final List<String> KNOWN_CODES = List.of(
			"AI_ANALYSIS_PROVIDER_FAILED", "AI_ANALYSIS_RESULT_INVALID", "AI_CONTRACT_VERSION_UNSUPPORTED",
			"AI_COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER", "AI_CREDENTIAL_DECRYPTION_FAILED", "AI_CREDENTIAL_ENCRYPTION_FAILED",
			"AI_CREDENTIAL_ENVELOPE_INVALID", "AI_CREDENTIAL_ENVELOPE_SOURCE_MISSING", "AI_CREDENTIAL_ENVELOPE_UNAVAILABLE",
			"AI_CREDENTIAL_KEY_VERSION_UNSUPPORTED", "AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED",
			"AI_CREDENTIAL_TRANSPORT_ENCRYPTION_FAILED", "AI_CREDENTIAL_TRANSPORT_KEY_NOT_CONFIGURED",
			"AI_CREDENTIAL_TRANSPORT_NOT_CONFIGURED", "AI_CREDENTIAL_UNAVAILABLE", "AI_MODEL_CAPABILITY_UNSUPPORTED",
			"AI_MODEL_NOT_SUPPORTED", "AI_PROVIDER_AUTH_FAILED", "AI_PROVIDER_FAILED", "AI_PROVIDER_MODEL_NOT_FOUND",
			"AI_PROVIDER_NOT_CONFIGURED", "AI_PROVIDER_NOT_SUPPORTED", "AI_PROVIDER_QUOTA_EXHAUSTED",
			"AI_PROVIDER_RATE_LIMITED", "AI_PROVIDER_RESULT_INVALID", "AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE",
			"AI_QUEUE_CAPACITY_EXCEEDED", "AI_RUNNING_STALE_RECOVERED", "AI_RUNTIME_DISABLED",
			"AI_RUNTIME_NOT_CONFIGURED", "AI_RUNTIME_OUTDATED", "AI_RUNTIME_UNAVAILABLE");

	@Test
	void everyKnownCodeHasItsOwnReadableText() {
		for (String code : KNOWN_CODES) {
			AiFailureResponse failure = AiFailureMessages.describe(code);
			assertThat(failure.code()).isEqualTo(code);
			assertThat(failure.title()).as(code).isNotBlank().isNotEqualTo(GENERIC_TITLE).doesNotContain("AI_");
			assertThat(failure.message()).as(code).isNotBlank().doesNotContain("AI_");
			assertThat(failure.hint()).as(code).isNotBlank().doesNotContain("AI_");
		}
	}

	@Test
	void theTwoScreenshotErrorsReadWellAndCanBeRetried() {
		AiFailureResponse timeout = AiFailureMessages.describe("AI_PROVIDER_TIMEOUT");
		assertThat(timeout.title()).isEqualTo("AI phản hồi quá lâu");
		assertThat(timeout.retryable()).isTrue();

		AiFailureResponse invalid = AiFailureMessages.describe("AI_PROVIDER_RESULT_INVALID");
		assertThat(invalid.title()).isEqualTo("Kết quả AI không hợp lệ");
		assertThat(invalid.retryable()).isTrue();
		assertThat(AiFailureMessages.describe("AI_ANALYSIS_RESULT_INVALID").title()).isEqualTo(invalid.title());
	}

	@Test
	void configurationProblemsAreNotOfferedAsRetry() {
		for (String code : List.of("AI_PROVIDER_QUOTA_EXHAUSTED", "AI_PROVIDER_AUTH_FAILED", "AI_MODEL_NOT_SUPPORTED",
				"AI_CREDENTIAL_UNAVAILABLE", "AI_RUNTIME_NOT_CONFIGURED", "AI_RUNTIME_OUTDATED", "AI_CREDENTIAL_DECRYPTION_FAILED")) {
			assertThat(AiFailureMessages.describe(code).retryable()).as(code).isFalse();
		}
	}

	@Test
	void unknownCodeStillGetsAReadableTextAndKeepsTheCode() {
		AiFailureResponse failure = AiFailureMessages.describe(" SOMETHING_NEW ");
		assertThat(failure.code()).isEqualTo("SOMETHING_NEW");
		assertThat(failure.title()).isEqualTo(GENERIC_TITLE);
		assertThat(failure.retryable()).isTrue();
		assertThat(AiFailureMessages.describe(null)).isNull();
		assertThat(AiFailureMessages.describe("  ")).isNull();
	}

	@Test
	void onlyAFailedRunCarriesAFailureAndItFallsBackToTheProviderDecisionCode() {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setStatus(AiAnalysisStatus.FAILED);
		run.setFailureCode("AI_PROVIDER_TIMEOUT");
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setSafeErrorCode("AI_PROVIDER_RATE_LIMITED");
		assertThat(AiAnalysisReadService.failure(run, decision).code()).isEqualTo("AI_PROVIDER_TIMEOUT");

		run.setFailureCode(null);
		assertThat(AiAnalysisReadService.failure(run, decision).code()).isEqualTo("AI_PROVIDER_RATE_LIMITED");

		assertThat(AiAnalysisReadService.failure(run, null).title()).isEqualTo(GENERIC_TITLE);

		run.setStatus(AiAnalysisStatus.COMPLETED);
		run.setFailureCode("AI_PROVIDER_TIMEOUT");
		assertThat(AiAnalysisReadService.failure(run, decision)).isNull();
		run.setStatus(AiAnalysisStatus.RUNNING);
		assertThat(AiAnalysisReadService.failure(run, decision)).isNull();
	}
}
