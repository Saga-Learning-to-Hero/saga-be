package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.ai.AiProgressNarrative;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Renders a completed Progress Narrative run into a .docx file, entirely in saga-be (never in the
 * AI runtime). Every number/fact comes from the persisted deterministic facts snapshot and the
 * persisted AI narrative — nothing is computed or invented here. No secrets or internal metadata
 * (provider keys, tokens, prompt/schema internals) are ever written into the document.
 */
@Component @Profile("!test")
public class AiProgressReportDocxRenderer {
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private final ObjectMapper mapper;

	public AiProgressReportDocxRenderer(ObjectMapper mapper) { this.mapper = mapper; }

	public byte[] render(AiAnalysisRun run, AiProgressNarrative narrative) {
		try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			title(doc, "SAGA Progress Report");
			paragraph(doc, "Generated: " + (run.getCompletedAt() == null ? "" : run.getCompletedAt().format(TIMESTAMP)) + " (UTC)");
			paragraph(doc, "Scope: " + run.getArtifactType() + "  •  Subject ID: " + run.getArtifactId());
			paragraph(doc, "Analysis ID: " + run.getId());

			heading(doc, "Deterministic Progress Facts");
			for (Map.Entry<String, Object> entry : facts(narrative.getFactsJson()).entrySet()) bullet(doc, entry.getKey() + ": " + String.valueOf(entry.getValue()));

			heading(doc, "Overview");
			paragraph(doc, narrative.getOverview());

			heading(doc, "Due Soon / Overdue");
			paragraph(doc, narrative.getDueSoonOverdueNote());

			heading(doc, "Highlights");
			bulletsOrNone(doc, stringList(narrative.getHighlightsJson()));

			heading(doc, "Concerns / Risks");
			bulletsOrNone(doc, stringList(narrative.getConcernsJson()));

			heading(doc, "Blockers");
			bulletsOrNone(doc, stringList(narrative.getBlockersJson()));

			heading(doc, "Recommendations");
			bulletsOrNone(doc, stringList(narrative.getRecommendationsJson()));

			heading(doc, "Human Review Recommended");
			paragraph(doc, narrative.isHumanReviewRecommended() ? "Yes" : "No");

			doc.write(out);
			return out.toByteArray();
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to render progress report DOCX", ex);
		}
	}

	/** Safe: no user input, no path traversal — a fixed prefix plus the run's own UUID only. */
	public String filename(AiAnalysisRun run) {
		return "saga-progress-report-" + run.getArtifactType().name().toLowerCase() + "-" + run.getId() + ".docx";
	}

	private Map<String, Object> facts(String factsJson) {
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = mapper.readValue(factsJson, Map.class);
			return new LinkedHashMap<>(parsed);
		} catch (Exception ex) { return Map.of(); }
	}

	private List<String> stringList(String json) {
		try { return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
		catch (Exception ex) { return List.of(); }
	}

	private static void title(XWPFDocument doc, String text) {
		XWPFParagraph p = doc.createParagraph(); p.setAlignment(ParagraphAlignment.LEFT);
		XWPFRun run = p.createRun(); run.setText(text); run.setBold(true); run.setFontSize(20);
	}
	private static void heading(XWPFDocument doc, String text) {
		XWPFParagraph p = doc.createParagraph();
		XWPFRun run = p.createRun(); run.setText(text); run.setBold(true); run.setFontSize(14); run.addBreak();
	}
	private static void paragraph(XWPFDocument doc, String text) {
		XWPFParagraph p = doc.createParagraph();
		XWPFRun run = p.createRun(); run.setText(text == null || text.isBlank() ? "—" : text);
	}
	private static void bullet(XWPFDocument doc, String text) {
		XWPFParagraph p = doc.createParagraph();
		XWPFRun run = p.createRun(); run.setText("• " + text);
	}
	private static void bulletsOrNone(XWPFDocument doc, List<String> items) {
		if (items.isEmpty()) { paragraph(doc, "None."); return; }
		for (String item : items) bullet(doc, item);
	}
}
