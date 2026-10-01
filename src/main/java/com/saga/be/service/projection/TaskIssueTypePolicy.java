package com.saga.be.service.projection;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient.IssueTypeOption;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Which issue-type changes and Jira parent links SAGA allows, by Jira's hierarchy level.
 *
 * <ul>
 *   <li>Editing may only switch between normal work items (Task / Story / Feature / Bug ...). An
 *       Epic or a Subtask keeps its type, and nothing becomes an Epic or a Subtask by editing --
 *       Jira itself only allows that through "Move", and a Subtask cannot exist without a parent.
 *   <li>Creating: a Subtask needs a normal task as parent; a normal task may only sit under an
 *       Epic; an Epic (or anything above it) takes no parent.
 * </ul>
 *
 * Contribution scoring never reads the issue type, so none of this changes how points are counted.
 */
public final class TaskIssueTypePolicy {

	public enum Level {
		SUBTASK,
		STANDARD,
		EPIC,
		ABOVE_EPIC
	}

	private TaskIssueTypePolicy() {}

	/** Jira's hierarchyLevel when given (-1, 0, 1, 2+), else the subtask flag, else the name "Epic". */
	public static Level level(IssueTypeOption type) {
		if (type == null) {
			return Level.STANDARD;
		}
		Integer hierarchy = type.hierarchyLevel();
		if (hierarchy != null) {
			if (hierarchy < 0) {
				return Level.SUBTASK;
			}
			if (hierarchy == 0) {
				return Level.STANDARD;
			}
			return hierarchy == 1 ? Level.EPIC : Level.ABOVE_EPIC;
		}
		if (type.subtask()) {
			return Level.SUBTASK;
		}
		String name = type.name() == null ? "" : type.name().trim().toLowerCase(Locale.ROOT);
		return "epic".equals(name) ? Level.EPIC : Level.STANDARD;
	}

	/** Allows an edit only between two normal work-item types; the caller skips an unchanged type. */
	public static void requireEditable(IssueTypeOption current, IssueTypeOption target) {
		Level from = level(current);
		Level to = level(target);
		if (from == Level.STANDARD && to == Level.STANDARD) {
			return;
		}
		throw new IntegrationException(
				IntegrationErrorCode.TASK_ISSUE_TYPE_CHANGE_NOT_ALLOWED,
				HttpStatus.BAD_REQUEST,
				"The issue type can only be changed between Task, Story, Feature and Bug. "
						+ describe(from)
						+ " cannot become "
						+ describe(to).toLowerCase(Locale.ROOT)
						+ "; create a new issue of the right type instead.");
	}

	/** {@code parent} is null when no Jira parent was given. */
	public static void requireParent(Level child, Level parent) {
		if (child == Level.SUBTASK) {
			if (parent == null) {
				throw new IntegrationException(
						IntegrationErrorCode.TASK_SUBTASK_PARENT_REQUIRED,
						HttpStatus.BAD_REQUEST,
						"A Subtask must be created under a parent task.");
			}
			if (parent != Level.STANDARD) {
				throw parentInvalid("A Subtask's parent must be a Task, Story, Feature or Bug, not " + describe(parent) + ".");
			}
			return;
		}
		if (parent == null) {
			return;
		}
		if (child == Level.STANDARD && parent != Level.EPIC) {
			throw parentInvalid("A Task, Story, Feature or Bug can only be placed under an Epic, not under "
					+ describe(parent).toLowerCase(Locale.ROOT) + ".");
		}
		if (child == Level.EPIC || child == Level.ABOVE_EPIC) {
			throw parentInvalid("An Epic cannot have a parent task.");
		}
	}

	private static IntegrationException parentInvalid(String message) {
		return new IntegrationException(IntegrationErrorCode.TASK_PARENT_TYPE_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	private static String describe(Level level) {
		return switch (level) {
			case SUBTASK -> "A Subtask";
			case STANDARD -> "A normal task";
			case EPIC -> "An Epic";
			case ABOVE_EPIC -> "An issue above Epic level";
		};
	}
}
