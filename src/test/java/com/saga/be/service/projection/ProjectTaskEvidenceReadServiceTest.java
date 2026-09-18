package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.dto.project.TaskEvidenceGroupedResponse;
import com.saga.be.dto.project.TaskEvidenceItem;
import com.saga.be.dto.project.TaskEvidencePageResponse;
import com.saga.be.dto.project.TaskEvidenceResponse;
import com.saga.be.dto.project.TaskEvidenceType;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.jira.TaskFile;
import com.saga.be.entity.jira.TaskWebLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ProjectTaskEvidenceReadServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskGitCommitLinkRepository commitLinks;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private TaskFileRepository files;
	@Mock
	private TaskWebLinkRepository webLinks;

	private ProjectTaskEvidenceReadService service;
	private ObjectMapper mapper;
	private UUID userId;
	private UUID projectId;
	private UUID taskId;

	@BeforeEach
	void setUp() {
		service = new ProjectTaskEvidenceReadService(
				new ProjectDataAuthorization(users, members, projects),
				tasks,
				commitLinks,
				commits,
				files,
				webLinks);
		mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		taskId = UUID.randomUUID();
	}

	@Test
	void member_groupedPreview_includesLinkedCommitSagaAndJiraFileAndLink() throws Exception {
		stubStudent();
		stubActiveTask();
		GitCommit commit = linkedCommit();
		when(commitLinks.findLinkedCommitIdsByTaskId(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(commit.getId()), PageRequest.of(0, 20), 1));
		when(commits.findFetchedByIdIn(List.of(commit.getId()))).thenReturn(List.of(commit));
		TaskFile sagaFile = file(EvidenceSource.SAGA, "srs.pdf");
		TaskFile jiraFile = file(EvidenceSource.JIRA, "jira.png");
		when(files.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(jiraFile, sagaFile), PageRequest.of(0, 20), 2));
		TaskWebLink sagaLink = link(EvidenceSource.SAGA, "https://docs.example/spec", "Spec");
		TaskWebLink jiraLink = link(EvidenceSource.JIRA, "https://jira.example/browse/SAGA-1", "Remote");
		when(webLinks.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(jiraLink, sagaLink), PageRequest.of(0, 20), 2));

		TaskEvidenceGroupedResponse response =
				(TaskEvidenceGroupedResponse) service.list(userId, projectId, taskId, null, null, null);

		assertThat(response.taskId()).isEqualTo(taskId);
		assertThat(response.groups().COMMIT().total()).isEqualTo(1);
		assertThat(response.groups().COMMIT().items()).hasSize(1);
		TaskEvidenceItem commitItem = response.groups().COMMIT().items().getFirst();
		assertThat(commitItem.type()).isEqualTo(TaskEvidenceType.COMMIT);
		assertThat(commitItem.id()).isEqualTo(commit.getId());
		assertThat(commitItem.source()).isNull();
		assertThat(commitItem.file()).isNull();
		assertThat(commitItem.webLink()).isNull();
		assertThat(commitItem.commit().gitCommitId()).isEqualTo(commit.getId());
		assertThat(commitItem.commit().sha()).isEqualTo(commit.getShaHash());
		assertThat(commitItem.commit().shortSha()).isEqualTo(commit.getShaHash().substring(0, 7));
		assertThat(commitItem.commit().repositoryFullName()).isEqualTo("org/demo");
		assertThat(response.groups().FILE().total()).isEqualTo(2);
		assertThat(response.groups().FILE().items())
				.extracting(TaskEvidenceItem::source)
				.containsExactly("JIRA", "SAGA");
		assertThat(response.groups().FILE().items())
				.extracting(item -> item.file().downloadPath())
				.containsExactly(
						"/api/tasks/" + taskId + "/files/" + jiraFile.getId(),
						"/api/tasks/" + taskId + "/files/" + sagaFile.getId());
		assertThat(response.groups().WEB_LINK().total()).isEqualTo(2);
		assertThat(response.groups().WEB_LINK().items())
				.extracting(item -> item.webLink().url())
				.containsExactly("https://jira.example/browse/SAGA-1", "https://docs.example/spec");

		String json = mapper.writeValueAsString(response);
		assertThat(json).doesNotContain("data/task-files");
		assertThat(json).doesNotContain("storage");
		assertThat(json).doesNotContain("token");
		assertThat(json).doesNotContain("Bearer");
		assertThat(json).doesNotContain("\"commit\":{}");
		assertThat(json).doesNotContain("\"file\":{}");
		verify(commits, never()).findFetchedByProject_Id(any());
		verify(files, never()).findByTask_IdOrderByCreatedAtAsc(any());
		verify(webLinks, never()).findByTask_IdOrderByCreatedAtAsc(any());
		String typedByInterface = mapper.writerFor(TaskEvidenceResponse.class).writeValueAsString(response);
		assertThat(typedByInterface).contains("\"groups\"");
		assertThat(typedByInterface).contains("\"COMMIT\"");
		assertThat(typedByInterface).doesNotContain("\"page\":");
	}

	@Test
	void lecturer_canReadWebLinksThroughGenericGet() {
		stubLecturerAssigned();
		stubActiveTask();
		when(commitLinks.findLinkedCommitIdsByTaskId(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
		when(files.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
		TaskWebLink link = link(EvidenceSource.SAGA, "https://docs.example/spec", "Spec");
		when(webLinks.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(link), PageRequest.of(0, 20), 1));

		TaskEvidenceGroupedResponse response =
				(TaskEvidenceGroupedResponse) service.list(userId, projectId, taskId, null, 0, 20);

		assertThat(response.groups().WEB_LINK().items()).hasSize(1);
		assertThat(response.groups().WEB_LINK().items().getFirst().webLink().url())
				.isEqualTo("https://docs.example/spec");
	}

	@Test
	void leader_canReadGroupedEvidence() {
		stubStudent();
		stubActiveTask();
		stubEmptyEvidence();
		TaskEvidenceGroupedResponse response =
				(TaskEvidenceGroupedResponse) service.list(userId, projectId, taskId, null, null, 10);
		assertThat(response.groups().COMMIT().items()).isEmpty();
	}

	@Test
	void adminDenied() {
		stubRole(AccountRole.ADMIN);
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, null, null, null))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(tasks, never()).findByIdAndProject_IdAndDeletedAtIsNull(any(), any());
	}

	@Test
	void outsiderDenied() {
		stubRole(AccountRole.STUDENT);
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, null, null, null))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(files, never()).findByTask_Id(any(), any());
	}

	@Test
	void inactiveEnrollmentDeniedLikeOutsider() {
		stubRole(AccountRole.STUDENT);
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, null, null, null))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
	}

	@Test
	void unassignedLecturerDenied() {
		stubRole(AccountRole.LECTURER);
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, null, null, null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
	}

	@Test
	void foreignProjectTaskDoesNotLeak() {
		stubStudent();
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, null, null, null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
		verify(commitLinks, never()).findLinkedCommitIdsByTaskId(any(), any());
		verify(files, never()).findByTask_Id(any(), any());
		verify(webLinks, never()).findByTask_Id(any(), any());
		verify(commits, never()).findFetchedByIdIn(any());
	}

	@Test
	void groupedModeRejectsPageGreaterThanZero() {
		stubStudent();
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, null, 1, 20))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		verify(tasks, never()).findByIdAndProject_IdAndDeletedAtIsNull(any(), any());
	}

	@Test
	void sizeOutOfRangeRejected() {
		stubStudent();
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, "COMMIT", 0, 0))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, "COMMIT", 0, 51))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	@Test
	void unknownTypeRejected() {
		stubStudent();
		assertThatThrownBy(() -> service.list(userId, projectId, taskId, "JIRA_ATTACHMENT", 0, 20))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	@Test
	void typedFileAndWebLinkPaging_doNotQueryOtherTypes() {
		stubStudent();
		stubActiveTask();
		TaskFile sagaFile = file(EvidenceSource.SAGA, "srs.pdf");
		when(files.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(sagaFile), PageRequest.of(0, 20), 1));

		TaskEvidencePageResponse filesPage =
				(TaskEvidencePageResponse) service.list(userId, projectId, taskId, "FILE", 0, 20);

		assertThat(filesPage.type()).isEqualTo(TaskEvidenceType.FILE);
		assertThat(filesPage.items()).hasSize(1);
		assertThat(filesPage.items().getFirst().file()).isNotNull();
		assertThat(filesPage.items().getFirst().commit()).isNull();
		verify(commitLinks, never()).findLinkedCommitIdsByTaskId(any(), any());
		verify(webLinks, never()).findByTask_Id(any(), any());

		TaskWebLink link = link(EvidenceSource.JIRA, "https://jira.example/browse/SAGA-1", "Remote");
		when(webLinks.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(link), PageRequest.of(0, 20), 1));

		TaskEvidencePageResponse linksPage =
				(TaskEvidencePageResponse) service.list(userId, projectId, taskId, "WEB_LINK", 0, 20);

		assertThat(linksPage.type()).isEqualTo(TaskEvidenceType.WEB_LINK);
		assertThat(linksPage.items()).hasSize(1);
		assertThat(linksPage.items().getFirst().webLink().url()).isEqualTo("https://jira.example/browse/SAGA-1");
		verify(commits, never()).findFetchedByIdIn(any());
	}

	@Test
	void evidenceReadServiceSourceHasNoProviderIo() throws Exception {
		String source = java.nio.file.Files.readString(
				java.nio.file.Path.of("src/main/java/com/saga/be/service/projection/ProjectTaskEvidenceReadService.java"));
		assertThat(source).doesNotContain("com.saga.be.integration.github");
		assertThat(source).doesNotContain("com.saga.be.integration.jira");
		assertThat(source).doesNotContain("Firebase");
		assertThat(source).doesNotContain("org.neo4j");
		assertThat(source).doesNotContain("SagaGraph");
	}

	@Test
	void typedCommitPaging_doesNotQueryFilesOrLinks() {
		stubStudent();
		stubActiveTask();
		GitCommit commit = linkedCommit();
		when(commitLinks.findLinkedCommitIdsByTaskId(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(commit.getId()), PageRequest.of(1, 20), 42));
		when(commits.findFetchedByIdIn(List.of(commit.getId()))).thenReturn(List.of(commit));

		TaskEvidencePageResponse response =
				(TaskEvidencePageResponse) service.list(userId, projectId, taskId, "COMMIT", 1, 20);

		assertThat(response.type()).isEqualTo(TaskEvidenceType.COMMIT);
		assertThat(response.page()).isEqualTo(1);
		assertThat(response.size()).isEqualTo(20);
		assertThat(response.total()).isEqualTo(42);
		assertThat(response.items()).hasSize(1);
		verify(files, never()).findByTask_Id(any(), any());
		verify(webLinks, never()).findByTask_Id(any(), any());
	}

	@Test
	void unlinkedCommitIdsAreNotFetchedFromProjectCommitList() {
		stubStudent();
		stubActiveTask();
		when(commitLinks.findLinkedCommitIdsByTaskId(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
		when(files.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
		when(webLinks.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

		TaskEvidenceGroupedResponse response =
				(TaskEvidenceGroupedResponse) service.list(userId, projectId, taskId, "  ", 0, 20);

		assertThat(response.groups().COMMIT().total()).isZero();
		verify(commits, never()).findFetchedByIdIn(any());
		verify(commits, never()).findFetchedByProject_Id(any());
	}

	private void stubEmptyEvidence() {
		when(commitLinks.findLinkedCommitIdsByTaskId(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
		when(files.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
		when(webLinks.findByTask_Id(eq(taskId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
	}

	private void stubStudent() {
		stubRole(AccountRole.STUDENT);
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
	}

	private void stubLecturerAssigned() {
		stubRole(AccountRole.LECTURER);
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
	}

	private void stubRole(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		when(users.findById(userId)).thenReturn(Optional.of(account));
	}

	private void stubActiveTask() {
		Task task = new Task();
		task.setId(taskId);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)).thenReturn(Optional.of(task));
	}

	private GitCommit linkedCommit() {
		GitRepo repo = new GitRepo();
		repo.setFullName("org/demo");
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setShaHash("abcdef1234567890");
		commit.setMessage("fix login");
		commit.setCommittedAt(LocalDateTime.of(2026, 9, 8, 4, 45, 2));
		commit.setRepo(repo);
		return commit;
	}

	private TaskFile file(EvidenceSource source, String name) {
		TaskFile row = new TaskFile();
		row.setId(UUID.randomUUID());
		row.setOriginalFilename(name);
		row.setMimeType("application/pdf");
		row.setSizeBytes(12);
		row.setSource(source);
		row.setCreatedAt(LocalDateTime.of(2026, 9, 8, 5, 0));
		return row;
	}

	private TaskWebLink link(EvidenceSource source, String url, String title) {
		TaskWebLink row = new TaskWebLink();
		row.setId(UUID.randomUUID());
		row.setUrl(url);
		row.setTitle(title);
		row.setSource(source);
		row.setCreatedAt(LocalDateTime.of(2026, 9, 8, 6, 0));
		return row;
	}
}
