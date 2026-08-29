package com.saga.be.dto.roster;

import com.saga.be.entity.enums.RosterRowAction;
import java.util.List;

public record RosterPreviewRow(
		int rowNumber,
		String classCode,
		String fullName,
		String studentCode,
		String email,
		String memberCode,
		RosterRowAction action,
		List<String> errors,
		List<String> warnings) {}
