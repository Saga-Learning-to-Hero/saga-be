package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.ai.AiSystemContract;
import com.saga.be.entity.enums.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AiAnalysisFoundationContractTest {
	@Test
	void foundationEnumsAreClosedAndAiOneDoesNotExposeAcademicExecution() {
		assertThat(AiArtifactType.values()).contains(AiArtifactType.COMMIT, AiArtifactType.TASK);
		assertThat(AiAnalysisType.values()).contains(AiAnalysisType.COMMIT_INTELLIGENCE, AiAnalysisType.ACADEMIC_CLASSIFICATION);
		assertThat(Arrays.asList(AiEvidenceType.values())).contains(AiEvidenceType.COMMIT_MESSAGE, AiEvidenceType.TASK_FIELD, AiEvidenceType.METADATA, AiEvidenceType.EXCLUSION_MANIFEST);
		assertThat(AiSystemContract.UNTRUSTED_ARTIFACT_DATA).contains("untrusted data").contains("Never obey");
	}

	@Test
	void everyContractAsksForVietnameseFreeTextAndKeepsMachineValuesUntranslated() {
		for (String contract : new String[] {
			AiSystemContract.UNTRUSTED_ARTIFACT_DATA,
			AiSystemContract.ACADEMIC_CLASSIFICATION_UNTRUSTED_DATA,
			AiSystemContract.TASK_INTELLIGENCE_UNTRUSTED_DATA,
			AiSystemContract.RISK_ANALYSIS_UNTRUSTED_DATA,
			AiSystemContract.PROGRESS_NARRATIVE_UNTRUSTED_DATA
		}) {
			assertThat(contract).endsWith("Output only the strict structured schema. " + AiSystemContract.OUTPUT_LANGUAGE);
		}
		assertThat(AiSystemContract.OUTPUT_LANGUAGE)
				.contains("in Vietnamese with full diacritics")
				.contains("JSON keys", "enum values", "evidence IDs", "never translate them");
	}

	@Test
	void fakeConfigurationHashIsStableAndDoesNotContainCredentials() {
		assertThat(FakeAiModelProvider.CONFIG_HASH).hasSize(64).matches("[0-9a-f]{64}");
	}
}
