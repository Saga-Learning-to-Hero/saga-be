package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.ai.AiAnalysisEvidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A whole class is sent as one summary row plus one row per team, and joined back for the Word report. */
class AiProgressTeamFactsTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private static Map<String, Object> team(int no) {
		Map<String, Object> team = new LinkedHashMap<>();
		team.put("teamName", "Nhóm " + no);
		team.put("members", List.of(Map.of("name", "SV " + no)));
		return team;
	}

	@Test
	void eachTeamTravelsAsItsOwnEvidenceRow_andTheReportGetsThemAllBack() throws Exception {
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("scope", "COURSE");
		facts.put("overdueCount", 1);
		facts.put("teams", List.of(team(1), team(2), team(3)));

		List<AiEvidenceDraft> drafts = AiProgressTeamFacts.split(mapper, "course:abc", new AiProgressFactsBuilder.Facts(facts));

		assertThat(drafts).extracting(AiEvidenceDraft::sourceRef)
				.containsExactly("progress-facts:course:abc", "progress-team:1", "progress-team:2", "progress-team:3");
		assertThat(mapper.readTree(drafts.getFirst().payloadJson()).has("teams")).isFalse();

		List<AiAnalysisEvidence> rows = new ArrayList<>();
		for (AiEvidenceDraft draft : drafts) {
			AiAnalysisEvidence row = new AiAnalysisEvidence();
			row.setSourceRef(draft.sourceRef());
			row.setPayloadJson(draft.payloadJson());
			rows.add(row);
		}
		JsonNode merged = mapper.readTree(AiProgressTeamFacts.merge(mapper, rows));
		assertThat(merged.path("overdueCount").asInt()).isEqualTo(1);
		assertThat(merged.path("teams")).hasSize(3);
		assertThat(merged.path("teams").get(2).path("teamName").asText()).isEqualTo("Nhóm 3");
	}

	@Test
	void aStudentReportWithoutTeamsStaysOneRow() throws Exception {
		List<AiEvidenceDraft> drafts = AiProgressTeamFacts.split(mapper, "student:x", new AiProgressFactsBuilder.Facts(Map.of("scope", "STUDENT")));

		assertThat(drafts).hasSize(1);
		AiAnalysisEvidence row = new AiAnalysisEvidence();
		row.setSourceRef(drafts.getFirst().sourceRef());
		row.setPayloadJson(drafts.getFirst().payloadJson());
		assertThat(mapper.readTree(AiProgressTeamFacts.merge(mapper, List.of(row))).path("scope").asText()).isEqualTo("STUDENT");
	}
}
