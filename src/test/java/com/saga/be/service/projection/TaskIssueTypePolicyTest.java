package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient.IssueTypeOption;
import com.saga.be.service.projection.TaskIssueTypePolicy.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskIssueTypePolicyTest {

	private static final IssueTypeOption TASK = new IssueTypeOption("1", "Task", null, false, 0);
	private static final IssueTypeOption STORY = new IssueTypeOption("2", "Story", null, false, 0);
	private static final IssueTypeOption FEATURE = new IssueTypeOption("3", "Feature", null, false, 0);
	private static final IssueTypeOption BUG = new IssueTypeOption("4", "Bug", null, false, 0);
	private static final IssueTypeOption EPIC = new IssueTypeOption("5", "Epic", null, false, 1);
	private static final IssueTypeOption SUBTASK = new IssueTypeOption("6", "Subtask", null, true, -1);

	@Test
	void levelFollowsJirasHierarchyLevelFirst() {
		assertThat(TaskIssueTypePolicy.level(TASK)).isEqualTo(Level.STANDARD);
		assertThat(TaskIssueTypePolicy.level(EPIC)).isEqualTo(Level.EPIC);
		assertThat(TaskIssueTypePolicy.level(SUBTASK)).isEqualTo(Level.SUBTASK);
		assertThat(TaskIssueTypePolicy.level(new IssueTypeOption("7", "Initiative", null, false, 2))).isEqualTo(Level.ABOVE_EPIC);
		// A renamed type is judged by its level, not its name.
		assertThat(TaskIssueTypePolicy.level(new IssueTypeOption("8", "Hạng mục lớn", null, false, 1))).isEqualTo(Level.EPIC);
		assertThat(TaskIssueTypePolicy.level(new IssueTypeOption("9", "Việc con", null, true, -1))).isEqualTo(Level.SUBTASK);
	}

	@Test
	void withoutAHierarchyLevelTheSubtaskFlagAndTheEpicNameDecide() {
		assertThat(TaskIssueTypePolicy.level(new IssueTypeOption("1", "Sub-task", null, true, null))).isEqualTo(Level.SUBTASK);
		assertThat(TaskIssueTypePolicy.level(new IssueTypeOption("2", " EPIC ", null))).isEqualTo(Level.EPIC);
		assertThat(TaskIssueTypePolicy.level(new IssueTypeOption("3", "Feature", null))).isEqualTo(Level.STANDARD);
		assertThat(TaskIssueTypePolicy.level(null)).isEqualTo(Level.STANDARD);
	}

	@Test
	void editMaySwitchFreelyBetweenTheFourNormalTypes() {
		for (IssueTypeOption from : List.of(TASK, STORY, FEATURE, BUG)) {
			for (IssueTypeOption to : List.of(TASK, STORY, FEATURE, BUG)) {
				assertThatCode(() -> TaskIssueTypePolicy.requireEditable(from, to)).doesNotThrowAnyException();
			}
		}
	}

	@Test
	void editNeverCrossesALevel() {
		List<IssueTypeOption[]> refused = List.of(
				new IssueTypeOption[] {TASK, SUBTASK},
				new IssueTypeOption[] {TASK, EPIC},
				new IssueTypeOption[] {SUBTASK, TASK},
				new IssueTypeOption[] {EPIC, STORY},
				new IssueTypeOption[] {SUBTASK, EPIC},
				new IssueTypeOption[] {EPIC, EPIC});
		for (IssueTypeOption[] pair : refused) {
			assertThatThrownBy(() -> TaskIssueTypePolicy.requireEditable(pair[0], pair[1]))
					.isInstanceOf(IntegrationException.class)
					.extracting(ex -> ((IntegrationException) ex).getCode())
					.isEqualTo(IntegrationErrorCode.TASK_ISSUE_TYPE_CHANGE_NOT_ALLOWED);
		}
	}

	@Test
	void parentRules() {
		assertThatCode(() -> TaskIssueTypePolicy.requireParent(Level.SUBTASK, Level.STANDARD)).doesNotThrowAnyException();
		assertThatCode(() -> TaskIssueTypePolicy.requireParent(Level.STANDARD, Level.EPIC)).doesNotThrowAnyException();
		assertThatCode(() -> TaskIssueTypePolicy.requireParent(Level.STANDARD, null)).doesNotThrowAnyException();
		assertThatCode(() -> TaskIssueTypePolicy.requireParent(Level.EPIC, null)).doesNotThrowAnyException();

		assertCode(Level.SUBTASK, null, IntegrationErrorCode.TASK_SUBTASK_PARENT_REQUIRED);
		assertCode(Level.SUBTASK, Level.EPIC, IntegrationErrorCode.TASK_PARENT_TYPE_INVALID);
		assertCode(Level.SUBTASK, Level.SUBTASK, IntegrationErrorCode.TASK_PARENT_TYPE_INVALID);
		assertCode(Level.STANDARD, Level.STANDARD, IntegrationErrorCode.TASK_PARENT_TYPE_INVALID);
		assertCode(Level.STANDARD, Level.SUBTASK, IntegrationErrorCode.TASK_PARENT_TYPE_INVALID);
		assertCode(Level.EPIC, Level.EPIC, IntegrationErrorCode.TASK_PARENT_TYPE_INVALID);
	}

	private static void assertCode(Level child, Level parent, IntegrationErrorCode code) {
		assertThatThrownBy(() -> TaskIssueTypePolicy.requireParent(child, parent))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(code);
	}
}
