package com.saga.be.entity.enums;

/** Lifecycle of a task delay case. */
public enum DelayCaseStatus {
	/** Waiting for the assignee's explanation (until explanationDueAt). */
	OPEN,
	/** Explanation sent; waiting for the team leader to agree or disagree. */
	AWAITING_LEADER,
	/** Waiting for the lecturer's decision. */
	AWAITING_LECTURER,
	/** Closed as an objective delay: not counted as late in the on-time rate. */
	CLOSED_OBJECTIVE,
	/** Closed as a subjective delay (or no explanation in time): counted as late. */
	CLOSED_SUBJECTIVE;

	public boolean closed() {
		return this == CLOSED_OBJECTIVE || this == CLOSED_SUBJECTIVE;
	}
}
