package com.saga.be.dto.roster;

import java.util.List;
import java.util.UUID;

public record RosterPreviewResponse(
		String previewToken,
		UUID courseId,
		String classCode,
		RosterPreviewSummary summary,
		List<RosterPreviewRow> rows) {}
