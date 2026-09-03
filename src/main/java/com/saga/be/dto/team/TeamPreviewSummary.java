package com.saga.be.dto.team;

public record TeamPreviewSummary(
		int totalRows,
		int validRows,
		int invalidRows,
		int readyCreate,
		int readyAssign,
		int readyReassign,
		int alreadyAssigned,
		int blockingErrorCount) {}
