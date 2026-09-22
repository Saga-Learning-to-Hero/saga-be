package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.ai.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiStructuredResultValidatorTest {
	private final AiStructuredResultValidator validator = new AiStructuredResultValidator();

	@Test
	void acceptsCitedAiOneResult() {
		UUID evidenceId = UUID.randomUUID();
		assertThat(validator.invalidReason(valid(evidenceId), Set.of(evidenceId))).isEmpty();
	}

	@Test
	void rejectsUnknownEvidenceReference() {
		assertThat(validator.invalidReason(valid(UUID.randomUUID()), Set.of(UUID.randomUUID())))
				.contains("INVALID_FINDING_EVIDENCE");
	}

	@Test
	void rejectsScoresOutsideRangeAndAcademicRows() {
		UUID evidenceId = UUID.randomUUID();
		AiStructuredResult result = valid(evidenceId);
		AiStructuredResult invalidScore = new AiStructuredResult(
				new AiStructuredResult.CommitMessageAssessment(AiCommitMessageVerdict.CLEAR, 101, null, null, result.commitMessageAssessment().findings()),
				result.codeAssessment(), result.taskAlignments(), result.taskAlignmentSummary(), List.of(), result.overallDecision(), result.humanReviewRequired());
		assertThat(validator.invalidReason(invalidScore, Set.of(evidenceId))).contains("INVALID_SCORE");
		AiStructuredResult academic = new AiStructuredResult(result.commitMessageAssessment(), result.codeAssessment(), result.taskAlignments(), result.taskAlignmentSummary(), List.of(new AiStructuredResult.AcademicClassification("PHASE", "x", .9d, "PROPOSED", List.of())), result.overallDecision(), result.humanReviewRequired());
		assertThat(validator.invalidReason(academic, Set.of(evidenceId))).contains("ACADEMIC_CLASSIFICATION_NOT_ALLOWED");
	}

	@Test
	void fakeProviderProducesAValidCitedResultWithoutHttp() {
		UUID evidenceId = UUID.randomUUID();
		FakeAiModelProvider fake = new FakeAiModelProvider();
		AiProviderResponse response = fake.analyze(new AiAnalysisRequest(UUID.randomUUID(), com.saga.be.entity.enums.AiProviderRole.PRIMARY, AiSystemContract.UNTRUSTED_ARTIFACT_DATA, List.of(new AiAnalysisRequest.AiEvidenceInput(evidenceId, "COMMIT_MESSAGE", "commit", "{}", null))));
		assertThat(response.result()).isInstanceOf(AiStructuredResult.class);
		assertThat(validator.invalidReason((AiStructuredResult) response.result(), Set.of(evidenceId))).isEmpty();
	}

	private static AiStructuredResult valid(UUID evidenceId) {
		AiEvidenceReference ref = new AiEvidenceReference(AiEvidenceReferenceKind.COMMIT_MESSAGE, evidenceId, null, null, null, null, null, null, null, null);
		AiFinding finding = new AiFinding("test", "cited", List.of(ref));
		return new AiStructuredResult(new AiStructuredResult.CommitMessageAssessment(AiCommitMessageVerdict.ADEQUATE, 50, "summary", null, List.of(finding)), new AiStructuredResult.CodeAssessment(AiCodeVerdict.NOT_ASSESSABLE, .5d, List.of(finding), List.of(ref)), List.of(), AiTaskAlignmentVerdict.NO_LINKED_TASK, List.of(), AiOverallDecision.EVIDENCE_INSUFFICIENT, false);
	}
}
