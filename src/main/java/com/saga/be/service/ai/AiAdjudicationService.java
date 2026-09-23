package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic backend adjudicator — no third AI call. Compares the PRIMARY and SECONDARY
 * provider decisions of one run and records exactly one of AGREED / MINOR_DISAGREEMENT /
 * MAJOR_DISAGREEMENT / PRIMARY_ONLY / SECONDARY_ONLY / FAILED. A malformed/invalid decision is
 * never treated as an agreement. Runs at most once per run (idempotent via the unique
 * analysis_run_id on ai_analysis_adjudication).
 */
@Service @Profile("!test")
public class AiAdjudicationService {
	private final AiAnalysisRunRepository runs; private final AiAnalysisProviderDecisionRepository decisions; private final AiAnalysisAdjudicationRepository adjudications; private final ObjectMapper mapper;

	public AiAdjudicationService(AiAnalysisRunRepository runs, AiAnalysisProviderDecisionRepository decisions, AiAnalysisAdjudicationRepository adjudications, ObjectMapper mapper) {
		this.runs = runs; this.decisions = decisions; this.adjudications = adjudications; this.mapper = mapper;
	}

	@Transactional
	public void adjudicate(UUID runId) {
		if (adjudications.findByAnalysisRun_Id(runId).isPresent()) return;
		AiAnalysisProviderDecision secondary = decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY).orElse(null);
		if (secondary == null) return; // secondary brain not attempted for this run: nothing to adjudicate
		AiAnalysisRun run = runs.findById(runId).orElseThrow();
		AiAnalysisProviderDecision primary = decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY).orElse(null);
		boolean primaryOk = primary != null && primary.getStatus() == AiProviderDecisionStatus.COMPLETED && primary.getStructuredResultJson() != null;
		boolean secondaryOk = secondary.getStatus() == AiProviderDecisionStatus.COMPLETED && secondary.getStructuredResultJson() != null;

		AiAdjudicationOutcome outcome;
		Map<String, Object> details = new TreeMap<>();
		if (!primaryOk && !secondaryOk) {
			outcome = AiAdjudicationOutcome.FAILED;
		} else if (primaryOk && !secondaryOk) {
			outcome = AiAdjudicationOutcome.PRIMARY_ONLY;
		} else if (!primaryOk && secondaryOk) {
			outcome = AiAdjudicationOutcome.SECONDARY_ONLY;
		} else {
			Comparison result = compare(run.getAnalysisType(), primary.getStructuredResultJson(), secondary.getStructuredResultJson());
			outcome = result.outcome();
			details = result.details();
		}
		boolean humanReviewRequired = outcome == AiAdjudicationOutcome.MAJOR_DISAGREEMENT || outcome == AiAdjudicationOutcome.FAILED
				|| outcome == AiAdjudicationOutcome.PRIMARY_ONLY || outcome == AiAdjudicationOutcome.SECONDARY_ONLY;

		AiAnalysisAdjudication row = new AiAnalysisAdjudication();
		row.setAnalysisRun(run);
		row.setOutcome(outcome);
		row.setDisagreementDetailsJson(json(details));
		row.setHumanReviewRequired(humanReviewRequired);
		try { adjudications.saveAndFlush(row); } catch (org.springframework.dao.DataIntegrityViolationException ignored) { /* another writer won the race; adjudication is idempotent */ }
	}

	private record Comparison(AiAdjudicationOutcome outcome, Map<String, Object> details) {}

	private Comparison compare(AiAnalysisType type, String primaryJson, String secondaryJson) {
		JsonNode p, s;
		try { p = mapper.readTree(primaryJson); s = mapper.readTree(secondaryJson); }
		catch (Exception ex) { return new Comparison(AiAdjudicationOutcome.FAILED, Map.of("reason", "UNPARSEABLE_RESULT")); }
		return switch (type) {
			case RISK_ANALYSIS -> compareOrdinal(text(p, "riskLevel"), text(s, "riskLevel"), List.of("LOW", "MEDIUM", "HIGH"));
			case ACADEMIC_CLASSIFICATION -> compareSets(classificationTargets(p), classificationTargets(s));
			case COMMIT_INTELLIGENCE -> compareEquality(text(p, "overallDecision"), text(s, "overallDecision"), "overallDecision");
			case TASK_INTELLIGENCE -> compareEquality(text(p, "evidenceStrength"), text(s, "evidenceStrength"), "evidenceStrength");
			case PROGRESS_NARRATIVE -> compareEquality(text(p, "humanReviewRecommended"), text(s, "humanReviewRecommended"), "humanReviewRecommended");
		};
	}

	private Comparison compareOrdinal(String a, String b, List<String> scale) {
		int ia = scale.indexOf(a), ib = scale.indexOf(b);
		if (ia < 0 || ib < 0) return new Comparison(AiAdjudicationOutcome.FAILED, Map.of("reason", "UNKNOWN_ORDINAL_VALUE", "primary", String.valueOf(a), "secondary", String.valueOf(b)));
		int diff = Math.abs(ia - ib);
		AiAdjudicationOutcome outcome = diff == 0 ? AiAdjudicationOutcome.AGREED : diff == 1 ? AiAdjudicationOutcome.MINOR_DISAGREEMENT : AiAdjudicationOutcome.MAJOR_DISAGREEMENT;
		return new Comparison(outcome, diff == 0 ? Map.of() : Map.of("primary", a, "secondary", b));
	}

	private Comparison compareEquality(String a, String b, String field) {
		boolean equal = Objects.equals(a, b);
		return new Comparison(equal ? AiAdjudicationOutcome.AGREED : AiAdjudicationOutcome.MAJOR_DISAGREEMENT, equal ? Map.of() : Map.of("field", field, "primary", String.valueOf(a), "secondary", String.valueOf(b)));
	}

	private Comparison compareSets(Set<String> a, Set<String> b) {
		boolean equal = a.equals(b);
		if (equal) return new Comparison(AiAdjudicationOutcome.AGREED, Map.of());
		Set<String> onlyPrimary = new TreeSet<>(a); onlyPrimary.removeAll(b);
		Set<String> onlySecondary = new TreeSet<>(b); onlySecondary.removeAll(a);
		return new Comparison(AiAdjudicationOutcome.MAJOR_DISAGREEMENT, Map.of("onlyPrimary", onlyPrimary, "onlySecondary", onlySecondary));
	}

	private Set<String> classificationTargets(JsonNode result) {
		Set<String> targets = new TreeSet<>();
		String decision = text(result, "classificationDecision");
		if (!"PROPOSED".equals(decision)) { targets.add("DECISION:" + decision); return targets; }
		for (JsonNode item : result.path("classifications")) targets.add(text(item, "targetType") + ":" + text(item, "targetId"));
		return targets;
	}

	private static String text(JsonNode node, String field) { JsonNode value = node.path(field); return value.isMissingNode() || value.isNull() ? null : value.asText(); }
	private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
}
