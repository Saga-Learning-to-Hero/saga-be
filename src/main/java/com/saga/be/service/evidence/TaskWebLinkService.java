package com.saga.be.service.evidence;

import com.saga.be.dto.task.CreateTaskWebLinkRequest;
import com.saga.be.dto.task.TaskWebLinkResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.jira.TaskWebLink;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.identity.TeamAuthorization;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class TaskWebLinkService {

	private final TaskRepository tasks;
	private final TaskWebLinkRepository links;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final UserAccountRepository users;

	public TaskWebLinkService(
			TaskRepository tasks,
			TaskWebLinkRepository links,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			UserAccountRepository users) {
		this.tasks = tasks;
		this.links = links;
		this.teams = teams;
		this.members = members;
		this.users = users;
	}

	@Transactional(readOnly = true)
	public List<TaskWebLinkResponse> list(UUID userId, UUID taskId) {
		Task task = requireTask(taskId);
		requireMember(userId, task.getProject().getId());
		return links.findByTask_IdOrderByCreatedAtAsc(taskId).stream().map(TaskWebLinkService::toResponse).toList();
	}

	@Transactional
	public TaskWebLinkResponse add(UUID userId, UUID taskId, CreateTaskWebLinkRequest request) {
		Task task = requireTask(taskId);
		requireMember(userId, task.getProject().getId());
		UserAccount actor = users.findById(userId).orElseThrow();
		String url = TaskWebLinkUrls.normalize(request == null ? null : request.url());
		String hash = TaskWebLinkUrls.hash(url);
		if (links.findByTask_IdAndUrlHash(taskId, hash).isPresent()) {
			throw duplicate();
		}
		TaskWebLink row = new TaskWebLink();
		row.setTask(task);
		row.setUrl(url);
		row.setUrlHash(hash);
		row.setTitle(title(request));
		row.setSource(EvidenceSource.SAGA);
		row.setCreatedBy(actor);
		try {
			return toResponse(links.save(row));
		} catch (DataIntegrityViolationException ex) {
			throw duplicate();
		}
	}

	@Transactional
	public void delete(UUID userId, UUID taskId, UUID linkId) {
		Task task = requireTask(taskId);
		requireMember(userId, task.getProject().getId());
		TaskWebLink row = links.findById(linkId).orElseThrow(() -> new AcademicException(
				AcademicErrorCode.TASK_WEB_LINK_NOT_FOUND, HttpStatus.NOT_FOUND, "Task link was not found."));
		if (!row.getTask().getId().equals(taskId)) {
			throw new AcademicException(
					AcademicErrorCode.TASK_WEB_LINK_NOT_FOUND, HttpStatus.NOT_FOUND, "Task link was not found.");
		}
		if (row.getSource() == EvidenceSource.JIRA) {
			throw new AcademicException(
					AcademicErrorCode.TASK_EVIDENCE_JIRA_IMMUTABLE,
					HttpStatus.CONFLICT,
					"Jira-synced links cannot be removed in SAGA.");
		}
		links.delete(row);
	}

	private Task requireTask(UUID taskId) {
		return tasks.findActiveFetchedById(taskId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found."));
	}

	private void requireMember(UUID userId, UUID projectId) {
		Team team = teams.findByProject_Id(projectId).orElse(null);
		boolean member = team != null
				&& members.findByTeam_Id(team.getId()).stream()
						.anyMatch(item ->
								item.getCourseEnrollment().getStudentProfile().getUserAccount().getId().equals(userId));
		TeamAuthorization.requireMember(
				member
						? new TeamAuthorization.Membership(
								team.getId(), projectId, team.getCourse().getId(), null, userId)
						: null);
	}

	private static String title(CreateTaskWebLinkRequest request) {
		if (request == null || request.title() == null || request.title().isBlank()) {
			return null;
		}
		return request.title().trim();
	}

	private static AcademicException duplicate() {
		return new AcademicException(
				AcademicErrorCode.TASK_WEB_LINK_DUPLICATE, HttpStatus.CONFLICT, "This URL is already linked to the task.");
	}

	private static TaskWebLinkResponse toResponse(TaskWebLink row) {
		return new TaskWebLinkResponse(
				row.getId(),
				row.getTask().getId(),
				row.getUrl(),
				row.getTitle(),
				row.getSource() == null ? EvidenceSource.SAGA.name() : row.getSource().name(),
				row.getCreatedBy() == null ? null : row.getCreatedBy().getId(),
				row.getCreatedAt());
	}
}
