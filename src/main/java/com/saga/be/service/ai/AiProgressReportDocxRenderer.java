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
			title(doc, "Báo cáo tiến độ SAGA");
			paragraph(doc, "Thời điểm tạo: " + (run.getCompletedAt() == null ? "" : run.getCompletedAt().format(TIMESTAMP)) + " (UTC)");
			paragraph(doc, "Phạm vi: " + run.getArtifactType() + "  •  Mã đối tượng: " + run.getArtifactId());
			paragraph(doc, "Mã phân tích: " + run.getId());

			heading(doc, "Số liệu tiến độ (do hệ thống tính)");
			for (Map.Entry<String, Object> entry : facts(narrative.getFactsJson()).entrySet()) bullet(doc, factLabel(entry.getKey()) + ": " + String.valueOf(entry.getValue()));

			heading(doc, "Tổng quan");
			paragraph(doc, narrative.getOverview());

			heading(doc, "Sắp đến hạn / Quá hạn");
			paragraph(doc, narrative.getDueSoonOverdueNote());

			heading(doc, "Điểm nổi bật");
			bulletsOrNone(doc, stringList(narrative.getHighlightsJson()));

			heading(doc, "Vấn đề / Rủi ro");
			bulletsOrNone(doc, stringList(narrative.getConcernsJson()));

			heading(doc, "Trở ngại");
			bulletsOrNone(doc, stringList(narrative.getBlockersJson()));

			heading(doc, "Đề xuất");
			bulletsOrNone(doc, stringList(narrative.getRecommendationsJson()));

			heading(doc, "Cần giảng viên xem xét");
			paragraph(doc, narrative.isHumanReviewRecommended() ? "Có" : "Không");

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

	private static final Map<String, String> FACT_LABELS = Map.ofEntries(
			Map.entry("scope", "Phạm vi"),
			Map.entry("courseId", "Mã lớp học phần"),
			Map.entry("projectId", "Mã dự án"),
			Map.entry("teamId", "Mã nhóm"),
			Map.entry("teamName", "Tên nhóm"),
			Map.entry("studentId", "Mã sinh viên"),
			Map.entry("teamCount", "Số nhóm"),
			Map.entry("memberCount", "Số thành viên"),
			Map.entry("taskStatusCounts", "Số task theo trạng thái"),
			Map.entry("overdueCount", "Số task quá hạn"),
			Map.entry("dueSoonCount", "Số task sắp đến hạn"),
			Map.entry("nonDoneAttentionTaskCount", "Số task chưa xong cần chú ý"),
			Map.entry("riskDistribution", "Phân bố mức rủi ro"),
			Map.entry("taskIntelligenceEvidenceDistribution", "Phân bố mức bằng chứng của task"));

	/** Vietnamese label for a known facts key; an unknown key is shown as-is. */
	static String factLabel(String key) {
		return FACT_LABELS.getOrDefault(key, key);
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
		if (items.isEmpty()) { paragraph(doc, "Không có."); return; }
		for (String item : items) bullet(doc, item);
	}
}
