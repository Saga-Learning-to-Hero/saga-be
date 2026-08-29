package com.saga.be.dto.roster;

public record RosterPreviewSummary(
		int totalRows,
		int validRows,
		int invalidRows,
		int existingAccounts,
		int newInvitations,
		int alreadyEnrolled,
		int alreadyInvited) {}
