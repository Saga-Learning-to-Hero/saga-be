package com.saga.be.entity.enums;

public enum StudentInvitationStatus {
	PENDING,
	SENT,
	FAILED,
	CANCELLED,
	CLAIMED;

	/** Outstanding invitation: created or historically marked delivered. Claimable. */
	public boolean isOutstanding() {
		return this == PENDING || this == SENT;
	}
}
