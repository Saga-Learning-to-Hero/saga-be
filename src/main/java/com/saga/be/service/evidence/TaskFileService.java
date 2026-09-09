package com.saga.be.service.evidence;

import com.saga.be.config.TaskFileProperties;
import com.saga.be.dto.task.TaskFileResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.jira.TaskFile;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.identity.TeamAuthorization;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("!test")
public class TaskFileService {

	private final TaskRepository tasks;
	private final TaskFileRepository files;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final UserAccountRepository users;
	private final TaskFileProperties properties;
	private final TaskFileStorage storage;

	public TaskFileService(
			TaskRepository tasks,
			TaskFileRepository files,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			UserAccountRepository users,
			TaskFileProperties properties) {
		this.tasks = tasks;
		this.files = files;
		this.teams = teams;
		this.members = members;
		this.users = users;
		this.properties = properties;
		this.storage = new TaskFileStorage(properties.getDirectory());
	}

	@Transactional(readOnly = true)
	public List<TaskFileResponse> list(UUID userId, UUID taskId) {
		Task task = requireTask(taskId);
		requireCanRead(userId, task);
		return files.findByTask_IdOrderByCreatedAtAsc(taskId).stream().map(TaskFileService::toResponse).toList();
	}

	@Transactional
	public TaskFileResponse add(UUID userId, UUID taskId, MultipartFile upload) {
		Task task = requireTask(taskId);
		requireMember(userId, task.getProject().getId());
		UserAccount actor = users.findById(userId).orElseThrow();
		byte[] content = read(upload);
		if (content.length > properties.getMaxBytes()) {
			throw new AcademicException(
					AcademicErrorCode.TASK_FILE_TOO_LARGE, HttpStatus.BAD_REQUEST, "File is too large.");
		}
		if (files.countByTask_IdAndSource(taskId, EvidenceSource.SAGA) >= properties.getMaxFilesPerTask()) {
			throw new AcademicException(
					AcademicErrorCode.TASK_FILE_LIMIT, HttpStatus.CONFLICT, "This task already has the maximum number of files.");
		}
		TaskFileTypes.Accepted accepted = TaskFileTypes.accept(upload.getOriginalFilename(), upload.getContentType(), content);
		String hash = sha256(content);
		if (files.findByTask_IdAndContentHash(taskId, hash).isPresent()) {
			throw duplicate();
		}
		TaskFile row = new TaskFile();
		row.setTask(task);
		row.setOriginalFilename(accepted.filename());
		row.setMimeType(accepted.mimeType());
		row.setSizeBytes(content.length);
		row.setContentHash(hash);
		row.setSource(EvidenceSource.SAGA);
		row.setCreatedBy(actor);
		try {
			row = files.save(row);
			storage.write(task.getId(), row.getId(), content);
			return toResponse(row);
		} catch (DataIntegrityViolationException ex) {
			throw duplicate();
		} catch (IOException ex) {
			if (row.getId() != null) {
				files.delete(row);
			}
			throw storeFailed();
		}
	}

	@Transactional(readOnly = true)
	public StoredFile download(UUID userId, UUID taskId, UUID fileId) {
		Task task = requireTask(taskId);
		requireCanRead(userId, task);
		TaskFile row = requireFile(taskId, fileId);
		try {
			return new StoredFile(row.getOriginalFilename(), row.getMimeType(), storage.read(task.getId(), row.getId()));
		} catch (IOException ex) {
			throw storeFailed();
		}
	}

	@Transactional
	public void delete(UUID userId, UUID taskId, UUID fileId) {
		Task task = requireTask(taskId);
		requireMember(userId, task.getProject().getId());
		TaskFile row = requireFile(taskId, fileId);
		if (row.getSource() == EvidenceSource.JIRA) {
			throw new AcademicException(
					AcademicErrorCode.TASK_EVIDENCE_JIRA_IMMUTABLE,
					HttpStatus.CONFLICT,
					"Jira-synced files cannot be removed in SAGA.");
		}
		files.delete(row);
		try {
			storage.delete(task.getId(), row.getId());
		} catch (IOException ex) {
			throw storeFailed();
		}
	}

	private TaskFile requireFile(UUID taskId, UUID fileId) {
		TaskFile row = files.findById(fileId).orElseThrow(() -> notFound());
		if (!row.getTask().getId().equals(taskId)) {
			throw notFound();
		}
		return row;
	}

	private Task requireTask(UUID taskId) {
		return tasks.findActiveFetchedById(taskId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found."));
	}

	private void requireCanRead(UUID userId, Task task) {
		UserAccount actor = users.findById(userId).orElseThrow();
		if (actor.getAccountRole() == AccountRole.ADMIN) {
			return;
		}
		if (actor.getAccountRole() == AccountRole.LECTURER
				&& task.getProject().getCourse().getInstructor() != null
				&& task.getProject().getCourse().getInstructor().getUserAccount() != null
				&& actor.getId().equals(task.getProject().getCourse().getInstructor().getUserAccount().getId())) {
			return;
		}
		requireMember(userId, task.getProject().getId());
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

	private static byte[] read(MultipartFile upload) {
		if (upload == null || upload.isEmpty()) {
			throw new AcademicException(
					AcademicErrorCode.TASK_FILE_INVALID, HttpStatus.BAD_REQUEST, "File is required.");
		}
		try {
			return upload.getBytes();
		} catch (IOException ex) {
			throw new AcademicException(
					AcademicErrorCode.TASK_FILE_INVALID, HttpStatus.BAD_REQUEST, "File could not be read.");
		}
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static AcademicException duplicate() {
		return new AcademicException(
				AcademicErrorCode.TASK_FILE_DUPLICATE, HttpStatus.CONFLICT, "This file is already attached to the task.");
	}

	private static AcademicException notFound() {
		return new AcademicException(
				AcademicErrorCode.TASK_FILE_NOT_FOUND, HttpStatus.NOT_FOUND, "Task file was not found.");
	}

	private static AcademicException storeFailed() {
		return new AcademicException(
				AcademicErrorCode.TASK_FILE_STORE_FAILED,
				HttpStatus.INTERNAL_SERVER_ERROR,
				"File could not be stored.");
	}

	private static TaskFileResponse toResponse(TaskFile row) {
		return new TaskFileResponse(
				row.getId(),
				row.getTask().getId(),
				row.getOriginalFilename(),
				row.getMimeType(),
				row.getSizeBytes(),
				row.getSource() == null ? EvidenceSource.SAGA.name() : row.getSource().name(),
				row.getCreatedBy() == null ? null : row.getCreatedBy().getId(),
				row.getCreatedAt());
	}

	public record StoredFile(String filename, String mimeType, byte[] content) {}
}
