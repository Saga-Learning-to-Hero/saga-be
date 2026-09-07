package com.saga.be.dto.roster;

import java.util.UUID;

public record RosterStudentMutationResponse(
		UUID courseId,
		String action,
		int emailsEnqueued,
		boolean teamMembershipRemoved,
		CourseRosterEntryResponse entry) {}
