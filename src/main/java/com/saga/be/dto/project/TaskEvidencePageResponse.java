package com.saga.be.dto.project;

import java.util.List;
import java.util.UUID;

public record TaskEvidencePageResponse(
		UUID taskId, TaskEvidenceType type, int page, int size, long total, List<TaskEvidenceItem> items)
		implements TaskEvidenceResponse {}
