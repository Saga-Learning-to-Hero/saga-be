package com.saga.be.graph;

import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.assessment.PeerReview;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.jira.TaskAttachment;
import com.saga.be.entity.jira.TaskFile;
import com.saga.be.entity.jira.TaskWebLink;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.graph.ProjectGraphSnapshot.CommitNode;
import com.saga.be.graph.ProjectGraphSnapshot.ReviewEdge;
import com.saga.be.graph.ProjectGraphSnapshot.SprintNode;
import com.saga.be.graph.ProjectGraphSnapshot.StudentNode;
import com.saga.be.graph.ProjectGraphSnapshot.TaskCommitLink;
import com.saga.be.graph.ProjectGraphSnapshot.TaskHierarchyLink;
import com.saga.be.graph.ProjectGraphSnapshot.TaskNode;
import com.saga.be.graph.ProjectGraphSnapshot.TeamNode;
import com.saga.be.graph.SagaGraphRules.TaskGraphAttrs;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.contribution.TaskLabelParser;
import com.saga.be.service.projection.JiraParentResolution;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
public class ProjectGraphLoader {

	private final ProjectRepository projects;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final SprintRepository sprints;
	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository links;
	private final TaskAttachmentRepository attachments;
	private final TaskWebLinkRepository webLinks;
	private final TaskFileRepository files;
	private final PeerReviewRepository peerReviews;

	public ProjectGraphLoader(
			ProjectRepository projects,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			SprintRepository sprints,
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			TaskAttachmentRepository attachments,
			TaskWebLinkRepository webLinks,
			TaskFileRepository files,
			PeerReviewRepository peerReviews) {
		this.projects = projects;
		this.teams = teams;
		this.members = members;
		this.sprints = sprints;
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
		this.attachments = attachments;
		this.webLinks = webLinks;
		this.files = files;
		this.peerReviews = peerReviews;
	}

