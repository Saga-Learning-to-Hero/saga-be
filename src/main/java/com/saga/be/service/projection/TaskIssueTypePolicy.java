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
 * Contribution scoring reads SUBTASK versus STANDARD when splitting a parent's story points.
 */
public final class TaskIssueTypePolicy {

	public enum Level {
		SUBTASK,
		STANDARD,
		EPIC,
		ABOVE_EPIC,
		/** Jira did not report a hierarchy level; never guessed from the type name. */
		UNKNOWN
	}

	private TaskIssueTypePolicy() {}

	public static Level level(IssueTypeOption type) {
		return type == null ? Level.UNKNOWN : fromJira(type.subtask(), type.hierarchyLevel());
	}

	/**
	 * Jira's hierarchyLevel (-1, 0, 1, 2+) decides; without it only the explicit subtask flag can
	 * say SUBTASK. Anything else is UNKNOWN -- a name like "Feature" or "Epic" is never trusted.
	 */
	public static Level fromJira(Boolean subtask, Integer hierarchyLevel) {
		if (hierarchyLevel != null) {
			if (hierarchyLevel < 0) {
				return Level.SUBTASK;
			}
			if (hierarchyLevel == 0) {
				return Level.STANDARD;
			}
			return hierarchyLevel == 1 ? Level.EPIC : Level.ABOVE_EPIC;
		}
		return Boolean.TRUE.equals(subtask) ? Level.SUBTASK : Level.UNKNOWN;
	}

	/**
	 * Writes the issue type Jira reported onto the task. The level is only rewritten when Jira says
	 * what it is, or when the type itself changed (then an unreported level becomes unknown) -- a
	 * payload without hierarchy metadata never erases a level already known for the same type.
	 */
	public static void applyJiraIssueType(
			com.saga.be.entity.jira.Task task, String issueTypeId, Boolean subtask, Integer hierarchyLevel) {
		if (issueTypeId == null || issueTypeId.isBlank()) {
			return;
		}
		Level level = fromJira(subtask, hierarchyLevel);
		boolean typeChanged = !issueTypeId.equals(task.getIssueTypeId());
		task.setIssueTypeId(issueTypeId);
		if (level != Level.UNKNOWN || typeChanged) {
			task.setIssueTypeLevel(storedValue(level));
			task.setJiraHierarchyLevel(hierarchyLevel);
		}
	}

	/** The stored column value, or null when unknown (never stores UNKNOWN). */
	public static String storedValue(Level level) {
		return level == null || level == Level.UNKNOWN ? null : level.name();
	}

	/** API value of a stored level: the stored name, or "UNKNOWN" when null (never null in responses). */
	public static String apiValue(String storedLevel) {
		return fromStored(storedLevel).name();
	}

	/** Reads the stored column; null / unrecognised -> UNKNOWN. */
	public static Level fromStored(String value) {
		if (value == null || value.isBlank()) {
			return Level.UNKNOWN;
		}
		try {
			return Level.valueOf(value.trim());
		} catch (IllegalArgumentException ex) {
			return Level.UNKNOWN;
		}
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
		if (parent != null && (child == Level.UNKNOWN || parent == Level.UNKNOWN)) {
			throw parentInvalid("Jira did not report the hierarchy level of this issue type; sync the Jira source and retry.");
		}
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
			case UNKNOWN -> "An issue of unknown hierarchy level";
		};
	}
}
