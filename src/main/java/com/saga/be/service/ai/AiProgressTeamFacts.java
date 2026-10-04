package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saga.be.entity.ai.AiAnalysisEvidence;
import com.saga.be.entity.enums.AiEvidenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Joins a progress report's evidence rows back into one facts snapshot (summary + teams). */
final class AiProgressTeamFacts {

	static final String SUMMARY_PREFIX = "progress-facts:";
	static final String SOURCE_PREFIX = "progress-team:";

	private AiProgressTeamFacts() {}

	/** The summary as {@code progress-facts:<subject>} plus one {@code progress-team:<n>} row per team. */
	static List<AiEvidenceDraft> split(ObjectMapper mapper, String subjectRef, AiProgressFactsBuilder.Facts data) {
		try {
			Object teams = data.data().get("teams");
			if (!(teams instanceof List<?> list) || list.isEmpty()) {
				return List.of(new AiEvidenceDraft(AiEvidenceType.METADATA, SUMMARY_PREFIX + subjectRef, data.json(mapper), null));
			}
			Map<String, Object> summary = new TreeMap<>(data.data());
			summary.remove("teams");
			List<AiEvidenceDraft> rows = new ArrayList<>();
			rows.add(new AiEvidenceDraft(AiEvidenceType.METADATA, SUMMARY_PREFIX + subjectRef, mapper.writeValueAsString(summary), null));
			for (int i = 0; i < list.size(); i++) {
				rows.add(new AiEvidenceDraft(AiEvidenceType.METADATA, SOURCE_PREFIX + (i + 1), mapper.writeValueAsString(list.get(i)), null));
			}
			return rows;
		} catch (Exception ex) {
			throw new IllegalStateException("Cannot serialize progress facts", ex);
		}
	}

	static String merge(ObjectMapper mapper, List<AiAnalysisEvidence> rows) {
		String summary = null;
		for (AiAnalysisEvidence row : rows) {
			if (row.getSourceRef() != null && row.getSourceRef().startsWith(SUMMARY_PREFIX)) {
				summary = row.getPayloadJson();
				break;
			}
		}
		if (summary == null) throw new IllegalStateException("Progress narrative run is missing its progress-facts evidence row");
		List<AiAnalysisEvidence> teams = rows.stream()
				.filter(row -> row.getSourceRef() != null && row.getSourceRef().startsWith(SOURCE_PREFIX))
				.toList();
		if (teams.isEmpty()) return summary;
		try {
			ObjectNode merged = (ObjectNode) mapper.readTree(summary);
			ArrayNode list = merged.putArray("teams");
			for (AiAnalysisEvidence team : teams) list.add(mapper.readTree(team.getPayloadJson()));
			return mapper.writeValueAsString(merged);
		} catch (Exception ex) {
			return summary;
		}
	}
}
