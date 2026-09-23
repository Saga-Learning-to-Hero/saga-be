package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.ai.AiAcademicClassification;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.repository.AiAcademicClassificationRepository;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Controlled learning, not fine-tuning: bounded, deterministic retrieval of lecturer-confirmed
 * (or lecturer-corrected) prior Academic Classification examples for the SAME pinned syllabus
 * version, one target type at a time. Never PROPOSED/REJECTED/corrected-original rows (see
 * {@link AiAcademicClassificationRepository#findConfirmedExamples}, which encodes the exact same
 * authoritative definition already enforced elsewhere). No vector DB, no similarity search — a
 * simple deterministic recency ranking, MySQL/local only. When there are no examples yet,
 * inference proceeds normally with an empty example list; nothing is invented.
 */
@Component @Profile("!test")
public class AiConfirmedExampleEvidenceBuilder {
	static final int MAX_EXAMPLES_PER_TARGET_TYPE = 5;

	private final AiAcademicClassificationRepository classifications;
	private final ObjectMapper mapper;

	public AiConfirmedExampleEvidenceBuilder(AiAcademicClassificationRepository classifications, ObjectMapper mapper) {
		this.classifications = classifications; this.mapper = mapper;
	}

	public List<AiEvidenceDraft> build(UUID syllabusVersionId) {
		List<AiEvidenceDraft> draft = new ArrayList<>();
		for (AiAcademicTargetType targetType : AiAcademicTargetType.values()) {
			List<AiAcademicClassification> examples = classifications.findConfirmedExamples(syllabusVersionId, targetType, PageRequest.of(0, MAX_EXAMPLES_PER_TARGET_TYPE));
			for (AiAcademicClassification example : examples) draft.add(row(example));
		}
		return draft;
	}

	private AiEvidenceDraft row(AiAcademicClassification example) {
		Map<String, Object> payload = new TreeMap<>();
		payload.put("exampleClassificationId", example.getId());
		payload.put("targetType", example.getTargetType() == null ? null : example.getTargetType().name());
		payload.put("targetId", example.getTargetType() == com.saga.be.entity.enums.AiAcademicTargetType.PHASE
				? (example.getPhase() == null ? null : example.getPhase().getId())
				: (example.getDeliverable() == null ? null : example.getDeliverable().getId()));
		payload.put("confidence", example.getConfidence());
		payload.put("provenance", example.getProvenance() == null ? null : example.getProvenance().name());
		String json = json(payload);
		return new AiEvidenceDraft(AiEvidenceType.HUMAN_CONFIRMED_EXAMPLE, "confirmed-example:" + example.getId(), json, null);
	}

	private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
}
