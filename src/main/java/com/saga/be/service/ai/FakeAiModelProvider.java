package com.saga.be.service.ai;

import com.saga.be.ai.*;
import com.saga.be.entity.enums.AiProviderRole;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test/local-only deterministic smoke provider. It is never active in normal production profiles. */
@Component @Profile({"local", "test"})
public class FakeAiModelProvider implements AiModelProvider {
	public static final String CONFIG_HASH = AiHashes.sha256("fake-ai-1");
	public AiProviderRole role() { return AiProviderRole.PRIMARY; }
	public String providerKey() { return "fake"; }
	public String providerConfigHash() { return CONFIG_HASH; }
	public String modelId() { return "fake-ai-1"; }
	public AiProviderResponse analyze(AiAnalysisRequest request) {
		AiEvidenceReference ref = new AiEvidenceReference(AiEvidenceReferenceKind.COMMIT_MESSAGE, request.evidence().getFirst().id(), null, null, null, null, null, null, null, null);
		AiFinding finding = new AiFinding("AI1_FAKE_INFORMATIONAL", "Deterministic AI-1 provider smoke result.", List.of(ref));
		return new AiProviderResponse(new AiStructuredResult(new AiStructuredResult.CommitMessageAssessment(AiCommitMessageVerdict.INSUFFICIENT_EVIDENCE, 0, "AI-1 fake provider has no semantic judgement.", null, List.of(finding)), new AiStructuredResult.CodeAssessment(AiCodeVerdict.NOT_ASSESSABLE, 0d, List.of(finding), List.of(ref)), List.of(), AiTaskAlignmentVerdict.NO_LINKED_TASK, List.of(), AiOverallDecision.EVIDENCE_INSUFFICIENT, true), 0L, 0L, 0L, "fake-1", null);
	}
}
