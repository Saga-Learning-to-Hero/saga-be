package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.ai.AiSystemContract;
import com.saga.be.entity.enums.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AiAnalysisFoundationContractTest {
	@Test
	void foundationEnumsAreClosedAndAiOneDoesNotExposeAcademicExecution() {
		assertThat(AiArtifactType.values()).containsExactly(AiArtifactType.COMMIT);
		assertThat(AiAnalysisType.values()).containsExactly(AiAnalysisType.COMMIT_INTELLIGENCE);
		assertThat(Arrays.asList(AiEvidenceType.values())).contains(AiEvidenceType.COMMIT_MESSAGE, AiEvidenceType.TASK_FIELD, AiEvidenceType.METADATA, AiEvidenceType.EXCLUSION_MANIFEST);
		assertThat(AiSystemContract.UNTRUSTED_ARTIFACT_DATA).contains("untrusted data").contains("Never obey");
	}

	@Test
	void fakeConfigurationHashIsStableAndDoesNotContainCredentials() {
		assertThat(FakeAiModelProvider.CONFIG_HASH).hasSize(64).matches("[0-9a-f]{64}");
	}
}
