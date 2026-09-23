package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.AiRiskAnalysis;
import com.saga.be.entity.ai.AiTaskIntelligence;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.repository.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Deterministic progress facts, local DB only. Every field is either an exact bounded count
 * query or derived from a small bounded task set — no invented metrics, no live GitHub/OpenAI
 * reads. Never includes a raw "now" timestamp in the returned JSON: that value feeds the
 * evidence-hash-based idempotency key, and a continuously-changing timestamp would defeat
 * idempotency for an otherwise-unchanged snapshot (same lesson as {@link AiTaskIntelligenceSnapshotBuilder}).
 */
@Component @Profile("!test")
public class AiProgressFactsBuilder {
	private static final int ATTENTION_TASK_CAP = 20;

	private final ObjectMapper mapper;
	private final TaskRepository tasks;
	private final AiTaskIntelligenceRepository taskIntelligence;
	private final AiRiskAnalysisRepository riskAnalysis;
	private final TeamRepository teams;
	private final TeamMemberRepository teamMembers;
	private final TaskDeadlineProperties deadlineProperties;
	private final Clock clock;

	public AiProgressFactsBuilder(ObjectMapper mapper, TaskRepository tasks, AiTaskIntelligenceRepository taskIntelligence, AiRiskAnalysisRepository riskAnalysis, TeamRepository teams, TeamMemberRepository teamMembers, TaskDeadlineProperties deadlineProperties, Clock clock) {
		this.mapper = mapper; this.tasks = tasks; this.taskIntelligence = taskIntelligence; this.riskAnalysis = riskAnalysis; this.teams = teams; this.teamMembers = teamMembers; this.deadlineProperties = deadlineProperties; this.clock = clock;
	}

	public record Facts(Map<String, Object> data) {
		public String json(ObjectMapper mapper) { try { return mapper.writeValueAsString(data); } catch (Exception e) { throw new IllegalStateException(e); } }
	}

	private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

	public Facts buildStudent(Project project, UUID studentId) {
		LocalDateTime now = now();
		LocalDateTime cutoff = now.plus(deadlineProperties.getDueSoonWindow());
		Map<String, Object> facts = new TreeMap<>();
		facts.put("scope", "STUDENT");
		facts.put("projectId", project.getId());
		facts.put("studentId", studentId);
		facts.put("taskStatusCounts", statusCounts(tasks.countStatusAndStoryPointsForAssignee(project.getId(), studentId)));
		facts.put("overdueCount", tasks.countOverdueForProject(project.getId(), studentId, now));
		facts.put("dueSoonCount", tasks.countDueSoonForProject(project.getId(), studentId, now, cutoff));
		List<Task> attention = tasks.findAttentionNonDoneByProjectAndAssignee(project.getId(), studentId, PageRequest.of(0, ATTENTION_TASK_CAP));
		facts.put("nonDoneAttentionTaskCount", attention.size() >= ATTENTION_TASK_CAP ? (ATTENTION_TASK_CAP + "+") : String.valueOf(attention.size()));
		List<UUID> attentionIds = attention.stream().map(Task::getId).toList();
		facts.put("taskIntelligenceEvidenceDistribution", evidenceStrengthDistribution(attentionIds));
		facts.put("riskDistribution", riskLevelDistribution(attentionIds));
		return new Facts(facts);
	}

	public Facts buildTeam(Project project) {
		LocalDateTime now = now();
		LocalDateTime cutoff = now.plus(deadlineProperties.getDueSoonWindow());
		Team team = teams.findByProject_Id(project.getId()).orElse(null);
		Map<String, Object> facts = new TreeMap<>();
		facts.put("scope", "TEAM");
		facts.put("projectId", project.getId());
		facts.put("teamId", team == null ? null : team.getId());
		facts.put("teamName", team == null ? null : team.getName());
		facts.put("memberCount", team == null ? 0 : teamMembers.findByTeam_Id(team.getId()).size());
		List<Object[]> grouped = tasks.countGroupedByStatusForProjects(List.of(project.getId()));
		Map<String, Long> statusCounts = new TreeMap<>();
		for (Object[] row : grouped) statusCounts.merge(((TaskStatus) row[1]).name(), (Long) row[2], Long::sum);
		facts.put("taskStatusCounts", statusCounts);
		facts.put("overdueCount", tasks.countOverdueForProject(project.getId(), null, now));
		facts.put("dueSoonCount", tasks.countDueSoonForProject(project.getId(), null, now, cutoff));
		return new Facts(facts);
	}

	public Facts buildCourse(Course course, List<Project> projects) {
		LocalDateTime now = now();
		LocalDateTime cutoff = now.plus(deadlineProperties.getDueSoonWindow());
		List<UUID> projectIds = projects.stream().map(Project::getId).toList();
		Map<String, Object> facts = new TreeMap<>();
		facts.put("scope", "COURSE");
		facts.put("courseId", course.getId());
		facts.put("teamCount", projectIds.size());
		if (projectIds.isEmpty()) {
			facts.put("taskStatusCounts", Map.of());
			facts.put("overdueCount", 0L);
			facts.put("dueSoonCount", 0L);
			return new Facts(facts);
		}
		List<Object[]> grouped = tasks.countGroupedByStatusForProjects(projectIds);
		Map<String, Long> statusCounts = new TreeMap<>();
		for (Object[] row : grouped) statusCounts.merge(((TaskStatus) row[1]).name(), (Long) row[2], Long::sum);
		facts.put("taskStatusCounts", statusCounts);
		facts.put("overdueCount", tasks.countOverdueForProjects(projectIds, now));
		facts.put("dueSoonCount", tasks.countDueSoonForProjects(projectIds, now, cutoff));
		return new Facts(facts);
	}

	private Map<String, Long> statusCounts(List<Object[]> rows) {
		Map<String, Long> counts = new TreeMap<>();
		for (Object[] row : rows) counts.merge(((TaskStatus) row[0]).name(), (Long) row[1], Long::sum);
		return counts;
	}

	private Map<String, Long> evidenceStrengthDistribution(List<UUID> taskIds) {
		if (taskIds.isEmpty()) return Map.of();
		Map<UUID, AiTaskIntelligence> latestByTask = new HashMap<>();
		for (AiTaskIntelligence row : taskIntelligence.findLatestByTaskIds(taskIds)) {
			latestByTask.putIfAbsent(row.getTask().getId(), row); // ordered desc: first hit is latest
		}
		Map<String, Long> counts = new TreeMap<>();
		for (AiTaskIntelligence row : latestByTask.values()) if (row.getEvidenceStrength() != null) counts.merge(row.getEvidenceStrength().name(), 1L, Long::sum);
		return counts;
	}

	private Map<String, Long> riskLevelDistribution(List<UUID> taskIds) {
		if (taskIds.isEmpty()) return Map.of();
		Map<UUID, AiRiskAnalysis> latestByTask = new HashMap<>();
		for (AiRiskAnalysis row : riskAnalysis.findLatestByTaskIds(taskIds)) {
			latestByTask.putIfAbsent(row.getAnalysisRun().getArtifactId(), row); // ordered desc: first hit is latest
		}
		Map<String, Long> counts = new TreeMap<>();
		for (AiRiskAnalysis row : latestByTask.values()) if (row.getRiskLevel() != null) counts.merge(row.getRiskLevel().name(), 1L, Long::sum);
		return counts;
	}
}
