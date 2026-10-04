package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiCodeVerdict;
import com.saga.be.ai.AiCommitMessageVerdict;
import com.saga.be.ai.AiEvidenceReference;
import com.saga.be.ai.AiEvidenceReferenceKind;
import com.saga.be.ai.AiFinding;
import com.saga.be.ai.AiOverallDecision;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.ai.AiStructuredResult;
import com.saga.be.ai.AiTaskAlignmentVerdict;
import com.saga.be.dto.ai.CommitAiReviewDtos;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiAnalysisEvidence;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.traceability.TaskCommitManualLink;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AiAnalysisEvidenceRepository;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;

class CommitAiReviewServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final UUID projectId = UUID.randomUUID();
	private final UUID courseId = UUID.randomUUID();
	private final UUID leaderId = UUID.randomUUID();
	private final UUID authorId = UUID.randomUUID();
	private final UUID otherMemberId = UUID.randomUUID();

	private ProjectDataAuthorization authorization;
	private GitCommitRepository commits;
	private AiAnalysisRunRepository runs;
	private AiAnalysisProviderDecisionRepository decisions;
	private AiAnalysisEvidenceRepository evidence;
	private TaskGitCommitLinkRepository links;
	private TaskCommitManualLinkRepository manualLinks;
	private TeamAiCredentialService teamKeys;
	private TeamMemberRepository members;
	private AiAnalysisSubmissionService submissions;
	private CommitAiReviewService service;
	private Project project;
	private GitRepo repo;

	@BeforeEach
	void setUp() {
		authorization = mock(ProjectDataAuthorization.class);
		commits = mock(GitCommitRepository.class);
		runs = mock(AiAnalysisRunRepository.class);
		decisions = mock(AiAnalysisProviderDecisionRepository.class);
		evidence = mock(AiAnalysisEvidenceRepository.class);
		links = mock(TaskGitCommitLinkRepository.class);
		manualLinks = mock(TaskCommitManualLinkRepository.class);
		teamKeys = mock(TeamAiCredentialService.class);
		members = mock(TeamMemberRepository.class);
		submissions = mock(AiAnalysisSubmissionService.class);
		service = new CommitAiReviewService(authorization, commits, runs, decisions, evidence, links, manualLinks, teamKeys, members, submissions, mapper);
		com.saga.be.repository.ProjectRepository projects = mock(com.saga.be.repository.ProjectRepository.class);
		when(projects.findCourseIdById(projectId)).thenReturn(Optional.of(courseId));
		service.setProjects(projects);
		Course course = new Course();
		course.setId(courseId);
		project = new Project();
		project.setId(projectId);
		project.setCourse(course);
		repo = new GitRepo();
		repo.setProject(project);
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(new TeamAiCredentialService.TeamKeyRef(UUID.randomUUID(), "fp", new AiProviderBinding(AiProvider.GEMINI, "gemini-3.6-flash"))));
		when(runs.findCommitReviewRuns(eq(projectId), any())).thenReturn(List.of());
		when(links.findLiveWithTaskByGitCommitIds(any())).thenReturn(List.of());
		when(manualLinks.findFetchedByProjectAndCommitIds(eq(projectId), any())).thenReturn(List.of());
		when(members.findActiveRoleByProjectIdAndUserId(projectId, leaderId)).thenReturn(Optional.of(RoleInTeam.LEADER));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, authorId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, otherMemberId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
	}

	// ---------------- badges

	@Test
	void badgesForAPage_mergeNoTaskPassAndPending() {
		GitCommit merge = commit(2);
		GitCommit unlinked = commit(1);
		GitCommit passing = commit(1);
		GitCommit pending = commit(1);
		linkAutomatically(passing, task("SAGA-1"));
		linkAutomatically(pending, task("SAGA-2"));
		AiAnalysisRun passRun = run(passing, AiAnalysisStatus.COMPLETED);
		AiAnalysisRun pendingRun = run(pending, AiAnalysisStatus.RUNNING);
		when(runs.findCommitReviewRuns(eq(projectId), any())).thenReturn(List.of(passRun, pendingRun));
		when(decisions.findByAnalysisRun_IdIn(List.of(passRun.getId()))).thenReturn(List.of(decision(passRun,
				result(AiCommitMessageVerdict.CLEAR, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.ALIGNS, List.of(), List.of()))));

		Map<UUID, CommitAiReviewDtos.Summary> badges = service.summaries(projectId, courseId, List.of(merge, unlinked, passing, pending));

		assertThat(badges.get(merge.getId()).status()).isEqualTo(CommitAiReviewDtos.SKIPPED_MERGE);
		assertThat(badges.get(unlinked.getId()).status()).isEqualTo(CommitAiReviewDtos.NOT_REVIEWED);
		assertThat(badges.get(unlinked.getId()).reasons()).extracting(CommitAiReviewDtos.Reason::code).containsExactly(CommitAiReviewDtos.REASON_NO_TASK);
		assertThat(badges.get(unlinked.getId()).taskLinked()).isFalse();
		assertThat(badges.get(passing.getId()).status()).isEqualTo(CommitAiReviewDtos.PASS);
		assertThat(badges.get(passing.getId()).label()).isEqualTo("Đạt");
		assertThat(badges.get(pending.getId()).status()).isEqualTo(CommitAiReviewDtos.PENDING);
		// one batch of queries for the whole page, never one per commit
		verify(runs, times(1)).findCommitReviewRuns(eq(projectId), anyList());
		verify(links, times(1)).findLiveWithTaskByGitCommitIds(anyList());
	}

	@Test
	void aMergeKnownOnlyByItsMessage_showsNoAiAndNoTaskWarning_evenWithAnOldFailedRun() {
		GitCommit merge = commit(1);
		merge.setParentCount(null);
		merge.setMessage("Merge pull request #70 from Saga-Learning-to-Hero/dev");
		AiAnalysisRun failed = run(merge, AiAnalysisStatus.FAILED);
		when(runs.findCommitReviewRuns(eq(projectId), any())).thenReturn(List.of(failed));

		CommitAiReviewDtos.Summary badge = service.summaries(projectId, courseId, List.of(merge)).get(merge.getId());

		assertThat(badge.status()).isEqualTo(CommitAiReviewDtos.SKIPPED_MERGE);
		assertThat(badge.reasons()).isEmpty();
		CommitAiReviewDtos.Detail detail = service.detail(authorId, projectId, merge.getId());
		assertThat(detail.merge()).isTrue();
		assertThat(detail.canRequestReview()).isFalse();
		assertThat(detail.reviewBlockedReason()).isEqualTo("MERGE");
	}

	@Test
	void onlyTheNewestRunOfACommitCounts() {
		GitCommit commit = commit(1);
		linkAutomatically(commit, task("SAGA-1"));
		AiAnalysisRun newest = run(commit, AiAnalysisStatus.FAILED);
		AiAnalysisRun older = run(commit, AiAnalysisStatus.COMPLETED);
		when(runs.findCommitReviewRuns(eq(projectId), any())).thenReturn(List.of(newest, older));

		assertThat(service.summaries(projectId, courseId, List.of(commit)).get(commit.getId()).status()).isEqualTo(CommitAiReviewDtos.FAILED);
	}

	@Test
	void aHandAttachedTaskCountsAsLinked() {
		GitCommit commit = commit(1);
		TaskCommitManualLink manual = new TaskCommitManualLink();
		manual.setTask(task("SAGA-9"));
		manual.setGitCommit(commit);
		when(manualLinks.findFetchedByProjectAndCommitIds(eq(projectId), any())).thenReturn(List.of(manual));

		CommitAiReviewDtos.Summary badge = service.summaries(projectId, courseId, List.of(commit)).get(commit.getId());

		assertThat(badge.taskLinked()).isTrue();
		assertThat(badge.reasons()).isEmpty();
	}

	@Test
	void noTeamKeyAndNoCourseKey_meansNoKeyBadge() {
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.empty());
		when(teamKeys.courseKeyUsable(projectId, courseId, AiInvocationOrigin.USER_REQUEST)).thenReturn(false);
		GitCommit commit = commit(1);
		assertThat(service.summaries(projectId, courseId, List.of(commit)).get(commit.getId()).status()).isEqualTo(CommitAiReviewDtos.NO_KEY);
		assertThat(service.keySource(projectId, courseId)).isEqualTo("NONE");
		when(teamKeys.courseKeyUsable(projectId, courseId, AiInvocationOrigin.USER_REQUEST)).thenReturn(true);
		assertThat(service.keySource(projectId, courseId)).isEqualTo("COURSE");
	}

	// ---------------- detail panel

	@Test
	void detailExplainsEachProblemWithTheExactDiffLinesAndASuggestedMessage() {
		GitCommit commit = commit(1);
		commit.setMessage("fix");
		Task task = task("SAGA-7");
		linkAutomatically(commit, task);
		AiAnalysisRun run = run(commit, AiAnalysisStatus.COMPLETED);
		run.setCompletedAt(LocalDateTime.of(2026, 10, 4, 21, 0));
		AiAnalysisEvidence message = evidence(run, AiEvidenceType.COMMIT_MESSAGE, "{\"message\":\"fix\"}");
		AiAnalysisEvidence hunk = evidence(run, AiEvidenceType.DIFF_HUNK, "{\"path\":\"src/Login.java\",\"hunkId\":\"H2\",\"patch\":\"@@ -10 +10 @@\\n+String password = \\\"123\\\";\"}");
		AiAnalysisEvidence taskRow = evidence(run, AiEvidenceType.TASK_FIELD, "{\"taskId\":\"" + task.getId() + "\",\"externalKey\":\"SAGA-7\",\"title\":\"Đăng nhập\"}");
		AiAnalysisEvidence manifest = evidence(run, AiEvidenceType.CHANGED_FILE_MANIFEST, "{\"filesTotal\":3,\"filesAnalyzed\":2,\"filesOmitted\":1,\"coverage\":\"PARTIAL\"}");
		when(runs.findCommitReviewRuns(eq(projectId), any())).thenReturn(List.of(run));
		when(evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(run.getId())).thenReturn(List.of(message, hunk, taskRow, manifest));
		AiStructuredResult result = result(AiCommitMessageVerdict.POOR, AiCodeVerdict.CONCERNS, AiTaskAlignmentVerdict.MISMATCH,
				List.of(new AiFinding("VAGUE_MESSAGE", "Tên commit 'fix' không nói sửa gì; hãy ghi SAGA-7 và nội dung sửa.", List.of(ref(message)))),
				List.of(new AiFinding("HARDCODED_SECRET", "src/Login.java H2 gán mật khẩu cứng '123'; chuyển sang biến môi trường.", List.of(ref(hunk), ref(hunk)))));
		result = new AiStructuredResult(result.commitMessageAssessment(), result.codeAssessment(),
				List.of(new AiStructuredResult.TaskAlignment(task.getId(), null, AiTaskAlignmentVerdict.MISMATCH, 0.7, "Commit sửa đăng nhập nhưng task là báo cáo.", List.of(ref(taskRow)))),
				AiTaskAlignmentVerdict.MISMATCH, List.of(), AiOverallDecision.INFORMATIONAL, false);
		AiAnalysisProviderDecision decision = decision(run, result);
		decision.setAiProvider(AiProvider.GEMINI);
		decision.setModelId("gemini-3.6-flash");
		when(decisions.findByAnalysisRun_Id(run.getId())).thenReturn(Optional.of(decision));

		CommitAiReviewDtos.Detail detail = service.detail(authorId, projectId, commit.getId());

		assertThat(detail.status()).isEqualTo(CommitAiReviewDtos.WARNING);
		assertThat(detail.reasons()).extracting(CommitAiReviewDtos.Reason::code)
				.containsExactly(CommitAiReviewDtos.REASON_MESSAGE, CommitAiReviewDtos.REASON_CODE, CommitAiReviewDtos.REASON_TASK_MISMATCH);
		assertThat(detail.headline()).startsWith("Cần xem lại:");
		assertThat(detail.messageReview().verdictLabel()).isEqualTo("Chưa rõ");
		assertThat(detail.messageReview().suggestedMessage()).isEqualTo("SAGA-7 fix: sửa lỗi đăng nhập");
		assertThat(detail.messageReview().findings().getFirst().locations().getFirst().label()).isEqualTo("Tên commit");
		CommitAiReviewDtos.Finding code = detail.codeReview().findings().getFirst();
		assertThat(code.code()).isEqualTo("HARDCODED_SECRET");
		assertThat(code.locations()).singleElement().satisfies(location -> {
			assertThat(location.kind()).isEqualTo("DIFF_HUNK");
			assertThat(location.path()).isEqualTo("src/Login.java");
			assertThat(location.hunkId()).isEqualTo("H2");
			assertThat(location.snippet()).contains("String password");
			assertThat(location.label()).isEqualTo("src/Login.java · H2");
		});
		assertThat(detail.codeReview().coverage().complete()).isFalse();
		assertThat(detail.codeReview().coverage().filesOmitted()).isEqualTo(1);
		assertThat(detail.taskReview().alignments()).singleElement().satisfies(alignment -> {
			assertThat(alignment.externalKey()).isEqualTo("SAGA-7");
			assertThat(alignment.title()).isEqualTo("Đăng nhập");
			assertThat(alignment.verdictLabel()).isEqualTo("Không khớp");
		});
		assertThat(detail.taskReview().linkedTasks()).singleElement().satisfies(linked -> {
			assertThat(linked.source()).isEqualTo("AUTO");
			assertThat(linked.canUnlink()).isFalse();
		});
		assertThat(detail.provider()).isEqualTo("GEMINI");
		assertThat(detail.reviewedAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 21, 0));
		assertThat(detail.canRequestReview()).isTrue();
		assertThat(detail.keySource()).isEqualTo("TEAM");
		assertThat(detail.failure()).isNull();
	}

	@Test
	void aMergeCommitDetailHidesTheReviewButton() {
		GitCommit merge = commit(2);
		CommitAiReviewDtos.Detail detail = service.detail(authorId, projectId, merge.getId());
		assertThat(detail.merge()).isTrue();
		assertThat(detail.status()).isEqualTo(CommitAiReviewDtos.SKIPPED_MERGE);
		assertThat(detail.canRequestReview()).isFalse();
		assertThat(detail.reviewBlockedReason()).isEqualTo("MERGE");
		assertThat(detail.headline()).contains("Merge commit");
	}

	@Test
	void aFailedReviewCarriesTheReadableFailure() {
		GitCommit commit = commit(1);
		AiAnalysisRun run = run(commit, AiAnalysisStatus.FAILED);
		run.setFailureCode("AI_PROVIDER_TIMEOUT");
		when(runs.findCommitReviewRuns(eq(projectId), any())).thenReturn(List.of(run));
		when(evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(run.getId())).thenReturn(List.of());

		CommitAiReviewDtos.Detail detail = service.detail(authorId, projectId, commit.getId());

		assertThat(detail.status()).isEqualTo(CommitAiReviewDtos.FAILED);
		assertThat(detail.failure().title()).isEqualTo("AI phản hồi quá lâu");
		assertThat(detail.failure().retryable()).isTrue();
	}

	@Test
	void whoMayManageLinks_authorAndLeaderYes_otherMembersAndLecturersNo() {
		GitCommit commit = commit(1);
		Task manualTask = task("SAGA-3");
		TaskCommitManualLink manual = new TaskCommitManualLink();
		manual.setTask(manualTask);
		manual.setGitCommit(commit);
		when(manualLinks.findFetchedByProjectAndCommitIds(eq(projectId), any())).thenReturn(List.of(manual));

		assertThat(service.detail(authorId, projectId, commit.getId()).canManageLinks()).isTrue();
		assertThat(service.detail(authorId, projectId, commit.getId()).taskReview().linkedTasks().getFirst().canUnlink()).isTrue();
		assertThat(service.detail(leaderId, projectId, commit.getId()).canManageLinks()).isTrue();
		assertThat(service.detail(otherMemberId, projectId, commit.getId()).canManageLinks()).isFalse();
		assertThat(service.detail(otherMemberId, projectId, commit.getId()).taskReview().linkedTasks().getFirst().canUnlink()).isFalse();
		UUID lecturer = UUID.randomUUID();
		when(members.findActiveRoleByProjectIdAndUserId(projectId, lecturer)).thenReturn(Optional.empty());
		assertThat(service.detail(lecturer, projectId, commit.getId()).canManageLinks()).isFalse();
	}

	@Test
	void aCommitOfAnotherProjectIsNotFound() {
		GitCommit foreign = commit(1);
		Project other = new Project();
		other.setId(UUID.randomUUID());
		GitRepo otherRepo = new GitRepo();
		otherRepo.setProject(other);
		foreign.setRepo(otherRepo);
		assertThatThrownBy(() -> service.detail(authorId, projectId, foreign.getId()))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void detailNeverLoadsTheCourseLazily_soTheReReviewButtonWorksOutsideATransaction() {
		// regression: POST /ai-review returned 500 (LazyInitializationException on project.course)
		Project lazy = org.mockito.Mockito.spy(new Project());
		lazy.setId(projectId);
		org.mockito.Mockito.doThrow(new org.hibernate.LazyInitializationException("no session")).when(lazy).getCourse();
		GitRepo lazyRepo = new GitRepo();
		lazyRepo.setProject(lazy);
		GitCommit commit = commit(1);
		commit.setRepo(lazyRepo);

		CommitAiReviewDtos.Detail detail = service.request(authorId, projectId, commit.getId());

		verify(submissions).submit(authorId, projectId, commit.getId());
		assertThat(detail.keySource()).isEqualTo("TEAM");
	}

	// ---------------- backfill

	@Test
	void backfillReviewsRecentUnreviewedCommits_skippingMergesAndDoneOnes() {
		GitCommit merge = commit(2);
		GitCommit done = commit(1);
		GitCommit failed = commit(1);
		GitCommit fresh = commit(1);
		List<GitCommit> page = List.of(merge, done, failed, fresh);
		when(commits.findPageIdsByProject(eq(projectId), any())).thenReturn(new PageImpl<>(page.stream().map(GitCommit::getId).toList()));
		when(commits.findFetchedByIdIn(anyList())).thenAnswer(inv -> {
			List<UUID> ids = inv.getArgument(0);
			return page.stream().filter(c -> ids.contains(c.getId())).toList();
		});
		when(runs.findCommitReviewRuns(eq(projectId), any())).thenReturn(List.of(run(done, AiAnalysisStatus.COMPLETED), run(failed, AiAnalysisStatus.FAILED)));

		CommitAiReviewDtos.BackfillResult result = service.backfill(leaderId, projectId, 50);

		assertThat(result.queued()).isEqualTo(2);
		assertThat(result.skipped()).isEqualTo(2);
		verify(submissions).submit(leaderId, projectId, failed.getId());
		verify(submissions).submit(leaderId, projectId, fresh.getId());
		verify(submissions, never()).submit(leaderId, projectId, merge.getId());
		verify(submissions, never()).submit(leaderId, projectId, done.getId());
	}

	@Test
	void backfillIsCappedAtFifteen_andOneFailingCommitDoesNotStopTheOthers() {
		List<GitCommit> page = new ArrayList<>();
		for (int i = 0; i < 30; i++) page.add(commit(1));
		when(commits.findPageIdsByProject(eq(projectId), any())).thenReturn(new PageImpl<>(page.stream().map(GitCommit::getId).toList()));
		when(commits.findFetchedByIdIn(anyList())).thenAnswer(inv -> {
			List<UUID> ids = inv.getArgument(0);
			return page.stream().filter(c -> ids.contains(c.getId())).toList();
		});
		when(submissions.submit(leaderId, projectId, page.get(0).getId())).thenThrow(new RuntimeException("github down"));

		CommitAiReviewDtos.BackfillResult result = service.backfill(leaderId, projectId, null);

		assertThat(CommitAiReviewService.MAX_BACKFILL).isEqualTo(15);
		assertThat(result.queued()).isEqualTo(15);
		verify(submissions, times(15)).submit(eq(leaderId), eq(projectId), any());
	}

	@Test
	void backfillAnswersAtOnce_theSlowGitHubReadsAndSubmissionsRunInTheBackground() {
		List<GitCommit> page = List.of(commit(1), commit(1), commit(1));
		when(commits.findPageIdsByProject(eq(projectId), any())).thenReturn(new PageImpl<>(page.stream().map(GitCommit::getId).toList()));
		when(commits.findFetchedByIdIn(anyList())).thenReturn(page);
		List<Runnable> queued = new ArrayList<>();
		service.setBackground(queued::add);

		CommitAiReviewDtos.BackfillResult result = service.backfill(leaderId, projectId, 10);

		assertThat(result.queued()).isEqualTo(3);
		verify(submissions, never()).submit(any(), any(), any());
		queued.getFirst().run();
		verify(submissions, times(3)).submit(eq(leaderId), eq(projectId), any());
	}

	@Test
	void aFullBackgroundQueueReportsTheCommitsAsNotQueued() {
		GitCommit fresh = commit(1);
		when(commits.findPageIdsByProject(eq(projectId), any())).thenReturn(new PageImpl<>(List.of(fresh.getId())));
		when(commits.findFetchedByIdIn(anyList())).thenReturn(List.of(fresh));
		service.setBackground(task -> { throw new java.util.concurrent.RejectedExecutionException("full"); });

		CommitAiReviewDtos.BackfillResult result = service.backfill(leaderId, projectId, 10);

		assertThat(result.queued()).isZero();
		assertThat(result.failed()).isEqualTo(1);
	}

	@Test
	void backfillNeedsATeamKeyOrTheLecturersPermission() {
		GitCommit commit = commit(1);
		when(commits.findPageIdsByProject(eq(projectId), any())).thenReturn(new PageImpl<>(List.of(commit.getId())));
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.empty());
		when(teamKeys.courseFallbackAvailable(projectId, courseId)).thenReturn(false);

		assertThatThrownBy(() -> service.backfill(leaderId, projectId, 5))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.AI_CREDENTIAL_UNAVAILABLE))
				.hasMessageContaining("giảng viên chưa cho dùng key của lớp");
		verify(submissions, never()).submit(any(), any(), any());
	}

	@Test
	void backfillIsForTheLeaderOnly() {
		doThrow(new IntegrationException(IntegrationErrorCode.NOT_TEAM_LEADER, HttpStatus.FORBIDDEN, "leader only"))
				.when(authorization).requireStudentLeader(authorId, projectId);
		assertThatThrownBy(() -> service.backfill(authorId, projectId, 5)).isInstanceOf(IntegrationException.class);
		verify(submissions, never()).submit(any(), any(), any());
	}

	// ---------------- helpers

	private GitCommit commit(int parents) {
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setRepo(repo);
		commit.setShaHash(UUID.randomUUID().toString().replace("-", ""));
		commit.setParentCount(parents);
		commit.setMessage("SAGA-1 feat: login");
		UserAccount account = new UserAccount();
		account.setId(authorId);
		StudentProfile student = new StudentProfile();
		student.setUserAccount(account);
		commit.setAuthorStudent(student);
		when(commits.findFetchedByIdIn(List.of(commit.getId()))).thenReturn(List.of(commit));
		return commit;
	}

	private Task task(String key) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey(key);
		task.setTitle("Task " + key);
		task.setProject(project);
		return task;
	}

	private final List<TaskGitCommitLink> automaticLinks = new ArrayList<>();

	private void linkAutomatically(GitCommit commit, Task task) {
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setTask(task);
		link.setGitCommit(commit);
		automaticLinks.add(link);
		org.mockito.Mockito.doAnswer(inv -> {
			java.util.Collection<UUID> ids = inv.getArgument(0);
			return automaticLinks.stream().filter(l -> ids.contains(l.getGitCommit().getId())).toList();
		}).when(links).findLiveWithTaskByGitCommitIds(any());
	}

	private static AiAnalysisRun run(GitCommit commit, AiAnalysisStatus status) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(UUID.randomUUID());
		run.setArtifactId(commit.getId());
		run.setStatus(status);
		return run;
	}

	private AiAnalysisProviderDecision decision(AiAnalysisRun run, AiStructuredResult result) {
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setAnalysisRun(run);
		try {
			decision.setStructuredResultJson(mapper.writeValueAsString(result));
		} catch (Exception ex) {
			throw new AssertionError(ex);
		}
		return decision;
	}

	private static AiAnalysisEvidence evidence(AiAnalysisRun run, AiEvidenceType type, String payload) {
		AiAnalysisEvidence row = new AiAnalysisEvidence();
		row.setId(UUID.randomUUID());
		row.setAnalysisRun(run);
		row.setEvidenceType(type);
		row.setPayloadJson(payload);
		return row;
	}

	private static AiEvidenceReference ref(AiAnalysisEvidence row) {
		return new AiEvidenceReference(AiEvidenceReferenceKind.DIFF_HUNK, row.getId(), null, null, null, null, null, null, null, null);
	}

	private static AiStructuredResult result(AiCommitMessageVerdict message, AiCodeVerdict code, AiTaskAlignmentVerdict task,
			List<AiFinding> messageFindings, List<AiFinding> codeFindings) {
		return new AiStructuredResult(
				new AiStructuredResult.CommitMessageAssessment(message, 40, "Tên commit quá chung chung.", "SAGA-7 fix: sửa lỗi đăng nhập", messageFindings),
				new AiStructuredResult.CodeAssessment(code, 0.6, codeFindings, List.of()),
				List.of(), task, List.of(), AiOverallDecision.INFORMATIONAL, false);
	}
}
