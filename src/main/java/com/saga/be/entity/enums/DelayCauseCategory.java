package com.saga.be.entity.enums;

/**
 * Why a task missed its deadline, as the assignee explains it. Each cause belongs to one group;
 * OTHER always goes to the lecturer because neither the system nor the team leader can place it.
 */
public enum DelayCauseCategory {
	/** Waiting on another task (named by the assignee and checked by the system). */
	BLOCKED_BY_TASK(Group.OBJECTIVE),
	/** Work was added or the requirement changed after the task started. */
	SCOPE_CHANGED(Group.OBJECTIVE),
	/** The task was handed to this person close to its deadline. */
	REASSIGNED_LATE(Group.OBJECTIVE),
	/** The team or lecturer moved the schedule (due date or sprint dates changed). */
	SCHEDULE_CHANGED(Group.OBJECTIVE),
	/** Server, environment or account problem. */
	TECHNICAL_ISSUE(Group.OBJECTIVE),
	/** Illness, hospital, family matter. */
	PERSONAL_EMERGENCY(Group.OBJECTIVE),
	STARTED_LATE(Group.SUBJECTIVE),
	UNDERESTIMATED(Group.SUBJECTIVE),
	NO_PROGRESS(Group.SUBJECTIVE),
	OTHER(Group.OTHER);

	public enum Group {
		OBJECTIVE,
		SUBJECTIVE,
		OTHER
	}

	private final Group group;

	DelayCauseCategory(Group group) {
		this.group = group;
	}

	public Group group() {
		return group;
	}

	/** A written note is required: the system has nothing to check these against. */
	public boolean requiresNote() {
		return this == OTHER || this == TECHNICAL_ISSUE || this == PERSONAL_EMERGENCY;
	}
}
