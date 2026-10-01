package com.saga.be.entity.enums;

/** Small value sets of a task delay case. */
public final class DelayCaseEnums {

	private DelayCaseEnums() {}

	/** How the explanation compares with what the system can see. */
	public enum Verification {
		/** The data supports the explanation (e.g. the named blocking task really was late). */
		CONSISTENT,
		/** The data contradicts it (e.g. the named blocking task was done before this deadline). */
		MISMATCH,
		/** Nothing in the data can confirm or deny it (needs a person to judge the evidence). */
		UNVERIFIABLE
	}

	public enum LeaderDecision {
		AGREE,
		DISAGREE
	}

	/** The lecturer's final classification. */
	public enum Outcome {
		OBJECTIVE,
		SUBJECTIVE
	}

	public enum CloseReason {
		/** Subjective cause, leader agreed, no contradiction in the data. */
		LEADER_CONFIRMED,
		LECTURER_DECIDED,
		/** No explanation within the explanation window. */
		EXPLANATION_EXPIRED
	}

	/** Task fields whose changes are recorded for delay verification. */
	public enum TaskChangeField {
		DUE_DATE,
		STORY_POINT,
		ASSIGNEE
	}
}