	@Transactional(readOnly = true)
	public ProjectGraphSnapshot load(UUID projectId) {
		Project project = projects
				.findFetchedById(projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Project was not found."));
		Team team = teams.findByProject_Id(projectId).orElse(null);
		List<StudentNode> students = new ArrayList<>();
		List<UUID> studentIds = new ArrayList<>();
		if (team != null) {
			for (TeamMember member : members.findFetchedByTeam_Id(team.getId())) {
				if (member.getCourseEnrollment().getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
					continue;
				}
				StudentProfile profile = member.getCourseEnrollment().getStudentProfile();
				UserAccount account = profile.getUserAccount();
				students.add(new StudentNode(
						profile.getId(),
						account.getFullName() == null ? profile.getStudentCode() : account.getFullName(),
						profile.getStudentCode(),
						account.getAvatarUrl(),
						member.getRoleInTeam() == null ? null : member.getRoleInTeam().name()));
				studentIds.add(profile.getId());
			}
		}
		List<SprintNode> sprintNodes = sprints.findActiveByProject_Id(projectId).stream()
				.map(sprint -> new SprintNode(sprint.getId(), sprint.getName(), sprint.getState()))
				.toList();
		List<Task> projectTasks = tasks.findActiveFetchedByProject_Id(projectId);
		Set<UUID> taskIds = new HashSet<>();
		for (Task task : projectTasks) {
			taskIds.add(task.getId());
		}
		Set<UUID> evidenced = new HashSet<>();
		if (!taskIds.isEmpty()) {
			for (TaskAttachment attachment : attachments.findByTask_IdIn(taskIds)) {
				evidenced.add(attachment.getTask().getId());
			}
			for (TaskWebLink link : webLinks.findByTask_IdIn(taskIds)) {
				evidenced.add(link.getTask().getId());
			}
			for (TaskFile file : files.findByTask_IdIn(taskIds)) {
				evidenced.add(file.getTask().getId());
			}
		}
		Map<UUID, Integer> commitCounts = new HashMap<>();
		List<TaskCommitLink> linkNodes = new ArrayList<>();
		if (!taskIds.isEmpty()) {
			for (TaskGitCommitLink link : links.findFetchedByProject_Id(projectId)) {
				UUID taskId = link.getTask().getId();
				UUID commitId = link.getGitCommit().getId();
				linkNodes.add(new TaskCommitLink(taskId, commitId));
				commitCounts.merge(taskId, 1, Integer::sum);
			}
		}
		Map<String, UUID> bySourceAndJiraId = jiraIdIndex(projectTasks);
		Set<String> parentsOfSubtasks = parentsOfSubtasks(projectTasks);
		Map<String, String> labelsByKey = labelsByKey(projectTasks);
		List<TaskNode> taskNodes = new ArrayList<>();
		for (Task task : projectTasks) {
			JiraParentResolution.Result parent = resolveParent(task, bySourceAndJiraId);
			int linked = commitCounts.getOrDefault(task.getId(), 0);
			boolean subtask = "SUBTASK".equals(task.getIssueTypeLevel());
			String ownKey = task.getJiraIntegration() == null || task.getExternalId() == null
					? null
					: JiraParentResolution.key(task.getJiraIntegration().getId(), task.getExternalId());
			boolean proofOnSubtasks = ownKey != null && parentsOfSubtasks.contains(ownKey);
			String labelsJson = task.getLabelsJson();
			if (subtask && task.getJiraIntegration() != null && task.getParentExternalId() != null) {
				labelsJson = labelsByKey.getOrDefault(
						JiraParentResolution.key(task.getJiraIntegration().getId(), task.getParentExternalId()),
						labelsJson);
			}
			TaskGraphAttrs attrs = SagaGraphRules.classify(
					task.getStatus(),
					TaskLabelParser.parse(labelsJson),
					evidenced.contains(task.getId()),
					linked,
					proofOnSubtasks);
			taskNodes.add(new TaskNode(
					task.getId(),
					task.getSprint() == null ? null : task.getSprint().getId(),
					task.getAssigneeStudent() == null ? null : task.getAssigneeStudent().getId(),
					task.getExternalKey(),
					task.getTitle(),
					task.getStatus() == null ? null : task.getStatus().name(),
					task.getStoryPoint(),
					attrs.weightType(),
					attrs.classified(),
					attrs.anomaly(),
					linked,
					task.getTaskType() == null ? null : task.getTaskType().name(),
					task.getIssueTypeName(),
					task.getIssueTypeId(),
					com.saga.be.service.projection.TaskIssueTypePolicy.apiValue(task.getIssueTypeLevel()),
					task.getJiraHierarchyLevel(),
					task.getJiraIntegration() == null ? null : task.getJiraIntegration().getId(),
					task.getParentExternalId(),
					task.getParentExternalKey(),
					parent == null ? null : parent.resolution(),
					parent == null ? null : parent.reason(),
					!JiraParentResolution.hasParent(task)));
		}
		List<CommitNode> commitNodes = new ArrayList<>();
		for (GitCommit commit : commits.findFetchedByProject_Id(projectId)) {
			UUID authorStudentId = commit.getAuthorStudent() == null ? null : commit.getAuthorStudent().getId();
			String subject = commit.getAuthorExternalId();
			if (subject == null || subject.isBlank()) {
				subject = "unknown:" + commit.getShaHash();
			}
			commitNodes.add(new CommitNode(
					commit.getId(),
					commit.getShaHash(),
					commit.getMessage(),
					subject,
					authorStudentId,
					authorStudentId == null));
		}
		List<ReviewEdge> reviews = new ArrayList<>();
		if (!studentIds.isEmpty()) {
			for (PeerReview review : peerReviews.findFetchedByProjectAndReviewees(projectId, studentIds)) {
				if (review.getStarRating() == null || review.getSprint() == null) {
					continue;
				}
				reviews.add(new ReviewEdge(
						review.getReviewerStudent().getId(),
						review.getRevieweeStudent().getId(),
						review.getSprint().getId(),
						review.getStarRating()));
			}
		}
		return new ProjectGraphSnapshot(
				project.getId(),
				project.getName(),
				team == null ? null : new TeamNode(team.getId(), team.getName()),
				students,
				sprintNodes,
				taskNodes,
				commitNodes,
				linkNodes,
				reviews,
				hierarchyLinks(projectTasks));
	}

	/**
	 * One PARENT_OF link per task whose Jira parent is another task of the same project and Jira
	 * source (resolved by source + Jira id, never by key). An unresolved parent yields no link and
	 * no HAS_WORK_ITEM either -- the node carries parentResolution=UNRESOLVED instead.
	 */
	static List<TaskHierarchyLink> hierarchyLinks(List<Task> projectTasks) {
		Map<String, UUID> index = jiraIdIndex(projectTasks);
		List<TaskHierarchyLink> out = new ArrayList<>();
		for (Task task : projectTasks) {
			JiraParentResolution.Result parent = resolveParent(task, index);
			if (parent != null && parent.parentTaskId() != null) {
				out.add(new TaskHierarchyLink(parent.parentTaskId(), task.getId()));
			}
		}
		return out;
	}

	/** Jira keys of issues that have at least one Subtask on the same source. */
	private static Set<String> parentsOfSubtasks(List<Task> projectTasks) {
		Set<String> keys = new HashSet<>();
		for (Task task : projectTasks) {
			if (!"SUBTASK".equals(task.getIssueTypeLevel())
					|| task.getJiraIntegration() == null
					|| task.getParentExternalId() == null
					|| task.getParentExternalId().isBlank()) {
				continue;
			}
			keys.add(JiraParentResolution.key(task.getJiraIntegration().getId(), task.getParentExternalId()));
		}
		return keys;
	}

	private static Map<String, String> labelsByKey(List<Task> projectTasks) {
		Map<String, String> labels = new HashMap<>();
		for (Task task : projectTasks) {
			if (task.getJiraIntegration() != null && task.getExternalId() != null && !task.getExternalId().isBlank()) {
				labels.put(
						JiraParentResolution.key(task.getJiraIntegration().getId(), task.getExternalId()),
						task.getLabelsJson());
			}
		}
		return labels;
	}

	private static Map<String, UUID> jiraIdIndex(List<Task> projectTasks) {
		Map<String, UUID> index = new HashMap<>();
		for (Task task : projectTasks) {
			if (task.getJiraIntegration() != null && task.getExternalId() != null && !task.getExternalId().isBlank()) {
				index.put(JiraParentResolution.key(task.getJiraIntegration().getId(), task.getExternalId()), task.getId());
			}
		}
		return index;
	}

	/** Null for a top-level item. */
	static JiraParentResolution.Result resolveParent(Task task, Map<String, UUID> index) {
		if (!JiraParentResolution.hasParent(task)) {
			return null;
		}
		UUID found = task.getJiraIntegration() == null
				? null
				: index.get(JiraParentResolution.key(task.getJiraIntegration().getId(), task.getParentExternalId()));
		return JiraParentResolution.of(task, found);
	}
}
