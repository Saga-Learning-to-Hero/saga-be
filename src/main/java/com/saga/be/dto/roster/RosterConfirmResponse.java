package com.saga.be.dto.roster;

import java.time.LocalDateTime;
import java.util.UUID;

public record RosterConfirmResponse(
		UUID courseId,
		int enrolled,
		int invited,
		int unchanged,
		int emailsEnqueued,
		LocalDateTime confirmedAt) {}
