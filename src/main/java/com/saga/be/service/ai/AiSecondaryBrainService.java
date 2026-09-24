package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * OFF by default (saga.ai.secondary-enabled=false): {@link #maybeRun} then does nothing and the
 * behavior is unchanged (exactly one PRIMARY inference). When ON, runs at most once per run,
 * fully independent of the PRIMARY outcome and never seeing the PRIMARY result — it reads only
 * the same immutable evidence bundle PRIMARY read. Never retried, never falls back to PRIMARY's
 * provider/config on failure or on missing secondary configuration (fails closed instead).
 */
@Service @Profile("!test")
public class AiSecondaryBrainService {
	private static final Logger log = LoggerFactory.getLogger(AiSecondaryBrainService.class);
	private final AiAnalysisProperties properties; private final List<AiModelProvider> providers; private final AiAnalysisProviderDecisionRepository decisions; private final AiResultValidation validation; private final ObjectMapper mapper; private final AiCredentialResolver credentialResolver;

	public AiSecondaryBrainService(AiAnalysisProperties properties, List<AiModelProvider> providers, AiAnalysisProviderDecisionRepository decisions, AiResultValidation validation, ObjectMapper mapper, AiCredentialResolver credentialResolver) {
		this.properties = properties; this.providers = providers; this.decisions = decisions; this.validation = validation; this.mapper = mapper; this.credentialResolver = credentialResolver;
	}

	public void maybeRun(AiAnalysisStateService.ExecutionInput input) {
		if (!properties.isSecondaryEnabled()) return;
		AiModelProvider secondary = providers.stream().filter(p -> p.role() == AiProviderRole.SECONDARY).findFirst().orElse(null);
		if (secondary == null) { log.warn("secondary brain enabled but no SECONDARY provider bean is registered"); return; }
		UUID runId = input.run().getId();
		// SECONDARY is always COURSE-sourced or unavailable -- never a platform credential, never
		// a silent reuse of PRIMARY's credential (section II/VIII). Missing course secondary
		// credential means secondary is simply unavailable; PRIMARY is unaffected either way.
		if (credentialResolver == null) { log.info("secondary brain has no credential resolver wired; treating as unavailable runId={}", runId); return; }
		UUID courseId = input.run().resolveCourseId();
		AiCredentialResolver.Resolution resolution = credentialResolver.resolve(courseId, input.run().getAnalysisType(), AiProviderRole.SECONDARY, AiInvocationOrigin.AUTOMATION);
		if (resolution.outcome() != AiCredentialResolver.Outcome.COURSE) return;
		if (decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY).isPresent()) return;
		AiAnalysisProviderDecision row = new AiAnalysisProviderDecision();
		row.setAnalysisRun(input.run()); row.setProviderRole(AiProviderRole.SECONDARY); row.setProviderKey(secondary.providerKey()); row.setProviderConfigHash(secondary.providerConfigHash()); row.setCredentialSource(AiCredentialSource.COURSE); row.setCourseCredentialId(resolution.courseCredentialId()); row.setCredentialFingerprint(resolution.credentialFingerprint()); row.setModelId(secondary.modelId()); AiCredentialResolver.applyBinding(row, resolution); row.setRoute(AiProviderRoute.NORMAL); row.setStatus(AiProviderDecisionStatus.PENDING);
		try { decisions.saveAndFlush(row); } catch (Exception ex) { log.info("secondary decision already exists runId={}", runId); return; }
		if (decisions.claimSecondaryPending(runId, LocalDateTime.now()) != 1) return;
		try {
			List<AiAnalysisRequest.AiEvidenceInput> evidence = input.evidence().stream().map(e -> new AiAnalysisRequest.AiEvidenceInput(e.getId(), e.getEvidenceType().name(), e.getSourceRef(), e.getPayloadJson(), e.getMetadataJson())).toList();
			AiAnalysisType type = input.run().getAnalysisType();
			if (!secondary.supportsCourseCredential()) throw new AiProviderException(AiCredentialResolver.COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER);
			AiCredentialEnvelope envelope = credentialResolver.buildEnvelope(resolution.courseCredentialId(), AiProviderRole.SECONDARY, courseId);
			AiProviderResponse response = secondary.analyze(new AiAnalysisRequest(runId, AiProviderRole.SECONDARY, type, input.run().getPromptVersion(), input.run().getTaxonomyVersion(), contractFor(type), evidence, AiCredentialSource.COURSE, envelope, resolution.binding()));
			if (response == null || response.result() == null || validation.invalidReason(type, response.result(), evidence).isPresent()) {
				decisions.failSecondaryActive(runId, "AI_ANALYSIS_RESULT_INVALID", false, LocalDateTime.now());
				log.warn("secondary brain invalid result runId={}", runId);
				return;
			}
			credentialResolver.markSuccessful(resolution.courseCredentialId());
			String resultJson = mapper.writeValueAsString(response.result());
			decisions.completeSecondaryRunning(runId, resultJson, true, response.latencyMs(), response.inputUnits(), response.outputUnits(), response.modelRevision(), response.costMetadataJson(), LocalDateTime.now());
		} catch (Exception ex) {
			String code = ex instanceof AiProviderException p ? p.safeCode()
					: ex instanceof AiCredentialCryptoException crypto ? crypto.safeCode()
					: "AI_ANALYSIS_PROVIDER_FAILED";
			if ("AI_PROVIDER_AUTH_FAILED".equals(code)) credentialResolver.markInvalid(resolution.courseCredentialId());
			else if (AiAnalysisExecutionService.CREDENTIAL_DEGRADED_CODES.contains(code)) credentialResolver.markDegraded(resolution.courseCredentialId());
			decisions.failSecondaryActive(runId, code, false, LocalDateTime.now());
			log.warn("secondary brain failed runId={} code={}", runId, code);
		}
	}

	private static String contractFor(AiAnalysisType type) {
		return switch (type) {
			case ACADEMIC_CLASSIFICATION -> AiSystemContract.ACADEMIC_CLASSIFICATION_UNTRUSTED_DATA;
			case TASK_INTELLIGENCE -> AiSystemContract.TASK_INTELLIGENCE_UNTRUSTED_DATA;
			case RISK_ANALYSIS -> AiSystemContract.RISK_ANALYSIS_UNTRUSTED_DATA;
			case PROGRESS_NARRATIVE -> AiSystemContract.PROGRESS_NARRATIVE_UNTRUSTED_DATA;
			default -> AiSystemContract.UNTRUSTED_ARTIFACT_DATA;
		};
	}
}
