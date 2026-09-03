package com.saga.be.dto.team;

import com.saga.be.entity.enums.TeamRowAction;
import java.util.List;
import java.util.UUID;

public record TeamPreviewRow(
		int rowNumber,
		UUID courseEnrollmentId,
		String classCode,
		String fullName,
		String studentCode,
		String email,
		Integer teamNo,
		String teamName,
		String teamRole,
		TeamRowAction action,
		List<String> errors,
		List<String> warnings) {}
