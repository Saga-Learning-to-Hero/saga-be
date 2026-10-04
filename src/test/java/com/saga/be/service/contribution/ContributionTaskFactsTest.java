package com.saga.be.service.contribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.ContributionCriterion;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.service.contribution.SprintFirstContributionMixer.TaskFact;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContributionTaskFactsTest {

	private static final UUID INTEGRATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SPRINT = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID PARENT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
	private static final UUID CHILD_A = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");
	private static final UUID CHILD_B = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc3");
	private static final UUID STUDENT_A = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd4");
	private static final UUID STUDENT_B = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee5");
	private static final UUID LEADER = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff6");

	@Test
	void subtaskSharesArePercentsOfTheParentAndTheParentAssigneeGetsNothing() {
		Task parent = standard(PARENT, "P-1", 10, LEADER, "[\"saga:code\"]", TaskStatus.DONE);
		Task a = subtask(CHILD_A, "P-1", 6, STUDENT_A, "[\"saga:code\"]", TaskStatus.DONE);
		Task b = subtask(CHILD_B, "P-1", 4, STUDENT_B, "[\"saga:test\"]", TaskStatus.DONE);

		List<TaskFact> facts = ContributionTaskFacts.from(List.of(parent, a, b), Set.of(), Set.of(CHILD_A, CHILD_B));

		assertThat(facts).hasSize(2);
		assertThat(facts).noneMatch(fact -> fact.assigneeStudentId().equals(LEADER));
		assertThat(points(facts, STUDENT_A)).isEqualByComparingTo("6");
		assertThat(points(facts, STUDENT_B)).isEqualByComparingTo("4");
		assertThat(facts).allMatch(fact -> SPRINT.equals(fact.sprintId()));
		assertThat(criterion(facts, STUDENT_A)).isEqualTo(ContributionCriterion.CODE);
		assertThat(criterion(facts, STUDENT_B)).isEqualTo(ContributionCriterion.TEST);
		assertThat(facts).allMatch(fact -> fact.status() == TaskStatus.DONE);
	}

	@Test
	void unfinishedSubtaskAndUnfinishedParentAwardNothing() {
		Task parent = standard(PARENT, "P-1", 10, LEADER, "[\"saga:code\"]", TaskStatus.DONE);
		Task a = subtask(CHILD_A, "P-1", 6, STUDENT_A, "[\"saga:code\"]", TaskStatus.DONE);
		Task b = subtask(CHILD_B, "P-1", 4, STUDENT_B, "[\"saga:code\"]", TaskStatus.IN_PROGRESS);

		List<TaskFact> openChild = ContributionTaskFacts.from(List.of(parent, a, b), Set.of(), Set.of(CHILD_A, CHILD_B));
		assertThat(openChild).filteredOn(fact -> fact.assigneeStudentId().equals(STUDENT_B))
				.allMatch(fact -> fact.status() != TaskStatus.DONE);
		assertThat(points(openChild, STUDENT_A)).isEqualByComparingTo("6");

		parent.setStatus(TaskStatus.IN_PROGRESS);
		List<TaskFact> openParent = ContributionTaskFacts.from(List.of(parent, a, b), Set.of(), Set.of(CHILD_A, CHILD_B));
		assertThat(openParent).allMatch(fact -> fact.status() != TaskStatus.DONE);
	}

	@Test
	void standardWithoutSubtasksKeepsItsOwnStoryPointsAndDocumentGate() {
		Task alone = standard(PARENT, "P-1", null, LEADER, "[\"saga:document\"]", TaskStatus.DONE);
		List<TaskFact> missing = ContributionTaskFacts.from(List.of(alone), Set.of(), Set.of());
		assertThat(missing).singleElement().satisfies(fact -> {
			assertThat(fact.assigneeStudentId()).isEqualTo(LEADER);
			assertThat(fact.storyPoint()).isNull();
			assertThat(fact.criterion()).isNull();
		});

		List<TaskFact> proved = ContributionTaskFacts.from(List.of(alone), Set.of(PARENT), Set.of());
		assertThat(proved).singleElement().satisfies(fact -> assertThat(fact.criterion()).isEqualTo(ContributionCriterion.DOCUMENT));
	}

	@Test
	void documentEvidenceIsRequiredOnTheSubtaskNotTheParent() {
		Task parent = standard(PARENT, "P-1", 10, LEADER, "[\"saga:code\"]", TaskStatus.DONE);
		Task a = subtask(CHILD_A, "P-1", 6, STUDENT_A, "[\"saga:document\"]", TaskStatus.DONE);

		List<TaskFact> missing = ContributionTaskFacts.from(List.of(parent, a), Set.of(PARENT), Set.of());
		assertThat(missing).singleElement().satisfies(fact -> assertThat(fact.criterion()).isNull());

		List<TaskFact> proved = ContributionTaskFacts.from(List.of(parent, a), Set.of(CHILD_A), Set.of());
		assertThat(proved).singleElement().satisfies(fact -> {
			assertThat(fact.criterion()).isEqualTo(ContributionCriterion.DOCUMENT);
			assertThat(fact.storyPoint()).isEqualByComparingTo("6");
		});
	}

	@Test
	void epicAwardsNothing() {
		Task epic = standard(PARENT, "E-1", 10, LEADER, "[\"saga:code\"]", TaskStatus.DONE);
		epic.setIssueTypeLevel("EPIC");
		assertThat(ContributionTaskFacts.from(List.of(epic), Set.of(), Set.of())).isEmpty();
	}

	@Test
	void codeAndTestScoreOnlyWhenDoneAndLinkedToANonMergeCommit() {
		Task code = standard(PARENT, "P-1", 5, LEADER, "[\"saga:code\"]", TaskStatus.DONE);
		Task test = standard(CHILD_A, "T-1", 3, STUDENT_A, "[\"saga:test\"]", TaskStatus.DONE);

		List<TaskFact> missing = ContributionTaskFacts.from(List.of(code, test), Set.of(), Set.of());
		assertThat(missing).allMatch(fact -> fact.criterion() == null);

		List<TaskFact> codeOnly = ContributionTaskFacts.from(List.of(code, test), Set.of(), Set.of(PARENT));
		assertThat(criterion(codeOnly, LEADER)).isEqualTo(ContributionCriterion.CODE);
		assertThat(criterion(codeOnly, STUDENT_A)).isNull();

		Task open = standard(CHILD_B, "T-2", 2, STUDENT_B, "[\"saga:code\"]", TaskStatus.IN_PROGRESS);
		List<TaskFact> notDone = ContributionTaskFacts.from(List.of(open), Set.of(), Set.of(CHILD_B));
		assertThat(notDone).singleElement().satisfies(fact -> assertThat(fact.status()).isNotEqualTo(TaskStatus.DONE));

		Task parent = standard(PARENT, "P-1", 10, LEADER, "[\"saga:code\"]", TaskStatus.DONE);
		Task child = subtask(CHILD_A, "P-1", 6, STUDENT_A, "[\"saga:test\"]", TaskStatus.DONE);
		List<TaskFact> commitOnParent = ContributionTaskFacts.from(List.of(parent, child), Set.of(), Set.of(PARENT));
		assertThat(commitOnParent).singleElement().satisfies(fact -> assertThat(fact.criterion()).isNull());
		List<TaskFact> commitOnChild = ContributionTaskFacts.from(List.of(parent, child), Set.of(), Set.of(CHILD_A));
		assertThat(criterion(commitOnChild, STUDENT_A)).isEqualTo(ContributionCriterion.TEST);
	}

	@Test
	void percentBudgetRejectsATotalAboveOneHundred() {
		SubtaskPercentPolicy.requireRoom(6, 0);
		SubtaskPercentPolicy.requireRoom(4, 60);
		assertThatThrownBy(() -> SubtaskPercentPolicy.requireRoom(5, 60))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.TASK_SUBTASK_PERCENT_INVALID);
		assertThatThrownBy(() -> SubtaskPercentPolicy.requireRoom(11, 0))
				.isInstanceOf(IntegrationException.class);
		assertThat(SubtaskPercentPolicy.share(10, 6)).isEqualByComparingTo(new BigDecimal("6"));
		assertThat(SubtaskPercentPolicy.share(null, 6)).isEqualByComparingTo(new BigDecimal("0.6"));
	}

	private static ContributionCriterion criterion(List<TaskFact> facts, UUID student) {
		return facts.stream()
				.filter(fact -> fact.assigneeStudentId().equals(student))
				.findFirst()
				.orElseThrow()
				.criterion();
	}

	private static BigDecimal points(List<TaskFact> facts, UUID student) {
		return facts.stream()
				.filter(fact -> fact.assigneeStudentId().equals(student))
				.findFirst()
				.orElseThrow()
				.storyPoint();
	}

	private static Task standard(
			UUID id, String externalId, Integer storyPoint, UUID assignee, String labels, TaskStatus status) {
		Task task = task(id, externalId, storyPoint, assignee, status);
		task.setIssueTypeLevel("STANDARD");
		task.setLabelsJson(labels);
		task.setSprint(sprint());
		return task;
	}

	private static Task subtask(
			UUID id, String parentExternalId, int storyPoint, UUID assignee, String labels, TaskStatus status) {
		Task task = task(id, "S-" + id, storyPoint, assignee, status);
		task.setIssueTypeLevel("SUBTASK");
		task.setParentExternalId(parentExternalId);
		task.setLabelsJson(labels);
		return task;
	}

	private static Task task(UUID id, String externalId, Integer storyPoint, UUID assignee, TaskStatus status) {
		Task task = new Task();
		task.setId(id);
		task.setExternalId(externalId);
		task.setStoryPoint(storyPoint);
		task.setStatus(status);
		JiraIntegration integration = new JiraIntegration();
		integration.setId(INTEGRATION);
		task.setJiraIntegration(integration);
		StudentProfile student = new StudentProfile();
		student.setId(assignee);
		task.setAssigneeStudent(student);
		return task;
	}

	private static Sprint sprint() {
		Sprint sprint = new Sprint();
		sprint.setId(SPRINT);
		sprint.setName("Sprint 1");
		return sprint;
	}
}
