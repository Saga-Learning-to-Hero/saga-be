package com.saga.be.dto.team;

import java.util.List;
import java.util.UUID;

public record TeamPreviewResponse(
		String previewToken,
		UUID courseId,
		String classCode,
		boolean hasBlockingErrors,
		List<String> blockingErrors,
		TeamPreviewSummary summary,
		List<TeamPreviewRow> rows) {}
