package com.saga.be.dto.team;

import java.time.LocalDateTime;
import java.util.UUID;

public record TeamConfirmResponse(
		UUID courseId,
		int createdTeams,
		int updatedTeams,
		int assignedMembers,
		int reassignedMembers,
		int updatedRoles,
		int unchanged,
		int emailsEnqueued,
		LocalDateTime confirmedAt) {}
