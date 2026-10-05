package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A system-key run is recorded with saga-be's default model; the screen and the log show what really answered. */
class AiActualModelTest {

	@Test
	void theModelSagaAiReportedIsUsed() {
		AiActualModel actual = AiActualModel.from("{\"responseId\":\"r1\",\"providerKey\":\"cohere\",\"modelId\":\"command-a-plus-05-2026\"}");

		assertThat(actual).isEqualTo(new AiActualModel("COHERE", "command-a-plus-05-2026"));
	}

	@Test
	void nothingUsableFallsBackToTheConfiguredModel() {
		assertThat(AiActualModel.from(null)).isNull();
		assertThat(AiActualModel.from("")).isNull();
		assertThat(AiActualModel.from("not json")).isNull();
		assertThat(AiActualModel.from("{\"providerKey\":\"fake\",\"modelId\":\"fake-model\"}")).isNull();
		assertThat(AiActualModel.from("{\"providerKey\":\"gemini\",\"modelId\":\"\"}")).isNull();
	}
}
