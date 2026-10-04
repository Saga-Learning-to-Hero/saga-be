package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.ai.AiProgressNarrative;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
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
	private final ObjectMapper mapper;

	public AiProgressReportDocxRenderer(ObjectMapper mapper) { this.mapper = mapper; }

	public byte[] render(AiAnalysisRun run, AiProgressNarrative narrative) {
		try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Map<String, Object> facts = facts(narrative.getFactsJson());
			boolean detailed = facts.get("teams") instanceof List<?> list && !list.isEmpty();
			title(doc, "Báo cáo tiến độ SAGA");
			if (detailed) {
				header(doc, run, facts);
				detailedFacts(doc, facts);
				heading(doc, "Nhận định của AI");
			} else {
				paragraph(doc, "Thời điểm tạo: " + vietnamTime(run) + " (giờ Việt Nam)");
				paragraph(doc, "Phạm vi: " + run.getArtifactType() + "  •  Mã đối tượng: " + run.getArtifactId());
				paragraph(doc, "Mã phân tích: " + run.getId());
				heading(doc, "Số liệu tiến độ (do hệ thống tính)");
				for (Map.Entry<String, Object> entry : facts.entrySet()) bullet(doc, factLabel(entry.getKey()) + ": " + String.valueOf(entry.getValue()));
			}

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

			if (detailed) {
				note(doc, "Bảng số liệu do SAGA tính trực tiếp từ dữ liệu Jira và GitHub đã đồng bộ. Phần nhận định do AI viết dựa trên đúng số liệu này. "
						+ "\"Hoạt động nhiều nhất\" xếp theo story point và task hoàn thành, sau đó số commit; đây không phải điểm đóng góp.");
			}

			doc.write(out);
			return out.toByteArray();
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to render progress report DOCX", ex);
		}
	}

	// ------------------------------------------------------------------ detailed (team / course) report

	private static final DateTimeFormatter VIETNAM_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

	private static String vietnamTime(AiAnalysisRun run) {
		return run.getCompletedAt() == null ? "" : CommitAiReviewService.vietnamTime(run.getCompletedAt()).format(VIETNAM_TIME);
	}

	private void header(XWPFDocument doc, AiAnalysisRun run, Map<String, Object> facts) {
		Map<String, Object> course = map(facts.get("course"));
		List<List<String>> rows = new ArrayList<>();
		addRow(rows, "Lớp", join(" - ", text(course.get("courseCode")), text(course.get("courseName"))));
		addRow(rows, "Môn học", text(course.get("subject")));
		addRow(rows, "Học kỳ", text(course.get("semester")));
		addRow(rows, "Giảng viên", text(course.get("lecturer")));
		if ("TEAM".equals(facts.get("scope"))) addRow(rows, "Nhóm", text(facts.get("teamName")));
		addRow(rows, "Phạm vi", "TEAM".equals(facts.get("scope")) ? "Một nhóm" : "Toàn lớp");
		addRow(rows, "Thời điểm tạo", vietnamTime(run) + " (giờ Việt Nam)");
		table(doc, List.of("Thông tin", "Giá trị"), rows);
	}

	private void detailedFacts(XWPFDocument doc, Map<String, Object> facts) {
		Map<String, Object> totals = map(facts.get("totals"));
		List<Map<String, Object>> teams = maps(facts.get("teams"));

		heading(doc, "1. Tổng quan số liệu");
		List<List<String>> summary = new ArrayList<>();
		addRow(summary, "Số nhóm", String.valueOf(teams.size()));
		addRow(summary, "Số sinh viên đang học", text(totals.get("memberCount")));
		addRow(summary, "Task hoàn thành / tổng", text(totals.get("tasksDone")) + " / " + text(totals.get("tasksTotal")));
		addRow(summary, "Task chưa hoàn thành", String.valueOf(number(totals.get("tasksTotal")) - number(totals.get("tasksDone"))));
		addRow(summary, "Task quá hạn", text(facts.get("overdueCount")));
		addRow(summary, "Task sắp đến hạn", text(facts.get("dueSoonCount")));
		addRow(summary, "Commit (không tính merge)", text(totals.get("commits")));
		addRow(summary, "Commit chưa gắn task", text(totals.get("unlinkedCommits")));
		addRow(summary, "Commit AI đã đánh giá / bị cảnh báo", text(totals.get("reviewedCommits")) + " / " + text(totals.get("aiWarnedCommits")));
		table(doc, List.of("Chỉ số", "Giá trị"), summary);

		heading(doc, "2. Tình hình từng nhóm");
		List<List<String>> teamRows = new ArrayList<>();
		for (Map<String, Object> team : teams) {
			teamRows.add(List.of(text(team.get("teamName")), text(team.get("memberCount")),
					text(team.get("tasksDone")) + "/" + text(team.get("tasksTotal")), text(team.get("overdueCount")),
					text(team.get("unassignedOpenTasks")), text(team.get("commits")), text(team.get("unlinkedCommits")),
					text(team.get("aiWarnedCommits")) + "/" + text(team.get("aiReviewedCommits"))));
		}
		table(doc, List.of("Nhóm", "Thành viên", "Task xong/tổng", "Quá hạn", "Chưa giao", "Commit", "Chưa gắn task", "AI cảnh báo/đã đánh giá"), teamRows);

		heading(doc, "3. Task đang quá hạn");
		List<List<String>> overdueRows = new ArrayList<>();
		for (Map<String, Object> task : maps(facts.get("overdueTasks"))) {
			overdueRows.add(List.of(text(task.get("team")), text(task.get("key")), text(task.get("title")), text(task.get("assignee")),
					text(task.get("dueDate")), text(task.get("overdueDays"))));
		}
		if (overdueRows.isEmpty()) paragraph(doc, "Không có task nào quá hạn.");
		else table(doc, List.of("Nhóm", "Task", "Tên task", "Người phụ trách", "Hạn", "Trễ (ngày)"), overdueRows);
		if (Boolean.TRUE.equals(facts.get("overdueTaskListTruncated"))) note(doc, "Danh sách chỉ hiện các task trễ lâu nhất.");

		heading(doc, "4. Sinh viên cần chú ý");
		List<List<String>> attentionRows = new ArrayList<>();
		for (Map<String, Object> flag : maps(facts.get("attention"))) {
			attentionRows.add(List.of(text(flag.get("team")), text(flag.get("member")), text(flag.get("studentCode")),
					String.join("; ", strings(flag.get("reasons")))));
		}
		if (attentionRows.isEmpty()) paragraph(doc, "Không có sinh viên nào cần chú ý theo các tiêu chí của hệ thống.");
		else table(doc, List.of("Nhóm", "Sinh viên", "MSSV", "Lý do"), attentionRows);

		heading(doc, "5. Sinh viên hoạt động nhiều nhất");
		List<List<String>> activeRows = new ArrayList<>();
		for (Map<String, Object> row : maps(facts.get("mostActive"))) {
			activeRows.add(List.of(text(row.get("team")), text(row.get("member")), text(row.get("studentCode")),
					text(row.get("tasksDone")), text(row.get("storyPointsDone")), text(row.get("commits"))));
		}
		if (activeRows.isEmpty()) paragraph(doc, "Chưa có sinh viên nào hoàn thành task hoặc có commit.");
		else table(doc, List.of("Nhóm", "Sinh viên", "MSSV", "Task xong", "Story point xong", "Commit"), activeRows);

		heading(doc, "6. Chi tiết từng thành viên");
		for (Map<String, Object> team : teams) {
			subheading(doc, text(team.get("teamName")) + " (" + text(team.get("memberCount")) + " thành viên)");
			List<List<String>> memberRows = new ArrayList<>();
			for (Map<String, Object> member : maps(team.get("members"))) {
				List<String> overdue = strings(member.get("overdueTasks"));
				memberRows.add(List.of(text(member.get("name")), text(member.get("studentCode")), roleLabel(member.get("role")),
						text(member.get("tasksDone")) + "/" + text(member.get("tasksTotal")), text(member.get("tasksInProgress")),
						text(member.get("storyPointsDone")) + "/" + text(member.get("storyPointsTotal")),
						overdue.isEmpty() ? "0" : String.join(", ", overdue),
						text(member.get("commits")), text(member.get("unlinkedCommits")), text(member.get("aiWarnedCommits")),
						text(member.get("lastCommitAt"))));
			}
			if (memberRows.isEmpty()) paragraph(doc, "Nhóm chưa có thành viên đang học.");
			else table(doc, List.of("Thành viên", "MSSV", "Vai trò", "Task xong/tổng", "Đang làm", "Story point", "Task quá hạn", "Commit", "Chưa gắn task", "AI cảnh báo", "Commit gần nhất"), memberRows);
			if (number(team.get("commitsByUnmappedAuthors")) > 0) {
				note(doc, text(team.get("commitsByUnmappedAuthors")) + " commit đến từ tài khoản GitHub chưa liên kết với sinh viên nào.");
			}
		}
	}

	private static String roleLabel(Object role) {
		return "LEADER".equals(role) ? "Nhóm trưởng" : "MEMBER".equals(role) ? "Thành viên" : text(role);
	}

	private static void addRow(List<List<String>> rows, String label, String value) {
		rows.add(List.of(label, value == null || value.isBlank() ? "—" : value));
	}

	private static String join(String separator, String left, String right) {
		boolean hasLeft = left != null && !left.isBlank() && !"—".equals(left);
		boolean hasRight = right != null && !right.isBlank() && !"—".equals(right);
		return hasLeft && hasRight ? left + separator + right : hasLeft ? left : hasRight ? right : "—";
	}

	private static String text(Object value) {
		return value == null || value.toString().isBlank() ? "—" : value.toString();
	}

	private static long number(Object value) {
		return value instanceof Number n ? n.longValue() : 0L;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	private static List<Map<String, Object>> maps(Object value) {
		if (!(value instanceof List<?> list)) return List.of();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object item : list) if (item instanceof Map<?, ?>) out.add(map(item));
		return out;
	}

	private static List<String> strings(Object value) {
		if (!(value instanceof List<?> list)) return List.of();
		return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
	}

	private static void table(XWPFDocument doc, List<String> headers, List<List<String>> rows) {
		XWPFTable table = doc.createTable(rows.size() + 1, headers.size());
		table.setWidth("100%");
		for (int c = 0; c < headers.size(); c++) cell(table.getRow(0).getCell(c), headers.get(c), true);
		for (int r = 0; r < rows.size(); r++) {
			List<String> row = rows.get(r);
			for (int c = 0; c < headers.size(); c++) cell(table.getRow(r + 1).getCell(c), c < row.size() ? row.get(c) : "", false);
		}
		doc.createParagraph();
	}

	private static void cell(XWPFTableCell cell, String text, boolean bold) {
		XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().getFirst();
		XWPFRun run = p.createRun();
		run.setText(text == null ? "" : text);
		run.setBold(bold);
		run.setFontSize(9);
		if (bold) cell.setColor("E3F2EC");
	}

	private static void subheading(XWPFDocument doc, String text) {
		XWPFParagraph p = doc.createParagraph();
		XWPFRun run = p.createRun(); run.setText(text); run.setBold(true); run.setFontSize(12);
	}

	private static void note(XWPFDocument doc, String text) {
		XWPFParagraph p = doc.createParagraph();
		XWPFRun run = p.createRun(); run.setText(text); run.setItalic(true); run.setFontSize(9);
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
