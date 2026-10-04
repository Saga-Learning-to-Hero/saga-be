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
		assertThat(content).contains("Báo cáo tiến độ SAGA").contains("Tổng quan").contains("Đề xuất");
		assertThat(content).contains("Số task quá hạn: 2").contains("Số task sắp đến hạn: 1");
		assertThat(content).contains("The student has completed most assigned tasks this sprint.");
		assertThat(content).contains("Consistent recent commit activity");
		assertThat(content).contains("2 tasks are overdue and 1 is due soon");
		assertThat(content).contains("Check in about the overdue task");
		assertThat(content).containsPattern("Cần giảng viên xem xét\\s+Có\\n"); // humanReviewRecommended
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

	@Test
	void aClassReportHasTablesPerTeamAndMemberWithNamesOverdueTasksAndAttention() throws Exception {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(UUID.randomUUID());
		run.setArtifactType(AiArtifactType.COURSE);
		run.setArtifactId(UUID.randomUUID());
		run.setCompletedAt(java.time.LocalDateTime.of(2026, 10, 4, 22, 10));
		AiProgressNarrative narrative = new AiProgressNarrative();
		narrative.setAnalysisRun(run);
		narrative.setFactsJson("""
				{"scope":"COURSE","overdueCount":1,"dueSoonCount":0,
				 "course":{"courseCode":"SE1802","courseName":"SWR302-FA26-SE1802","subject":"SWR302 - Software Requirement","semester":"Fall 2026","lecturer":"Nguyễn Ngân"},
				 "totals":{"memberCount":2,"tasksTotal":4,"tasksDone":2,"commits":14,"unlinkedCommits":4,"reviewedCommits":2,"aiWarnedCommits":1},
				 "overdueTasks":[{"team":"Nhóm 2","key":"SAGA-12","title":"Xuất báo cáo","assignee":"Trần Thị B","dueDate":"2026-10-02","overdueDays":3}],
				 "attention":[{"team":"Nhóm 2","member":"Trần Thị B","studentCode":"SE170002","reasons":["1 task quá hạn: SAGA-12","chưa có commit nào"]}],
				 "mostActive":[{"team":"Nhóm 2","member":"Nguyễn Văn A","studentCode":"SE170001","tasksDone":2,"storyPointsDone":8,"commits":12}],
				 "teams":[{"teamName":"Nhóm 2","memberCount":2,"tasksTotal":4,"tasksDone":2,"overdueCount":1,"unassignedOpenTasks":1,"commits":14,
				   "commitsByUnmappedAuthors":2,"unlinkedCommits":4,"aiReviewedCommits":2,"aiWarnedCommits":1,
				   "members":[{"name":"Nguyễn Văn A","studentCode":"SE170001","role":"LEADER","tasksTotal":2,"tasksDone":2,"tasksInProgress":0,
				     "storyPointsDone":8,"storyPointsTotal":8,"overdueTasks":[],"commits":12,"unlinkedCommits":4,"aiWarnedCommits":1,"lastCommitAt":"2026-10-04"}]}]}
				""");
		narrative.setOverview("Lớp hoàn thành 2/4 task.");
		narrative.setDueSoonOverdueNote("Nhóm 2 - SAGA-12 - Trần Thị B - trễ 3 ngày");
		narrative.setHighlightsJson("[]");
		narrative.setConcernsJson("[]");
		narrative.setBlockersJson("[]");
		narrative.setRecommendationsJson("[\"Nhắc Trần Thị B hoàn thành SAGA-12\"]");

		String content = text(renderer.render(run, narrative));

		String expectedTime = CommitAiReviewService.vietnamTime(run.getCompletedAt()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
		assertThat(content).contains("SE1802 - SWR302-FA26-SE1802").contains("Nguyễn Ngân").contains(expectedTime + " (giờ Việt Nam)");
		assertThat(content).contains("Task đang quá hạn").contains("SAGA-12").contains("Xuất báo cáo").contains("Trần Thị B");
		assertThat(content).contains("Sinh viên cần chú ý").contains("1 task quá hạn: SAGA-12; chưa có commit nào");
		assertThat(content).contains("Sinh viên hoạt động nhiều nhất").contains("Nguyễn Văn A").contains("Nhóm trưởng");
		assertThat(content).contains("Nhóm 2 (2 thành viên)").contains("2 commit đến từ tài khoản GitHub chưa liên kết");
		assertThat(content).contains("Nhắc Trần Thị B hoàn thành SAGA-12").contains("không phải điểm đóng góp");
		assertThat(content).doesNotContain("courseId").doesNotContain(run.getArtifactId().toString());
	}

	private static String text(byte[] bytes) throws Exception {
		try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes));
				var extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
			return extractor.getText();
		}
	}
}
