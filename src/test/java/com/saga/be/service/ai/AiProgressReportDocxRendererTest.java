package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.ai.AiProgressNarrative;
import com.saga.be.entity.enums.AiArtifactType;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

class AiProgressReportDocxRendererTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final AiProgressReportDocxRenderer renderer = new AiProgressReportDocxRenderer(mapper);

	@Test
	void rendersAValidDocxContainingFactsAndNarrativeButNoSecrets() throws Exception {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(UUID.randomUUID());
		run.setArtifactType(AiArtifactType.STUDENT);
		run.setArtifactId(UUID.randomUUID());
		run.setProviderConfigHash("should-not-appear-in-the-document");

		AiProgressNarrative narrative = new AiProgressNarrative();
		narrative.setAnalysisRun(run);
		narrative.setFactsJson("{\"overdueCount\":2,\"dueSoonCount\":1,\"taskStatusCounts\":{\"DONE\":5,\"TODO\":1}}");
		narrative.setOverview("The student has completed most assigned tasks this sprint.");
		narrative.setHighlightsJson("[\"Consistent recent commit activity\"]");
		narrative.setConcernsJson("[\"One task has been inactive for a while\"]");
		narrative.setRecommendationsJson("[\"Check in about the overdue task\"]");
		narrative.setBlockersJson("[]");
		narrative.setDueSoonOverdueNote("2 tasks are overdue and 1 is due soon (deterministic count).");
		narrative.setHumanReviewRecommended(true);

		byte[] bytes = renderer.render(run, narrative);
		assertThat(bytes).isNotEmpty();

		StringBuilder text = new StringBuilder();
		try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
			for (XWPFParagraph p : doc.getParagraphs()) text.append(p.getText()).append('\n');
		}
		String content = text.toString();
		assertThat(content).contains("SAGA Progress Report");
		assertThat(content).contains("overdueCount: 2");
		assertThat(content).contains("The student has completed most assigned tasks this sprint.");
		assertThat(content).contains("Consistent recent commit activity");
		assertThat(content).contains("2 tasks are overdue and 1 is due soon");
		assertThat(content).contains("Check in about the overdue task");
		assertThat(content).contains("Yes"); // humanReviewRecommended
		assertThat(content).doesNotContain("should-not-appear-in-the-document");
	}

	@Test
	void filenameHasNoPathTraversalAndIsDerivedOnlyFromRunIdentity() {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(UUID.randomUUID());
		run.setArtifactType(AiArtifactType.TEAM);
		String filename = renderer.filename(run);
		assertThat(filename).isEqualTo("saga-progress-report-team-" + run.getId() + ".docx");
		assertThat(filename).doesNotContain("..").doesNotContain("/").doesNotContain("\\");
	}

	@Test
	void malformedFactsOrListJsonRendersSafelyInsteadOfThrowing() {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(UUID.randomUUID());
		run.setArtifactType(AiArtifactType.COURSE);
		run.setArtifactId(UUID.randomUUID());
		AiProgressNarrative narrative = new AiProgressNarrative();
		narrative.setFactsJson("not-json");
		narrative.setOverview("ok");
		narrative.setHighlightsJson("not-json");
		narrative.setConcernsJson("not-json");
		narrative.setRecommendationsJson("not-json");
		narrative.setBlockersJson("not-json");
		narrative.setDueSoonOverdueNote("ok");
		byte[] bytes = renderer.render(run, narrative);
		assertThat(bytes).isNotEmpty();
	}
}
