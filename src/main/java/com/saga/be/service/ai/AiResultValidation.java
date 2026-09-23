package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.entity.enums.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The single place that dispatches an {@link AiAnalysisResult} to its type-specific validator.
 * Shared by the PRIMARY execution path ({@link AiAnalysisExecutionService}) and the SECONDARY
 * brain path ({@link AiSecondaryBrainService}) so both brains are held to exactly the same
 * deterministic correctness bar — a malformed/invalid SECONDARY result is never silently accepted.
 */
@Component
@Profile("!test")
public class AiResultValidation {
	private final AiStructuredResultValidator validator; private final AiAcademicResultValidator academicValidator; private final AiTaskIntelligenceResultValidator taskValidator; private final AiRiskAnalysisResultValidator riskValidator; private final AiProgressNarrativeResultValidator progressValidator; private final ObjectMapper mapper;

	public AiResultValidation(AiStructuredResultValidator validator, AiAcademicResultValidator academicValidator, AiTaskIntelligenceResultValidator taskValidator, AiRiskAnalysisResultValidator riskValidator, AiProgressNarrativeResultValidator progressValidator, ObjectMapper mapper) {
		this.validator = validator; this.academicValidator = academicValidator; this.taskValidator = taskValidator; this.riskValidator = riskValidator; this.progressValidator = progressValidator; this.mapper = mapper;
	}

	public Optional<String> invalidReason(AiAnalysisType type, AiAnalysisResult result, List<AiAnalysisRequest.AiEvidenceInput> evidence) {
		Set<UUID> evidenceIds = evidence.stream().map(AiAnalysisRequest.AiEvidenceInput::id).collect(java.util.stream.Collectors.toSet());
		return switch (type) {
			case ACADEMIC_CLASSIFICATION -> !(result instanceof AiAcademicClassificationResult academic) ? Optional.of("MISSING_RESULT") : academicValidator.invalidReason(academic, evidence);
			case TASK_INTELLIGENCE -> !(result instanceof AiTaskIntelligenceResult task) ? Optional.of("MISSING_RESULT") : taskValidator.invalidReason(task, evidenceIds);
			case RISK_ANALYSIS -> !(result instanceof AiRiskAnalysisResult risk) ? Optional.of("MISSING_RESULT") : riskValidator.invalidReason(risk, evidenceIds);
			case PROGRESS_NARRATIVE -> !(result instanceof AiProgressNarrativeResult progress) ? Optional.of("MISSING_RESULT") : progressValidator.invalidReason(progress, evidenceIds);
			default -> !(result instanceof AiStructuredResult structured) ? Optional.of("MISSING_RESULT") : validator.invalidReason(structured, evidenceIds, taskIds(evidence), completeCoverage(evidence));
		};
	}
	private Set<UUID> taskIds(List<AiAnalysisRequest.AiEvidenceInput> rows) { Set<UUID> ids = new HashSet<>(); for (var row : rows) if ("TASK_FIELD".equals(row.type())) try { String id = mapper.readTree(row.payloadJson()).path("taskId").asText(null); if (id != null) ids.add(UUID.fromString(id)); } catch (Exception ignored) {} return ids; }
	private boolean completeCoverage(List<AiAnalysisRequest.AiEvidenceInput> rows) { for (var row : rows) if ("CHANGED_FILE_MANIFEST".equals(row.type())) try { return "COMPLETE".equals(mapper.readTree(row.payloadJson()).path("coverage").asText()); } catch (Exception ignored) { return false; } return false; }
}
